use anyhow::{anyhow, Result};
use futures_util::stream::SplitSink;
use futures_util::SinkExt;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{oneshot, Mutex};
use tracing::{debug, info, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcRequest {
    pub id: String,
    pub method: String,
    pub params: serde_json::Value,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcResponse {
    pub id: String,
    pub ok: bool,
    #[serde(default)]
    pub result: Option<serde_json::Value>,
    #[serde(default)]
    pub error: Option<RpcError>,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcError {
    pub code: String,
    pub message: String,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RpcEvent {
    pub event: String,
    pub data: serde_json::Value,
}

pub type WsWriter = SplitSink<axum::extract::ws::WebSocket, axum::extract::ws::Message>;

pub struct BridgeManager {
    writer: Arc<Mutex<Option<(u64, WsWriter)>>>,
    conn_counter: std::sync::atomic::AtomicU64,
    pending: Arc<Mutex<HashMap<String, oneshot::Sender<Result<serde_json::Value, String>>>>>,
    pub unread_chat: Arc<Mutex<VecDeque<serde_json::Value>>>,
    pub last_status: Arc<Mutex<Option<serde_json::Value>>>,
    pub client_info: Arc<Mutex<Option<serde_json::Value>>>,
    pub session_token: Arc<Mutex<Option<String>>>,
    req_counter: std::sync::atomic::AtomicU64,
}

impl BridgeManager {
    pub fn new() -> Self {
        Self {
            writer: Arc::new(Mutex::new(None)),
            conn_counter: std::sync::atomic::AtomicU64::new(0),
            pending: Arc::new(Mutex::new(HashMap::new())),
            unread_chat: Arc::new(Mutex::new(VecDeque::new())),
            last_status: Arc::new(Mutex::new(None)),
            client_info: Arc::new(Mutex::new(None)),
            session_token: Arc::new(Mutex::new(None)),
            req_counter: std::sync::atomic::AtomicU64::new(1),
        }
    }

    pub async fn is_connected(&self) -> bool {
        self.writer.lock().await.is_some()
    }

    /// Registers a new game-bridge connection and returns its session id.
    /// Every connection owns an id so a stale socket's cleanup cannot tear down
    /// a newer, live connection (double-connect race).
    pub async fn set_writer(&self, w: WsWriter, token: Option<String>) -> u64 {
        let conn_id = self
            .conn_counter
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
            + 1;
        let mut lk = self.writer.lock().await;
        *lk = Some((conn_id, w));
        drop(lk);
        if let Some(tok) = token {
            let mut tok_lk = self.session_token.lock().await;
            *tok_lk = Some(tok);
        }
        info!("[Bridge] Session #{} attached as game bridge writer", conn_id);
        conn_id
    }

    /// Drops the writer only if it still belongs to `conn_id`.
    pub async fn disconnect(&self, conn_id: u64) {
        {
            let mut lk = self.writer.lock().await;
            match lk.as_ref() {
                Some((id, _)) if *id == conn_id => {
                    *lk = None;
                }
                Some((id, _)) => {
                    info!(
                        "[Bridge] Session #{} ended; keeping newer session #{} as writer",
                        conn_id, id
                    );
                    return;
                }
                None => return,
            }
        }
        let mut p = self.pending.lock().await;
        for (_, tx) in p.drain() {
            let _ = tx.send(Err("Bridge disconnected".to_string()));
        }
        info!("[Bridge] Client disconnected from reverse WebSocket bridge");
    }

    pub async fn call(
        &self,
        method: &str,
        params: serde_json::Value,
        timeout_dur: Duration,
    ) -> Result<serde_json::Value> {
        let id_num = self
            .req_counter
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let id = format!("req_{}", id_num);

        let (tx, rx) = oneshot::channel();
        {
            let mut p = self.pending.lock().await;
            p.insert(id.clone(), tx);
        }

        let req = RpcRequest {
            id: id.clone(),
            method: method.to_string(),
            params,
        };
        let msg_str = serde_json::to_string(&req)?;

        {
            let mut w_lk = self.writer.lock().await;
            match w_lk.as_mut() {
                Some((_, writer)) => {
                    if let Err(e) = writer
                        .send(axum::extract::ws::Message::Text(msg_str.into()))
                        .await
                    {
                        let mut p = self.pending.lock().await;
                        p.remove(&id);
                        return Err(anyhow!("Failed to send RPC message over WebSocket: {}", e));
                    }
                }
                None => {
                    let mut p = self.pending.lock().await;
                    p.remove(&id);
                    return Err(anyhow!("Minecraft client is not connected to launcher bridge"));
                }
            }
        }

        match tokio::time::timeout(timeout_dur, rx).await {
            Ok(Ok(Ok(val))) => Ok(val),
            Ok(Ok(Err(err_msg))) => Err(anyhow!("RPC error: {}", err_msg)),
            Ok(Err(_)) => Err(anyhow!("RPC channel canceled")),
            Err(_) => {
                let mut p = self.pending.lock().await;
                p.remove(&id);
                Err(anyhow!("RPC call '{}' timed out after {:?}", method, timeout_dur))
            }
        }
    }

    pub async fn handle_incoming_text(&self, text: &str) {
        debug!("[Bridge RECV] {}", text);
        if let Ok(val) = serde_json::from_str::<serde_json::Value>(text) {
            // Check if handshake
            if val.get("op").and_then(|v| v.as_str()) == Some("handshake") {
                let mut info_lk = self.client_info.lock().await;
                *info_lk = Some(val.clone());
                info!("[Bridge] Handshake received from Minecraft client: {:?}", val);
                return;
            }

            // Check if RPC response (contains id)
            if let Some(id_val) = val.get("id").and_then(|v| v.as_str()) {
                let is_ok = val.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
                let mut p = self.pending.lock().await;
                if let Some(tx) = p.remove(id_val) {
                    if is_ok {
                        let res = val
                            .get("result")
                            .cloned()
                            .unwrap_or(serde_json::Value::Null);
                        let _ = tx.send(Ok(res));
                    } else {
                        let err_msg = val
                            .get("error")
                            .and_then(|e| e.get("message"))
                            .and_then(|m| m.as_str())
                            .unwrap_or("Unknown game error")
                            .to_string();
                        let _ = tx.send(Err(err_msg));
                    }
                }
                return;
            }

            // Check if Event
            if let Some(ev) = val.get("event").and_then(|v| v.as_str()) {
                match ev {
                    "chat" => {
                        if let Some(data) = val.get("data") {
                            let mut chat_lk = self.unread_chat.lock().await;
                            chat_lk.push_back(data.clone());
                            if chat_lk.len() > 100 {
                                chat_lk.pop_front();
                            }
                        }
                    }
                    "status" | "hud" | "tick" => {
                        if let Some(data) = val.get("data") {
                            let mut st_lk = self.last_status.lock().await;
                            *st_lk = Some(data.clone());
                        }
                    }
                    other => {
                        debug!("[Bridge Event] Received event '{}'", other);
                    }
                }
            }
        } else {
            warn!("[Bridge] Failed to parse incoming JSON: {}", text);
        }
    }
}
