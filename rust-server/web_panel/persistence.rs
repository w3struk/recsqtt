use crate::log_event;
use crate::web_panel::*;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use std::time::Duration;

pub(crate) async fn refresh_credentials_report(state: &WebState) -> Result<(), String> {
    match crate::protocol::refresh_credentials(&state.app).await {
        Ok(()) => Ok(()),
        Err(error) => {
            let message = format!("{error:#}");
            log_event(
                &state.app,
                "ERROR",
                "AUTH",
                &format!("Change saved but credential reload failed: {message}"),
            );
            Err(message)
        }
    }
}

pub(crate) async fn wait_for_persistence(state: &WebState, revision: u64) -> Result<(), Response> {
    match tokio::time::timeout(
        Duration::from_secs(15),
        state.app.db_persistence.wait(revision),
    )
    .await
    {
        Ok(Ok(())) => Ok(()),
        Ok(Err(error)) => {
            eprintln!("[WEB] Failed to persist database mutation: {error:#}");
            Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                "failed to persist change",
            )
                .into_response())
        }
        Err(_) => Err((
            StatusCode::GATEWAY_TIMEOUT,
            "database persistence is taking too long; refresh before retrying",
        )
            .into_response()),
    }
}
