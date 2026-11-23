use crate::bridge::BridgeManager;
use crate::config::LauncherConfig;
use crate::downloader::Downloader;
use crate::instance::InstanceManager;
use crate::modrinth::ModrinthClient;
use crate::process::ProcessSupervisor;
use anyhow::Result;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Path as AxumPath, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Json, Response};
use axum::routing::{get, post};
use axum::Router;
use futures_util::StreamExt;
use serde::Deserialize;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tracing::{info, warn};

#[derive(Clone)]
pub struct AppState {
    pub config: LauncherConfig,
    pub instance_mgr: Arc<InstanceManager>,
    pub downloader: Arc<Downloader>,
    pub modrinth: Arc<ModrinthClient>,
    pub supervisor: Arc<ProcessSupervisor>,
    pub bridge: Arc<BridgeManager>,
}

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/api/status", get(get_status))
        .route("/api/instances", get(list_instances))
        .route("/api/instance/create", post(create_instance))
        .route("/api/instance/delete", post(delete_instance))
        .route("/api/instance/launch", post(launch_instance))
        .route("/api/instance/stop", post(stop_instance))
        .route("/api/instance/mods", get(list_mods))
        .route("/api/instance/install_mod", post(install_mod))
        .route("/api/instance/remove_mod", post(remove_mod))
        .route("/api/instance/toggle_mod", post(toggle_mod))
        .route("/api/modrinth/search", get(search_modrinth))
        .route("/api/instance/configure", post(configure_instance))
        .route("/api/version/install", post(install_version))
        .route("/api/bot/hud", get(get_bot_hud))
        .route(
            "/api/bot/:method",
            post(proxy_bot_rpc).get(proxy_bot_rpc_get),
        )
        .route("/game_bridge", get(ws_game_bridge))
        .with_state(state)
}

pub async fn run_server(state: AppState, port: u16) -> Result<()> {
    let app = create_router(state);
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    info!("[Server] Starting UnAI Minecraft Launcher Daemon on loopback {}", addr);
    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;
    Ok(())
}

async fn get_status(State(state): State<AppState>) -> impl IntoResponse {
    let proc_status = state.supervisor.get_status().await;
    let bridge_connected = state.bridge.is_connected().await;
    let client_info = state.bridge.client_info.lock().await.clone();

    Json(serde_json::json!({
        "launcher": "online",
        "version": env!("CARGO_PKG_VERSION"),
        "bridge_connected": bridge_connected,
        "process_status": format!("{:?}", proc_status),
        "client_info": client_info,
        "instances_dir": state.config.instances_dir,
    }))
}

async fn list_instances(State(state): State<AppState>) -> impl IntoResponse {
    match state.instance_mgr.list() {
        Ok(list) => (StatusCode::OK, Json(serde_json::json!({ "ok": true, "instances": list }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct CreateInstancePayload {
    name: String,
    version: Option<String>,
    loader: Option<String>,
    memory_mb: Option<u32>,
}

async fn create_instance(
    State(state): State<AppState>,
    Json(payload): Json<CreateInstancePayload>,
) -> impl IntoResponse {
    let ver = payload.version.unwrap_or_else(|| state.config.default_version.clone());
    let ldr = payload.loader.unwrap_or_else(|| state.config.default_loader.clone());

    match state.instance_mgr.create(&payload.name, &ver, &ldr, payload.memory_mb) {
        Ok(cfg) => (StatusCode::OK, Json(serde_json::json!({ "ok": true, "instance": cfg }))),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct InstanceActionPayload {
    name: String,
}

async fn delete_instance(
    State(state): State<AppState>,
    Json(payload): Json<InstanceActionPayload>,
) -> impl IntoResponse {
    match state.instance_mgr.delete(&payload.name) {
        Ok(_) => (StatusCode::OK, Json(serde_json::json!({ "ok": true }))),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct LaunchPayload {
    name: String,
    headless: Option<bool>,
    username: Option<String>,
    auth_type: Option<String>,
    memory_mb: Option<u32>,
}

async fn launch_instance(
    State(state): State<AppState>,
    Json(payload): Json<LaunchPayload>,
) -> impl IntoResponse {
    let inst_cfg = match state.instance_mgr.get(&payload.name) {
        Ok(c) => c,
        Err(e) => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
            );
        }
    };

    let session_token = uuid::Uuid::new_v4().to_string();
    let inst_dir = state.instance_mgr.instance_dir(&payload.name);
    let username = payload.username.unwrap_or_else(|| "DiromGPT".to_string());
    let auth_type = payload.auth_type.unwrap_or_else(|| state.config.default_auth.clone());
    let headless = payload.headless.unwrap_or(false);
    let memory_mb = payload.memory_mb.unwrap_or(inst_cfg.memory_mb);

    let authlib_path = state.config.versions_dir.join("authlib-injector.jar");

    match state
        .supervisor
        .launch(
            &payload.name,
            &inst_dir,
            &inst_cfg.version,
            &inst_cfg.loader,
            memory_mb,
            state.config.port,
            &session_token,
            &username,
            &auth_type,
            headless,
            if authlib_path.exists() { Some(&authlib_path) } else { None },
            &state.config.versions_dir,
            inst_cfg.quick_play_world.as_deref(),
        )
        .await
    {
        Ok(pid) => (
            StatusCode::OK,
            Json(serde_json::json!({
                "ok": true,
                "pid": pid,
                "instance": payload.name,
                "session_token": session_token,
                "headless": headless
            })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

async fn stop_instance(State(state): State<AppState>) -> impl IntoResponse {
    match state.supervisor.stop().await {
        Ok(_) => (StatusCode::OK, Json(serde_json::json!({ "ok": true }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct ListModsQuery {
    name: String,
}

async fn list_mods(
    State(state): State<AppState>,
    axum::extract::Query(query): axum::extract::Query<ListModsQuery>,
) -> impl IntoResponse {
    match state.instance_mgr.list_mods_detailed(&query.name) {
        Ok(mods) => (StatusCode::OK, Json(serde_json::json!({ "ok": true, "mods": mods }))),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct InstallModPayload {
    name: String,
    source: String,
}

async fn install_mod(
    State(state): State<AppState>,
    Json(payload): Json<InstallModPayload>,
) -> impl IntoResponse {
    let inst_cfg = match state.instance_mgr.get(&payload.name) {
        Ok(c) => c,
        Err(e) => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
            );
        }
    };

    let p = PathBuf::from(&payload.source);
    if p.exists() && p.is_file() {
        match state.instance_mgr.install_mod_file(&payload.name, &p) {
            Ok(target) => (
                StatusCode::OK,
                Json(serde_json::json!({ "ok": true, "installed_to": target })),
            ),
            Err(e) => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
            ),
        }
    } else {
        // Modrinth search / direct download
        let mods_dir = state.instance_mgr.mods_dir(&payload.name);
        match state
            .modrinth
            .resolve_and_download(&payload.source, &inst_cfg.loader, &inst_cfg.version, &mods_dir)
            .await
        {
            Ok(target) => (
                StatusCode::OK,
                Json(serde_json::json!({ "ok": true, "installed_to": target })),
            ),
            Err(e) => (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
            ),
        }
    }
}

#[derive(Deserialize)]
struct ModActionPayload {
    name: String,
    filename: String,
}

async fn remove_mod(
    State(state): State<AppState>,
    Json(payload): Json<ModActionPayload>,
) -> impl IntoResponse {
    match state.instance_mgr.remove_mod(&payload.name, &payload.filename) {
        Ok(_) => (StatusCode::OK, Json(serde_json::json!({ "ok": true }))),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct ToggleModPayload {
    name: String,
    filename: String,
    enable: bool,
}

async fn toggle_mod(
    State(state): State<AppState>,
    Json(payload): Json<ToggleModPayload>,
) -> impl IntoResponse {
    match state.instance_mgr.toggle_mod(&payload.name, &payload.filename, payload.enable) {
        Ok(new_name) => (
            StatusCode::OK,
            Json(serde_json::json!({ "ok": true, "filename": new_name })),
        ),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct ModrinthSearchQuery {
    query: String,
    loader: Option<String>,
    version: Option<String>,
}

async fn search_modrinth(
    State(state): State<AppState>,
    axum::extract::Query(query): axum::extract::Query<ModrinthSearchQuery>,
) -> impl IntoResponse {
    let loader = query.loader.unwrap_or_else(|| state.config.default_loader.clone());
    let ver = query.version.unwrap_or_else(|| state.config.default_version.clone());

    match state.modrinth.search(&query.query, &loader, &ver).await {
        Ok(hits) => (StatusCode::OK, Json(serde_json::json!({ "ok": true, "hits": hits }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct ConfigurePayload {
    name: String,
    settings: HashMap<String, String>,
}

async fn configure_instance(
    State(state): State<AppState>,
    Json(payload): Json<ConfigurePayload>,
) -> impl IntoResponse {
    match state.instance_mgr.configure_options(&payload.name, &payload.settings) {
        Ok(_) => (StatusCode::OK, Json(serde_json::json!({ "ok": true }))),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

#[derive(Deserialize)]
struct InstallVersionPayload {
    version: String,
    loader: Option<String>,
}

async fn install_version(
    State(state): State<AppState>,
    Json(payload): Json<InstallVersionPayload>,
) -> impl IntoResponse {
    let ldr = payload.loader.unwrap_or_else(|| "fabric".to_string());
    match state.downloader.install_version(&payload.version, &ldr).await {
        Ok(path) => (
            StatusCode::OK,
            Json(serde_json::json!({ "ok": true, "version_path": path })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

async fn get_bot_hud(State(state): State<AppState>) -> impl IntoResponse {
    let st = state.bridge.last_status.lock().await.clone().unwrap_or(serde_json::json!({}));
    let mut chat_items = Vec::new();
    {
        let mut chat_lk = state.bridge.unread_chat.lock().await;
        while let Some(msg) = chat_lk.pop_front() {
            chat_items.push(msg);
        }
    }

    Json(serde_json::json!({
        "status": st,
        "unread_chat": chat_items,
        "connected": state.bridge.is_connected().await
    }))
}

/// Flattens the RPC payload into the HTTP body. The workspace client both reads
/// method fields directly (`status`, `message`, `sent`) and checks `ok`, so the
/// payload is merged at top level with `ok: true` instead of being nested under
/// a `result` envelope.
fn rpc_http_response(
    res: Result<serde_json::Value, anyhow::Error>,
) -> (StatusCode, Json<serde_json::Value>) {
    match res {
        Ok(val) => {
            let mut body = match val {
                serde_json::Value::Object(map) => map,
                other => {
                    let mut map = serde_json::Map::new();
                    map.insert("result".to_string(), other);
                    map
                }
            };
            body.insert("ok".to_string(), serde_json::Value::Bool(true));
            (StatusCode::OK, Json(serde_json::Value::Object(body)))
        }
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "ok": false, "error": e.to_string() })),
        ),
    }
}

async fn proxy_bot_rpc(
    AxumPath(method): AxumPath<String>,
    State(state): State<AppState>,
    Json(body): Json<serde_json::Value>,
) -> impl IntoResponse {
    rpc_http_response(
        state
            .bridge
            .call(&method, body, Duration::from_secs(15))
            .await,
    )
}

/// GET variant of the bot RPC proxy: the workspace client issues plain GETs for
/// read-only methods, and a POST-only route answered them with 405.
async fn proxy_bot_rpc_get(
    AxumPath(method): AxumPath<String>,
    State(state): State<AppState>,
) -> impl IntoResponse {
    rpc_http_response(
        state
            .bridge
            .call(&method, serde_json::json!({}), Duration::from_secs(15))
            .await,
    )
}

async fn ws_game_bridge(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
) -> Response {
    ws.on_upgrade(move |socket| handle_game_socket(socket, state))
}

async fn handle_game_socket(socket: WebSocket, state: AppState) {
    info!("[Server] Minecraft client connected to WebSocket bridge!");
    let (writer, mut reader) = socket.split();

    let conn_id = state.bridge.set_writer(writer, None).await;

    while let Some(msg_res) = reader.next().await {
        match msg_res {
            Ok(Message::Text(text)) => {
                state.bridge.handle_incoming_text(&text).await;
            }
            Ok(Message::Close(_)) => {
                info!("[Server] WebSocket close frame received");
                break;
            }
            Ok(Message::Ping(_)) => {}
            Ok(Message::Pong(_)) => {}
            Ok(Message::Binary(_)) => {}
            Err(e) => {
                warn!("[Server] WebSocket read error: {}", e);
                break;
            }
        }
    }

    state.bridge.disconnect(conn_id).await;
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn rpc_success_flattens_result_and_adds_ok() {
        let (status, Json(body)) = rpc_http_response(Ok(json!({"sent": true, "message": "hello"})));
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body.get("ok"), Some(&serde_json::Value::Bool(true)));
        assert_eq!(body.get("sent"), Some(&serde_json::Value::Bool(true)));
        assert_eq!(body.get("message").and_then(|v| v.as_str()), Some("hello"));
        assert!(body.get("result").is_none());
    }

    #[test]
    fn rpc_failure_is_not_reported_as_success() {
        let (status, Json(body)) = rpc_http_response(Err(anyhow::anyhow!("client disconnected")));
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body.get("ok"), Some(&serde_json::Value::Bool(false)));
        assert_eq!(body.get("error").and_then(|v| v.as_str()), Some("client disconnected"));
    }
}
