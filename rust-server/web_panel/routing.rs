use crate::log_event;
use crate::model;
use crate::model::RoutingRule;
use crate::model::random_token;
use crate::protocol;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Path;
use axum::extract::Query;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use serde::Serialize;

#[derive(Deserialize, Default)]
pub(crate) struct DpiDiagnosticsQuery {
    #[serde(default)]
    pub(crate) limit: Option<usize>,
}

pub(crate) async fn api_diagnostics_dpi(
    Query(query): Query<DpiDiagnosticsQuery>,
) -> impl IntoResponse {
    let limit = query.limit.unwrap_or(20).clamp(1, 100);
    let frames: Vec<protocol::DpiFrame> = protocol::DPI_RING
        .read()
        .map(|ring| {
            let count = limit.min(ring.len());
            ring.iter().skip(ring.len() - count).cloned().collect()
        })
        .unwrap_or_default();
    Json(serde_json::json!({
        "ok": true,
        "ring_len": protocol::dpi_ring_len(),
        "frames": frames,
    }))
}

#[derive(Serialize)]
pub(crate) struct RoutingRuleInfo {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) is_whitelist: bool,
    pub(crate) mode: String,
    pub(crate) packages: Vec<String>,
    pub(crate) template_domains: bool,
    pub(crate) template_ips: bool,
    pub(crate) preset_apps: bool,
    pub(crate) direct_domains: Vec<String>,
    pub(crate) domains: Vec<String>,
    pub(crate) domains_is_whitelist: bool,
    pub(crate) domains_mode: String,
    pub(crate) ip_is_whitelist: bool,
    pub(crate) ip_mode: String,
    pub(crate) ips: Vec<String>,
    pub(crate) assigned_groups: Vec<String>,
    pub(crate) target_group_ids: Vec<String>,
    pub(crate) assigned_clients: Vec<String>,
    pub(crate) target_client_passwords: Vec<String>,
}

pub(crate) async fn routing_rules_get(State(state): State<WebState>) -> Response {
    let db = state.app.db.read().await;
    let mut list = Vec::new();
    for rule in db.routing_rules.values() {
        let mut assigned_groups = Vec::new();
        for group in db.groups.values() {
            if group.routing_rule_id == rule.id {
                assigned_groups.push(group.id.clone());
            }
        }
        let mut assigned_clients = Vec::new();
        for (pass, entry) in &db.passwords {
            if entry.routing_rule_id == rule.id {
                assigned_clients.push(pass.clone());
            }
        }
        list.push(RoutingRuleInfo {
            id: rule.id.clone(),
            name: rule.name.clone(),
            is_whitelist: rule.is_whitelist,
            mode: if rule.is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            packages: rule.packages.clone(),
            template_domains: rule.template_domains,
            template_ips: rule.template_ips,
            preset_apps: rule.preset_apps,
            direct_domains: rule.direct_domains.clone(),
            domains: rule.direct_domains.clone(),
            domains_is_whitelist: rule.domains_is_whitelist,
            domains_mode: if rule.domains_is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            ip_is_whitelist: rule.ip_is_whitelist,
            ip_mode: if rule.ip_is_whitelist {
                "whitelist".to_string()
            } else {
                "blacklist".to_string()
            },
            ips: rule.ips.clone(),
            assigned_groups: assigned_groups.clone(),
            target_group_ids: assigned_groups,
            assigned_clients: assigned_clients.clone(),
            target_client_passwords: assigned_clients,
        });
    }
    Json(list).into_response()
}

pub(crate) async fn template_domains_get() -> impl IntoResponse {
    (
        [(
            axum::http::header::CONTENT_TYPE,
            "text/plain; charset=utf-8",
        )],
        crate::routing_assets::ASSETS.domains_text.as_str(),
    )
}

pub(crate) async fn template_ips_get() -> impl IntoResponse {
    (
        [(
            axum::http::header::CONTENT_TYPE,
            "text/plain; charset=utf-8",
        )],
        crate::routing_assets::ASSETS.ips.text.as_str(),
    )
}

pub(crate) async fn preset_apps_get() -> Json<&'static crate::preset_apps::PresetApps> {
    Json(crate::preset_apps::preset_apps())
}

#[derive(Deserialize)]
pub(crate) struct CreateRoutingRuleRequest {
    #[serde(default)]
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) is_whitelist: bool,
    #[serde(default)]
    pub(crate) mode: Option<String>,
    #[serde(default)]
    pub(crate) packages: Vec<String>,
    #[serde(default)]
    pub(crate) template_domains: bool,
    #[serde(default)]
    pub(crate) template_ips: bool,
    #[serde(default = "default_preset_enabled")]
    pub(crate) preset_apps: bool,
    #[serde(default)]
    pub(crate) direct_domains: Vec<String>,
    #[serde(default)]
    pub(crate) domains: Option<Vec<String>>,
    #[serde(default)]
    pub(crate) domains_mode: Option<String>,
    #[serde(default)]
    pub(crate) domains_is_whitelist: bool,
    #[serde(default)]
    pub(crate) ip_mode: Option<String>,
    #[serde(default)]
    pub(crate) ip_is_whitelist: bool,
    #[serde(default)]
    pub(crate) ips: Vec<String>,
    #[serde(default)]
    pub(crate) assigned_groups: Vec<String>,
    #[serde(default)]
    pub(crate) target_group_ids: Option<Vec<String>>,
    #[serde(default)]
    pub(crate) assigned_clients: Vec<String>,
    #[serde(default)]
    pub(crate) target_client_passwords: Option<Vec<String>>,
}

pub(crate) fn default_preset_enabled() -> bool {
    true
}

pub(crate) fn routing_rule_validation_response(rule: &RoutingRule) -> Option<Response> {
    crate::protocol::validate_routing_rule(rule)
        .err()
        .map(|message| (StatusCode::BAD_REQUEST, message).into_response())
}

/// Участники, чьё разрешённое правило приходит из группы: `group_id` совпадает,
/// собственного правила нет, пароль непустой. Единственное место, где живёт
/// условие «собственное правило важнее группового».
pub(crate) fn group_member_passwords(
    db: &model::Database,
    group_id: &str,
) -> std::collections::BTreeSet<String> {
    db.passwords
        .iter()
        .filter(|(password, entry)| {
            entry.group_id == group_id && entry.routing_rule_id.is_empty() && !password.is_empty()
        })
        .map(|(password, _)| password.clone())
        .collect()
}

/// Кого касается привязка правила к конкретной ГРУППЕ.
///
/// Отличается от `rule_affected_passwords` областью: там — все, кто резолвится
/// на это правило (прямые привязки и другие группы с тем же правилом), здесь —
/// только эта группа. Пересохранение группы не должно вызывать переоткачку
/// правила у соседей, которые используют то же правило.
///
/// Участники попадают в набор независимо от того, осталось в группе правило или
/// снято: их разрешённое правило определяется членством, а не значением
/// `routing_rule_id`. Поэтому «правило забрали» не теряет участников из union'а
/// даже если считать набор уже после мутации.
pub(crate) fn group_affected_passwords(db: &model::Database, group_id: &str) -> Vec<String> {
    // Владелец — обычная запись `passwords`, поэтому попадает в набор только
    // через членство в группе, как и любой другой участник.
    group_member_passwords(db, group_id).into_iter().collect()
}

/// Объединение наборов «до» и «после»: без дублей, детерминированный порядок.
pub(crate) fn merge_affected_passwords(before: &[String], after: &[String]) -> Vec<String> {
    before
        .iter()
        .chain(after.iter())
        .cloned()
        .collect::<std::collections::BTreeSet<_>>()
        .into_iter()
        .collect()
}

/// Кому может быть заметно это правило: повторяет порядок разрешения правила из
/// `model::Database::resolve_routing_rule`, а не угадывает его.
///
/// В набор попадают:
/// - клиенты, у которых `routing_rule_id` равен `rule_id` (собственное правило
///   имеет приоритет над групповым, поэтому такого клиента изменение видно
///   всегда);
/// - члены групп с этим правилом, но только те, у кого собственного правила
///   нет — иначе группное правило их и так не касается.
///
/// Возвращает непустые пароли без дублей и в детерминированном порядке.
/// Пустой `rule_id` — пустой набор: отвязка «на ничего» никого не трогает, а
/// вызывающий и без того передаёт набор, в котором правила уже нет.
pub(crate) fn rule_affected_passwords(db: &model::Database, rule_id: &str) -> Vec<String> {
    if rule_id.is_empty() {
        return Vec::new();
    }
    let mut affected = std::collections::BTreeSet::new();
    for group in db.groups.values() {
        if group.routing_rule_id == rule_id {
            affected.extend(group_member_passwords(db, &group.id));
        }
    }
    for (password, entry) in &db.passwords {
        if entry.routing_rule_id == rule_id && !password.is_empty() {
            affected.insert(password.clone());
        }
    }
    affected.into_iter().collect()
}

/// Кого касается массовый перевод клиентов в группу (`api_clients_batch_group`).
///
/// Зеркало условия из `client_update`: разрешённое правило меняется только у
/// перемещаемого клиента БЕЗ собственного правила и только если группа реально
/// другая. Клиент с собственным правилом от групповой привязки не зависит, а
/// перевод «в ту же группу» ничего не меняет. Набор строится ДО мутации, поэтому
/// `entry.group_id` здесь — прежняя группа. Пустые пароли и отсутствующие в базе
/// пропускаются, порядок детерминированный, без дублей.
pub(crate) fn batch_group_affected_passwords(
    db: &model::Database,
    passwords: &[String],
    target_group_id: &str,
) -> Vec<String> {
    let mut affected = std::collections::BTreeSet::new();
    for pwd in passwords {
        if let Some(entry) = db.passwords.get(pwd)
            && entry.routing_rule_id.is_empty()
            && entry.group_id.as_str() != target_group_id
            && !pwd.is_empty()
        {
            affected.insert(pwd.clone());
        }
    }
    affected.into_iter().collect()
}

/// Оповестить живые сессии о смене правила и не превращать неудачу в ошибку
/// ответа: изменение уже сохранено, а 5xx поверх успешного сохранения
/// подтолкнул бы оператора сохранять правило повторно.
///
/// Пустой набор — молча: уведомлять некого (клиенты выключены, правило
/// никому не назначено).
pub(crate) async fn notify_routing_changed_or_log(state: &WebState, passwords: &[String]) {
    if passwords.is_empty() {
        return;
    }
    if let Err(error) = protocol::notify_routing_changed(&state.app, passwords).await {
        log_event(
            &state.app,
            "WARN",
            "routing",
            &format!(
                "routing_changed_notice undelivered clients={} error={error:#}",
                passwords.len()
            ),
        );
    }
}

pub(crate) async fn routing_rules_create(
    State(state): State<WebState>,
    Json(request): Json<CreateRoutingRuleRequest>,
) -> Response {
    let name_str = request.name.trim();
    let name = if name_str.is_empty() {
        "Правило".to_string()
    } else {
        name_str.chars().take(64).collect::<String>()
    };
    let is_whitelist = if let Some(ref m) = request.mode {
        m == "whitelist"
    } else {
        request.is_whitelist
    };
    let direct_domains = if !request.direct_domains.is_empty() {
        request.direct_domains
    } else {
        request.domains.unwrap_or_default()
    };
    let domains_is_whitelist = if let Some(ref m) = request.domains_mode {
        m == "whitelist"
    } else {
        request.domains_is_whitelist
    };
    let ip_is_whitelist = if let Some(ref m) = request.ip_mode {
        m == "whitelist"
    } else {
        request.ip_is_whitelist
    };
    let assigned_groups = if !request.assigned_groups.is_empty() {
        request.assigned_groups
    } else {
        request.target_group_ids.unwrap_or_default()
    };
    let assigned_clients = if !request.assigned_clients.is_empty() {
        request.assigned_clients
    } else {
        request.target_client_passwords.unwrap_or_default()
    };
    let id = format!("rule_{}", random_token(8));
    let rule = RoutingRule {
        id: id.clone(),
        name,
        is_whitelist,
        packages: request.packages,
        template_domains: request.template_domains,
        template_ips: request.template_ips,
        preset_apps: request.preset_apps,
        direct_domains,
        domains_is_whitelist,
        ip_is_whitelist,
        ips: request.ips,
    };
    if let Some(response) = routing_rule_validation_response(&rule) {
        return response;
    }
    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        for g_id in &assigned_groups {
            if let Some(g) = db.groups.get_mut(g_id) {
                g.routing_rule_id = id.clone();
            }
        }
        for c_pass in &assigned_clients {
            if let Some(p) = db.passwords.get_mut(c_pass) {
                p.routing_rule_id = id.clone();
            }
        }
        // Набор считаем ПОСЛЕ привязки: у нового правила ещё нет старых
        // владельцев, поэтому «кого задеть» = «кому оно теперь назначено».
        let affected = rule_affected_passwords(&db, &id);
        db.routing_rules.insert(id, rule.clone());
        (state.app.db_persistence.submit(db.clone()), affected)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    Json(rule).into_response()
}

pub(crate) async fn routing_rules_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<CreateRoutingRuleRequest>,
) -> Response {
    let name_str = request.name.trim();
    let name = if name_str.is_empty() {
        "Правило".to_string()
    } else {
        name_str.chars().take(64).collect::<String>()
    };
    let is_whitelist = if let Some(ref m) = request.mode {
        m == "whitelist"
    } else {
        request.is_whitelist
    };
    let direct_domains = if !request.direct_domains.is_empty() {
        request.direct_domains
    } else {
        request.domains.unwrap_or_default()
    };
    let domains_is_whitelist = if let Some(ref m) = request.domains_mode {
        m == "whitelist"
    } else {
        request.domains_is_whitelist
    };
    let ip_is_whitelist = if let Some(ref m) = request.ip_mode {
        m == "whitelist"
    } else {
        request.ip_is_whitelist
    };
    let assigned_groups = if !request.assigned_groups.is_empty() {
        request.assigned_groups
    } else {
        request.target_group_ids.unwrap_or_default()
    };
    let assigned_clients = if !request.assigned_clients.is_empty() {
        request.assigned_clients
    } else {
        request.target_client_passwords.unwrap_or_default()
    };
    let mut candidate = {
        let db = state.app.db.read().await;
        let Some(rule) = db.routing_rules.get(&id) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        rule.clone()
    };
    candidate.name = name;
    candidate.is_whitelist = is_whitelist;
    candidate.packages = request.packages;
    candidate.template_domains = request.template_domains;
    candidate.template_ips = request.template_ips;
    candidate.preset_apps = request.preset_apps;
    candidate.direct_domains = direct_domains;
    candidate.domains_is_whitelist = domains_is_whitelist;
    candidate.ip_is_whitelist = ip_is_whitelist;
    candidate.ips = request.ips;
    if let Some(response) = routing_rule_validation_response(&candidate) {
        return response;
    }
    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        // Отвязанные при пересохранении ничем не отличаются от тех, кому
        // правило просто изменили содержимое: оба случая требуют нового
        // решения, поэтому берём объединение «до» и «после».
        let before = rule_affected_passwords(&db, &id);
        db.routing_rules.insert(id.clone(), candidate);

        for group in db.groups.values_mut() {
            if assigned_groups.contains(&group.id) {
                group.routing_rule_id = id.clone();
            } else if group.routing_rule_id == id {
                group.routing_rule_id.clear();
            }
        }
        for (pass, entry) in db.passwords.iter_mut() {
            if assigned_clients.contains(pass) {
                entry.routing_rule_id = id.clone();
            } else if entry.routing_rule_id == id {
                entry.routing_rule_id.clear();
            }
        }
        let affected = merge_affected_passwords(&before, &rule_affected_passwords(&db, &id));
        (state.app.db_persistence.submit(db.clone()), affected)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    StatusCode::OK.into_response()
}

pub(crate) async fn routing_rules_delete(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        if db.routing_rules.remove(&id).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        }
        // Набор снимаем сразу после удаления правила, но до снятия привязок:
        // ниже эти поля обнуляются, и по «после» было бы не понять, кого
        // касалось удалённое правило.
        let affected = rule_affected_passwords(&db, &id);
        for group in db.groups.values_mut() {
            if group.routing_rule_id == id {
                group.routing_rule_id.clear();
            }
        }
        for entry in db.passwords.values_mut() {
            if entry.routing_rule_id == id {
                entry.routing_rule_id.clear();
            }
        }
        (state.app.db_persistence.submit(db.clone()), affected)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    StatusCode::OK.into_response()
}
