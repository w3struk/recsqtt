use crate::App;
use crate::web_panel::*;
use axum::body::Body;
use axum::http::Request;
use axum::http::StatusCode;
use axum::http::header;
use axum::middleware::Next;
use axum::response::IntoResponse;
use axum::response::Response;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::io::AsyncWriteExt;

pub(crate) const MAX_WEB_CONNECTIONS: usize = 64;

pub(crate) const STEALTH_ABORT_HEADER: &str = "x-csqtt-stealth-rst";

pub(crate) fn stealth_abort_response() -> Response {
    (
        StatusCode::OK,
        [
            (header::HeaderName::from_static(STEALTH_ABORT_HEADER), "1"),
            (header::CONNECTION, "close"),
        ],
        "",
    )
        .into_response()
}

#[derive(Debug)]
pub struct StealthAbortController {
    pub(crate) aborted: AtomicBool,
    pub(crate) last_activity_ms: AtomicU64,
    pub(crate) birth_ms: AtomicU64,
    #[cfg(unix)]
    pub(crate) raw_fd: std::sync::Mutex<Option<std::os::fd::RawFd>>,
}

pub(crate) static WEB_CONNECTION_CONTROLLERS: Mutex<Vec<std::sync::Weak<StealthAbortController>>> =
    Mutex::new(Vec::new());

pub(crate) const WEB_IDLE_ABORT_MS: u64 = 120_000;

pub(crate) const WEB_MAX_CONNECTION_LIFETIME_MS: u64 = 30 * 60_000;

pub(crate) fn web_watchdog_now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub(crate) async fn web_slowloris_watchdog() {
    let mut timer = tokio::time::interval(Duration::from_millis(5_000));
    loop {
        timer.tick().await;
        let now = web_watchdog_now_ms();
        let stale = {
            let mut registry = WEB_CONNECTION_CONTROLLERS
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            registry.shrink_to_fit();
            let mut stale = Vec::new();
            registry.retain(|weak| match weak.upgrade() {
                Some(controller) => {
                    stale.push(controller);
                    true
                }
                None => false,
            });
            stale
        };
        if stale.is_empty() {
            continue;
        }
        for controller in stale {
            if controller.aborted.load(Ordering::Relaxed) {
                continue;
            }
            let idle_for = now.saturating_sub(controller.last_activity_ms.load(Ordering::Acquire));
            if idle_for > WEB_IDLE_ABORT_MS {
                controller.abort();
                continue;
            }
            let registered_at = controller.birth_ms.load(Ordering::Acquire);
            if registered_at != 0
                && now.saturating_sub(registered_at) > WEB_MAX_CONNECTION_LIFETIME_MS
            {
                controller.abort();
            }
        }
    }
}

pub(crate) fn register_web_controller(controller: &Arc<StealthAbortController>) {
    let time = web_watchdog_now_ms();
    controller.last_activity_ms.store(time, Ordering::Release);
    controller.birth_ms.store(time, Ordering::Release);
    let mut registry = WEB_CONNECTION_CONTROLLERS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if registry.len() > 4 * MAX_WEB_CONNECTIONS {
        registry.retain(|weak| weak.strong_count() > 0);
    }
    registry.push(Arc::downgrade(controller));
}

impl StealthAbortController {
    pub fn new(#[allow(unused_variables)] stream: &tokio::net::TcpStream) -> Self {
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;
            Self {
                aborted: AtomicBool::new(false),
                last_activity_ms: AtomicU64::new(web_watchdog_now_ms()),
                birth_ms: AtomicU64::new(0),
                raw_fd: std::sync::Mutex::new(Some(stream.as_raw_fd())),
            }
        }
        #[cfg(not(unix))]
        {
            Self {
                aborted: AtomicBool::new(false),
                last_activity_ms: AtomicU64::new(web_watchdog_now_ms()),
                birth_ms: AtomicU64::new(0),
            }
        }
    }

    pub fn touch(&self) {
        self.last_activity_ms
            .store(web_watchdog_now_ms(), Ordering::Release);
    }

    pub fn abort(&self) {
        if self.aborted.swap(true, Ordering::SeqCst) {
            return;
        }
        #[cfg(unix)]
        {
            let guard = self
                .raw_fd
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if let Some(fd) = *guard {
                let linger = libc::linger {
                    l_onoff: 1,
                    l_linger: 0,
                };
                unsafe {
                    libc::setsockopt(
                        fd,
                        libc::SOL_SOCKET,
                        libc::SO_LINGER,
                        (&linger as *const libc::linger).cast(),
                        std::mem::size_of::<libc::linger>() as libc::socklen_t,
                    );
                    libc::shutdown(fd, libc::SHUT_RDWR);
                }
            }
        }
    }

    pub(crate) fn detach(&self) {
        #[cfg(unix)]
        {
            *self
                .raw_fd
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        }
    }

    pub fn is_aborted(&self) -> bool {
        self.aborted.load(Ordering::Relaxed)
    }
}

pub enum DualProtocolStream {
    #[allow(dead_code)]
    Plain {
        stream: tokio::net::TcpStream,
        controller: Arc<StealthAbortController>,
        _permit: tokio::sync::OwnedSemaphorePermit,
    },
    Tls {
        stream: Box<tokio_rustls::server::TlsStream<tokio::net::TcpStream>>,
        controller: Arc<StealthAbortController>,
        _permit: tokio::sync::OwnedSemaphorePermit,
    },
}

impl DualProtocolStream {
    pub fn controller(&self) -> &Arc<StealthAbortController> {
        match self {
            DualProtocolStream::Plain { controller, .. } => controller,
            DualProtocolStream::Tls { controller, .. } => controller,
        }
    }

    #[allow(deprecated)]
    pub(crate) fn abort_tcp(&self) {
        self.controller().abort();
        match self {
            DualProtocolStream::Plain { stream, .. } => {
                let _ = stream.set_linger(Some(Duration::ZERO));
            }
            DualProtocolStream::Tls { stream, .. } => {
                let _ = stream.get_ref().0.set_linger(Some(Duration::ZERO));
            }
        }
    }
}

impl Drop for DualProtocolStream {
    fn drop(&mut self) {
        self.controller().detach();
    }
}

impl tokio::io::AsyncRead for DualProtocolStream {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                let poll = std::pin::Pin::new(stream).poll_read(cx, buf);
                if matches!(poll, std::task::Poll::Ready(Ok(())) if !buf.filled().is_empty()) {
                    self.controller().touch();
                }
                poll
            }
            DualProtocolStream::Tls { stream, .. } => {
                let stream = stream.as_mut();
                let poll = std::pin::Pin::new(stream).poll_read(cx, buf);
                if matches!(poll, std::task::Poll::Ready(Ok(())) if !buf.filled().is_empty()) {
                    self.controller().touch();
                }
                poll
            }
        }
    }
}

impl tokio::io::AsyncWrite for DualProtocolStream {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.controller().is_aborted()
            || buf
                .windows(STEALTH_ABORT_HEADER.len())
                .any(|w| w == STEALTH_ABORT_HEADER.as_bytes())
        {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                let poll = std::pin::Pin::new(stream).poll_write(cx, buf);
                if poll.is_ready() {
                    self.controller().touch();
                }
                poll
            }
            DualProtocolStream::Tls { stream, .. } => {
                let stream = stream.as_mut();
                let poll = std::pin::Pin::new(stream).poll_write(cx, buf);
                if poll.is_ready() {
                    self.controller().touch();
                }
                poll
            }
        }
    }

    fn poll_write_vectored(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        bufs: &[std::io::IoSlice<'_>],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                std::pin::Pin::new(stream).poll_write_vectored(cx, bufs)
            }
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_write_vectored(cx, bufs)
            }
        }
    }

    fn is_write_vectored(&self) -> bool {
        match self {
            DualProtocolStream::Plain { stream, .. } => stream.is_write_vectored(),
            DualProtocolStream::Tls { stream, .. } => stream.is_write_vectored(),
        }
    }

    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => std::pin::Pin::new(stream).poll_flush(cx),
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_flush(cx)
            }
        }
    }

    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.controller().is_aborted() {
            self.abort_tcp();
            return std::task::Poll::Ready(Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionReset,
                "stealth connection reset",
            )));
        }
        match &mut *self {
            DualProtocolStream::Plain { stream, .. } => {
                std::pin::Pin::new(stream).poll_shutdown(cx)
            }
            DualProtocolStream::Tls { stream, .. } => {
                std::pin::Pin::new(stream.as_mut()).poll_shutdown(cx)
            }
        }
    }
}

pub(crate) fn collapse_consecutive_slashes(path: &str) -> String {
    if !path.contains("//") {
        return path.to_string();
    }
    let mut result = String::with_capacity(path.len());
    let mut prev_slash = false;
    for c in path.chars() {
        if c == '/' {
            if !prev_slash {
                result.push(c);
                prev_slash = true;
            }
        } else {
            result.push(c);
            prev_slash = false;
        }
    }
    result
}

#[derive(Clone)]
pub struct StealthConnectionService<S> {
    pub(crate) inner: S,
    pub(crate) controller: Arc<StealthAbortController>,
}

impl<S, ReqBody> tower_service::Service<axum::http::Request<ReqBody>>
    for StealthConnectionService<S>
where
    S: tower_service::Service<axum::http::Request<ReqBody>>,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = S::Future;

    fn poll_ready(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, mut req: axum::http::Request<ReqBody>) -> Self::Future {
        req.extensions_mut().insert(self.controller.clone());
        let uri = req.uri().clone();
        let path = uri.path();
        if path.contains("//") {
            let new_path = collapse_consecutive_slashes(path);
            let new_uri_str = if let Some(query) = uri.query() {
                format!("{}?{}", new_path, query)
            } else {
                new_path
            };
            if let Ok(new_uri) = new_uri_str.parse() {
                *req.uri_mut() = new_uri;
            }
        }
        self.inner.call(req)
    }
}

#[derive(Clone)]
pub struct DualAcceptor {
    pub(crate) tls_acceptor: axum_server::tls_rustls::RustlsAcceptor,
    pub(crate) connections: Arc<tokio::sync::Semaphore>,
    pub(crate) app: Arc<App>,
}

impl DualAcceptor {
    pub fn new(config: axum_server::tls_rustls::RustlsConfig, app: Arc<App>) -> Self {
        Self {
            tls_acceptor: axum_server::tls_rustls::RustlsAcceptor::new(config),
            connections: Arc::new(tokio::sync::Semaphore::new(MAX_WEB_CONNECTIONS)),
            app,
        }
    }
}

pub(crate) async fn is_valid_secret_path(raw_path: &str, app: &Arc<App>) -> bool {
    let path_no_query = raw_path.split('?').next().unwrap_or("").trim();
    let normalized = collapse_consecutive_slashes(path_no_query);
    let secret = app.web_secret_path.trim().trim_matches('/');
    if !secret.is_empty() {
        let prefix = format!("/{}", secret);
        if normalized == prefix || normalized.starts_with(&format!("{}/", prefix)) {
            return true;
        }
    } else {
        return true;
    }

    let trimmed = normalized.trim_matches('/');
    let first_segment = trimmed.split('/').next().unwrap_or("");
    if !first_segment.is_empty() {
        let db = app.db.read().await;
        if db
            .passwords
            .values()
            .any(|p| !p.sub_uuid.is_empty() && p.sub_uuid == first_segment)
        {
            return true;
        }
        if db
            .groups
            .values()
            .any(|g| !g.sub_uuid.is_empty() && g.sub_uuid == first_segment)
        {
            return true;
        }
    }

    false
}

impl<S> axum_server::accept::Accept<tokio::net::TcpStream, S> for DualAcceptor
where
    S: Send + Clone + 'static,
{
    type Stream = DualProtocolStream;
    type Service = StealthConnectionService<S>;
    type Future = std::pin::Pin<
        Box<
            dyn std::future::Future<Output = std::io::Result<(Self::Stream, Self::Service)>> + Send,
        >,
    >;

    #[allow(deprecated)]
    fn accept(&self, mut stream: tokio::net::TcpStream, service: S) -> Self::Future {
        let tls_acceptor = self.tls_acceptor.clone();
        let connections = self.connections.clone();
        let app = self.app.clone();
        Box::pin(async move {
            let permit = connections.try_acquire_owned().map_err(|_| {
                std::io::Error::new(
                    std::io::ErrorKind::ConnectionAborted,
                    "web connection limit",
                )
            })?;
            let mut buf = [0u8; 1];
            let _ = tokio::time::timeout(Duration::from_secs(10), stream.peek(&mut buf))
                .await
                .map_err(|_| {
                    let _ = stream.set_linger(Some(Duration::ZERO));
                    std::io::Error::new(std::io::ErrorKind::TimedOut, "web connection timed out")
                })??;
            if buf[0] == 0x16 {
                let controller = Arc::new(StealthAbortController::new(&stream));
                register_web_controller(&controller);
                let (tls_stream, service) = tokio::time::timeout(
                    Duration::from_secs(10),
                    tls_acceptor.accept(stream, service),
                )
                .await
                .map_err(|_| {
                    std::io::Error::new(std::io::ErrorKind::TimedOut, "TLS handshake timed out")
                })??;
                Ok((
                    DualProtocolStream::Tls {
                        stream: Box::new(tls_stream),
                        controller: controller.clone(),
                        _permit: permit,
                    },
                    StealthConnectionService {
                        inner: service,
                        controller,
                    },
                ))
            } else {
                let mut http_buf = [0u8; 2048];
                let n =
                    match tokio::time::timeout(Duration::from_secs(5), stream.read(&mut http_buf))
                        .await
                    {
                        Ok(Ok(n)) if n > 0 => n,
                        _ => {
                            let _ = stream.set_linger(Some(Duration::ZERO));
                            drop(stream);
                            return Err(std::io::Error::new(
                                std::io::ErrorKind::ConnectionReset,
                                "plain HTTP rejected",
                            ));
                        }
                    };
                let req_str = String::from_utf8_lossy(&http_buf[..n]);
                let first_line = req_str.lines().next().unwrap_or("");
                let mut parts = first_line.split_whitespace();
                let _method = parts.next().unwrap_or("");
                let raw_path = parts.next().unwrap_or("");

                let host_header = req_str
                    .lines()
                    .find(|l| l.to_ascii_lowercase().starts_with("host:"))
                    .and_then(|l| l.split_once(':'))
                    .map(|(_, h)| h.trim())
                    .unwrap_or("");

                if is_valid_secret_path(raw_path, &app).await {
                    let public_ip = app.public_ip.read().await.clone();
                    let target_host = match parse_host_header(host_header) {
                        Some((name, _))
                            if name.parse::<std::net::IpAddr>().is_ok() || name == public_ip =>
                        {
                            if host_header.contains(':')
                                || app.web_port == 443
                                || app.web_port == 80
                            {
                                host_header.to_string()
                            } else {
                                format!("{}:{}", host_header, app.web_port)
                            }
                        }
                        _ => {
                            let ip_str = if !public_ip.is_empty() && public_ip != "0.0.0.0" {
                                public_ip
                            } else {
                                "127.0.0.1".to_string()
                            };
                            if app.web_port == 443 {
                                ip_str
                            } else {
                                format!("{}:{}", ip_str, app.web_port)
                            }
                        }
                    };

                    let redirect_url = format!("https://{}{}", target_host, raw_path);
                    let resp = format!(
                        "HTTP/1.1 301 Moved Permanently\r\nLocation: {redirect_url}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                    );
                    let _ = tokio::time::timeout(
                        Duration::from_secs(2),
                        stream.write_all(resp.as_bytes()),
                    )
                    .await;
                    let _ = stream.flush().await;
                    let _ = stream.shutdown().await;
                    drop(stream);
                    Err(std::io::Error::other("redirected HTTP to HTTPS"))
                } else {
                    let _ = stream.set_linger(Some(Duration::ZERO));
                    drop(stream);
                    Err(std::io::Error::new(
                        std::io::ErrorKind::ConnectionReset,
                        "plain HTTP rejected (dead RST)",
                    ))
                }
            }
        })
    }
}

pub(crate) async fn security_headers(mut request: Request<Body>, next: Next) -> Response {
    let controller = request
        .extensions()
        .get::<Arc<StealthAbortController>>()
        .cloned();
    let uri = request.uri().clone();
    let path = uri.path();
    if path.contains("//") {
        let new_path = collapse_consecutive_slashes(path);
        let new_uri_str = if let Some(query) = uri.query() {
            format!("{}?{}", new_path, query)
        } else {
            new_path
        };
        if let Ok(new_uri) = new_uri_str.parse() {
            *request.uri_mut() = new_uri;
        }
    }
    let mut response = next.run(request).await;
    if response.headers().contains_key(STEALTH_ABORT_HEADER) {
        if let Some(controller) = controller {
            controller.abort();
        }
        return response;
    }
    let h = response.headers_mut();
    if !h.contains_key(header::CACHE_CONTROL) {
        h.insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store"),
        );
    }
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        header::HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::X_FRAME_OPTIONS,
        header::HeaderValue::from_static("DENY"),
    );
    h.insert(
        header::REFERRER_POLICY,
        header::HeaderValue::from_static("no-referrer"),
    );
    h.insert(
        header::CONTENT_SECURITY_POLICY,
        header::HeaderValue::from_static(
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'",
        ),
    );
    h.insert(
        header::HeaderName::from_static("strict-transport-security"),
        header::HeaderValue::from_static("max-age=15552000"),
    );
    response
}
