use crate::App;
use crate::web_panel::*;
use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::middleware;
use axum::routing::get;
use axum::routing::post;
use axum::routing::put;
use std::sync::Arc;

pub(crate) fn panel_router(app: Arc<App>) -> Router {
    let state = WebState { app: app.clone() };
    let protected = Router::new()
        .route("/api/stats", get(stats))
        .route("/api/memory/trim", post(memory_trim))
        .route("/api/settings", get(settings_get).post(settings_post))
        .route("/api/clients", get(clients_get).post(clients_create))
        .route(
            "/api/clients/{password}",
            get(client_get).post(client_update).delete(client_delete),
        )
        .route("/api/clients/{password}/toggle", post(client_toggle))
        .route("/api/clients/{password}/unbind", post(client_unbind))
        .route(
            "/api/clients/{password}/reset-traffic",
            post(client_reset_traffic),
        )
        .route("/api/clients/{password}/extend", post(client_extend))
        .route(
            "/api/clients/{password}/sub-link/regenerate",
            post(client_sub_link_regenerate),
        )
        .route("/api/groups", get(groups_get).post(groups_create))
        .route("/api/groups/{id}", put(groups_update).delete(groups_delete))
        .route(
            "/api/groups/{id}/sub-link/regenerate",
            post(group_sub_link_regenerate),
        )
        .route(
            "/api/routing-rules",
            get(routing_rules_get)
                .post(routing_rules_create)
                .layer(DefaultBodyLimit::max(ROUTING_RULE_BODY_LIMIT_BYTES)),
        )
        .route(
            "/api/routing-rules/{id}",
            put(routing_rules_update)
                .delete(routing_rules_delete)
                .layer(DefaultBodyLimit::max(ROUTING_RULE_BODY_LIMIT_BYTES)),
        )
        .route("/api/template-domains", get(template_domains_get))
        .route("/api/template-ips", get(template_ips_get))
        .route("/api/preset-apps", get(preset_apps_get))
        .route("/api/logs", get(logs_get))
        .route("/api/logs/toggle", post(logs_toggle))
        .route("/api/logs/clear", post(logs_clear))
        .route("/api/logs/download", get(logs_download))
        .route(
            "/api/local-proxy",
            get(local_proxy_get).post(local_proxy_create),
        )
        .route(
            "/api/local-proxy/profiles/{id}",
            put(local_proxy_update).delete(local_proxy_delete),
        )
        .route("/api/local-proxy/activate/{id}", post(local_proxy_activate))
        .route("/api/local-proxy/deactivate", post(local_proxy_deactivate))
        .route("/api/logout", post(logout))
        .route("/api/logout_all", post(logout_all))
        .route("/api/links", get(api_links_get))
        .route("/api/links/batch-vk", post(api_links_batch_vk))
        .route("/api/clients/{password}/vk", put(api_client_vk_update))
        .route("/api/settings/dns", post(api_settings_dns))
        .route(
            "/api/settings/restart-interval",
            post(api_settings_restart_interval),
        )
        .route("/api/clients/batch-toggle", post(api_clients_batch_toggle))
        .route("/api/clients/batch-extend", post(api_clients_batch_extend))
        .route(
            "/api/clients/batch-reset-traffic",
            post(api_clients_batch_reset_traffic),
        )
        .route("/api/clients/batch-delete", post(api_clients_batch_delete))
        .route("/api/clients/batch-group", post(api_clients_batch_group))
        .route("/api/groups/{id}/clients", get(group_clients_get))
        .route("/api/diagnostics/dpi", get(api_diagnostics_dpi))
        .route("/api/export", get(api_export_get))
        .route(
            "/api/import",
            post(api_import_post).layer(DefaultBodyLimit::max(ROUTING_RULE_BODY_LIMIT_BYTES)),
        )
        .route("/api/reboot", post(reboot))
        .route_layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ));

    let panel_routes = Router::new()
        .route("/", get(panel_root))
        .route("/manifest.webmanifest", get(pwa_manifest))
        .route("/sw.js", get(pwa_service_worker))
        .route("/pwa-icon.svg", get(pwa_icon))
        .route("/pwa-icon-192.png", get(pwa_icon_192))
        .route("/pwa-icon-512.png", get(pwa_icon_512))
        .route("/qrcode.min.js", get(qrcode_js))
        .route("/favicon.ico", get(favicon))
        .route("/api/login", post(login))
        .merge(protected);

    let mut router = Router::new();
    let secret = app.web_secret_path.trim().trim_matches('/');
    if secret.is_empty() {
        router = router.merge(panel_routes);
    } else {
        let prefix = format!("/{}", secret);
        let trailing_prefix = format!("{}/", prefix);
        router = router
            .route("/", get(root_with_secret))
            .route(&trailing_prefix, get(panel_root))
            .nest(&prefix, panel_routes);
        router = router
            .route("/manifest.webmanifest", get(pwa_manifest))
            .route("/sw.js", get(pwa_service_worker))
            .route("/pwa-icon.svg", get(pwa_icon))
            .route("/pwa-icon-192.png", get(pwa_icon_192))
            .route("/pwa-icon-512.png", get(pwa_icon_512))
            .route("/qrcode.min.js", get(qrcode_js))
            .route("/favicon.ico", get(favicon));
    }

    router
        .route("/{uuid}/sub", get(client_sub_page))
        .route("/{uuid}/sub/", get(client_sub_page))
        .route("/{uuid}/json", get(universal_sub_json))
        .route("/{uuid}/json/", get(universal_sub_json))
        .route("/{uuid}/sub.json", get(client_sub_json))
        .route("/api/sub/{uuid}", get(client_sub_json))
        .route("/api/sub/{uuid}/", get(client_sub_json))
        .route("/{uuid}/sub-group", get(group_sub_page))
        .route("/{uuid}/sub-group/", get(group_sub_page))
        .route("/{uuid}/sub-group.json", get(group_sub_json))
        .route("/api/sub-group/{uuid}", get(group_sub_json))
        .route("/api/sub-group/{uuid}/", get(group_sub_json))
        .fallback(fallback_reject)
        .layer(DefaultBodyLimit::max(WEB_BODY_LIMIT_BYTES))
        .layer(middleware::from_fn(security_headers))
        .with_state(state)
}
