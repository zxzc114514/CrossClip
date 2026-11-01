use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

struct WorkerPool {
    sender: Sender<Box<dyn FnOnce() + Send + 'static>>,
}

impl WorkerPool {
    fn new(size: usize) -> Self {
        let (tx, rx) = channel::<Box<dyn FnOnce() + Send + 'static>>();
        let rx = Arc::new(Mutex::new(rx));
        for _ in 0..size {
            let rx = rx.clone();
            std::thread::spawn(move || {
                while let Ok(job) = {
                    let lock = rx.lock().unwrap();
                    lock.recv()
                } {
                    job();
                }
            });
        }
        Self { sender: tx }
    }

    fn execute<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        let _ = self.sender.send(Box::new(f));
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ClientPeer {
    pub ip: String,
    pub port: u16,
    pub device_id: String,
    pub device_name: String,
    pub last_seen: u64,
}

#[derive(Deserialize)]
struct AuthRequest {
    auth_cipher: Option<String>,
}

#[derive(Deserialize)]
struct AuthChallengePayload {
    timestamp: u64,
    device_id: Option<String>,
    device_name: Option<String>,
    client_port: Option<u16>,
}

#[derive(Deserialize)]
struct SyncRequest {
    encrypted: String,
    hash: String,
    sender_id: Option<String>,
}

/// 统一验证 AES-256-GCM 密文挑战令牌（带 5 分钟防重放时间窗校验）
fn verify_auth_cipher(cipher_b64: &str, current_pin: &str) -> Option<AuthChallengePayload> {
    if cipher_b64.trim().is_empty() || current_pin.trim().is_empty() {
        return None;
    }
    let decrypted = crate::crypto::decrypt(cipher_b64.trim(), current_pin.trim()).ok()?;
    let payload: AuthChallengePayload = serde_json::from_str(&decrypted).ok()?;
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64;
    if now_ms.abs_diff(payload.timestamp) <= 300_000 {
        Some(payload)
    } else {
        None
    }
}

fn extract_auth_cipher_from_request(request: &Request) -> Option<String> {
    for header in request.headers() {
        if header.field.equiv("X-Auth-Cipher") {
            let val = header.value.as_str().trim();
            if !val.is_empty() {
                return Some(val.to_string());
            }
        }
    }
    None
}

#[derive(Serialize)]
struct StatusResponse {
    status: String,
    auth: Option<bool>,
    device_name: Option<String>,
    device_id: Option<String>,
    message: Option<String>,
}

#[derive(Clone)]
pub struct Broadcaster {
    clients: Arc<Mutex<Vec<Sender<String>>>>,
    peers: Arc<RwLock<Vec<ClientPeer>>>,
    pin_code: Arc<RwLock<String>>,
    device_id: String,
}

impl Broadcaster {
    pub fn device_id(&self) -> &str {
        &self.device_id
    }

    pub fn has_active_clients(&self) -> bool {
        let mut clients = self.clients.lock().unwrap();
        clients.retain(|client| client.send(": keepalive\n\n".to_string()).is_ok());
        !clients.is_empty()
    }

    pub fn new(pin_code: Arc<RwLock<String>>, device_id: String) -> Self {
        let broadcaster = Self {
            clients: Arc::new(Mutex::new(Vec::new())),
            peers: Arc::new(RwLock::new(Vec::new())),
            pin_code,
            device_id,
        };

        let peers_clone = broadcaster.peers.clone();
        let clients_clone = broadcaster.clients.clone();
        std::thread::spawn(move || loop {
            // 每 15 秒下发一次 SSE 保活帧，配合手机端 25 秒读超时实现快速断线感知
            std::thread::sleep(Duration::from_secs(15));
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();

            {
                let mut peers = peers_clone.write().unwrap();
                peers.retain(|p| now.saturating_sub(p.last_seen) < 600);
            }
            {
                let mut clients = clients_clone.lock().unwrap();
                clients.retain(|client| client.send(": keepalive\n\n".to_string()).is_ok());
            }
        });

        broadcaster
    }

    pub fn register_peer(&self, ip: String, port: u16, device_id: String, device_name: String) {
        if !ip.starts_with("127.") {
            crate::config::update_persisted_phone_ip(&ip);
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let mut peers = self.peers.write().unwrap();
        if let Some(existing) = peers.iter_mut().find(|p| p.ip == ip) {
            existing.last_seen = now;
            existing.port = port;
            if !device_id.is_empty() {
                existing.device_id = device_id;
            }
            if !device_name.is_empty() {
                existing.device_name = device_name;
            }
        } else {
            peers.push(ClientPeer {
                ip,
                port,
                device_id,
                device_name,
                last_seen: now,
            });
        }
    }

    pub fn broadcast_text(&self, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        let current_pin = self.pin_code.read().unwrap().clone();
        if let Ok(encrypted) = crate::crypto::encrypt(text, &current_pin) {
            let hash = crate::crypto::compute_hash(text);
            let now_ms = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;

            let payload = serde_json::json!({
                "type": "CLIP_SYNC",
                "encrypted": encrypted,
                "hash": hash,
                "sender_id": self.device_id,
                "timestamp": now_ms
            })
            .to_string();

            // 1. 毫秒级优先推送到所有活跃的 SSE 长连接（手机端直达通道）
            let msg = format!("data: {}\n\n", payload);
            {
                let mut clients = self.clients.lock().unwrap();
                clients.retain(|client| client.send(msg.clone()).is_ok());
            }

            // 2. 异步向局域网已鉴权的对等节点做 HTTP POST 兜底通知
            let peers: Vec<ClientPeer> = self.peers.read().unwrap().clone();
            if !peers.is_empty() {
                std::thread::spawn(move || {
                    for peer in peers {
                        let ip = peer.ip.clone();
                        let port = peer.port;
                        let payload_clone = payload.clone();
                        let _ = send_raw_http_post(&ip, port, "/sync", &payload_clone);
                    }
                });
            }
        }
    }

    pub fn disconnect_all(&self) {
        let mut clients = self.clients.lock().unwrap();
        clients.clear();
        let mut peers = self.peers.write().unwrap();
        peers.clear();
    }
}

fn send_raw_http_post(ip: &str, port: u16, path: &str, json_body: &str) -> bool {
    let addr_str = format!("{}:{}", ip, port);
    let socket_addr: SocketAddr = match addr_str.parse() {
        Ok(addr) => addr,
        Err(_) => return false,
    };

    if let Ok(mut stream) = TcpStream::connect_timeout(&socket_addr, Duration::from_millis(1200)) {
        let _ = stream.set_write_timeout(Some(Duration::from_millis(1200)));
        let _ = stream.set_read_timeout(Some(Duration::from_millis(1200)));
        let body_bytes = json_body.as_bytes();
        let request = format!(
            "POST {} HTTP/1.1\r\nHost: {}:{}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            path, ip, port, body_bytes.len()
        );
        if stream.write_all(request.as_bytes()).is_ok() && stream.write_all(body_bytes).is_ok() {
            let _ = stream.flush();
            let mut buf = [0u8; 128];
            let _ = stream.read(&mut buf);
            return true;
        }
    }
    false
}

fn start_phone_wake_daemon(http_port: u16, device_id: String, device_name: String, broadcaster: Broadcaster) {
    std::thread::spawn(move || {
        // 开机初始密集唤醒阶段：前 15 秒每 3 秒单播一次，迅速叫醒手机
        for _ in 0..5 {
            send_wakeup_to_saved_phone(http_port, &device_id, &device_name);
            std::thread::sleep(Duration::from_secs(3));
        }

        // 动态自适应阶段：若当前无活跃 SSE 连接（如手机刚离开局域网或正返回局域网），每 8 秒探测唤醒一次；
        // 若手机已稳定连接，则每 45 秒低频探测一次
        loop {
            let sleep_secs = if broadcaster.has_active_clients() { 45 } else { 8 };
            std::thread::sleep(Duration::from_secs(sleep_secs));
            if !broadcaster.has_active_clients() {
                send_wakeup_to_saved_phone(http_port, &device_id, &device_name);
            }
        }
    });
}

fn send_wakeup_to_saved_phone(http_port: u16, device_id: &str, device_name: &str) {
    let cfg = crate::config::load_or_init_config();
    if let Some(ref phone_ip) = cfg.last_phone_ip {
        if !phone_ip.is_empty() && !phone_ip.starts_with("127.") {
            let lan_ips = crate::ip_util::get_local_lan_ips();
            let main_ip = lan_ips.first().cloned().unwrap_or_else(|| "127.0.0.1".to_string());
            let payload = serde_json::json!({
                "type": "WAKEUP",
                "pc_ip": main_ip,
                "pc_port": http_port,
                "device_id": device_id,
                "device_name": device_name
            })
            .to_string();

            let _ = send_raw_http_post(phone_ip, 18237, "/wakeup", &payload);
        }
    }
}

pub fn start_http_server(
    port: u16,
    pin_code: Arc<RwLock<String>>,
    device_name: String,
    broadcaster: Broadcaster,
    on_text_received: Arc<dyn Fn(String, String) + Send + Sync + 'static>,
) {
    start_phone_wake_daemon(
        port,
        broadcaster.device_id().to_string(),
        device_name.clone(),
        broadcaster.clone(),
    );

    std::thread::spawn(move || {
        let addr = format!("0.0.0.0:{}", port);
        let server = match Server::http(&addr) {
            Ok(s) => Arc::new(s),
            Err(e) => {
                eprintln!("[HTTP] 无法绑定端口 {}: {}", port, e);
                return;
            }
        };

        let pool = Arc::new(WorkerPool::new(16));

        for request in server.incoming_requests() {
            let pin_code_clone = pin_code.clone();
            let device_name_clone = device_name.clone();
            let broadcaster_clone = broadcaster.clone();
            let on_received_clone = on_text_received.clone();

            if request.url().starts_with("/events") {
                std::thread::spawn(move || {
                    handle_client_request(
                        request,
                        pin_code_clone,
                        device_name_clone,
                        broadcaster_clone,
                        on_received_clone,
                    );
                });
            } else {
                pool.execute(move || {
                    handle_client_request(
                        request,
                        pin_code_clone,
                        device_name_clone,
                        broadcaster_clone,
                        on_received_clone,
                    );
                });
            }
        }
    });
}

fn handle_client_request(
    mut request: Request,
    pin_code: Arc<RwLock<String>>,
    device_name: String,
    broadcaster: Broadcaster,
    on_text_received: Arc<dyn Fn(String, String) + Send + Sync + 'static>,
) {
    let cors_header = Header::from_bytes(&b"Access-Control-Allow-Origin"[..], &b"*"[..]).unwrap();
    let cors_headers_allow = Header::from_bytes(
        &b"Access-Control-Allow-Headers"[..],
        &b"Content-Type, Accept, Authorization, X-Auth-Cipher"[..],
    )
    .unwrap();
    let content_type = Header::from_bytes(
        &b"Content-Type"[..],
        &b"application/json; charset=utf-8"[..],
    )
    .unwrap();

    let client_ip = request
        .remote_addr()
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|| "127.0.0.1".to_string());

    let url = request.url().to_string();
    let path = url.split('?').next().unwrap_or("").to_string();
    let method = request.method().clone();

    if method == Method::Options {
        let resp = Response::empty(StatusCode(200))
            .with_header(cors_header)
            .with_header(cors_headers_allow);
        let _ = request.respond(resp);
        return;
    }

    if path == "/ping" && method == Method::Get {
        let resp_data = serde_json::to_string(&StatusResponse {
            status: "ok".to_string(),
            auth: None,
            device_name: Some(device_name),
            device_id: Some(broadcaster.device_id().to_string()),
            message: None,
        })
        .unwrap();

        let resp = Response::from_string(resp_data)
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    if path == "/heartbeat" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(req) = serde_json::from_str::<AuthRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                if let Some(ref cipher) = req.auth_cipher {
                    if let Some(payload) = verify_auth_cipher(cipher, &current_pin) {
                        let port = payload.client_port.unwrap_or(18237);
                        let dev_id = payload.device_id.unwrap_or_default();
                        let dev_name = payload.device_name.unwrap_or_default();
                        broadcaster.register_peer(client_ip, port, dev_id, dev_name);
                        let resp = Response::from_string(r#"{"status":"ok"}"#)
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error","message":"pin_mismatch"}"#)
            .with_status_code(StatusCode(403))
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    if path == "/events" && method == Method::Get {
        let current_pin = pin_code.read().unwrap().clone();
        let auth_cipher = extract_auth_cipher_from_request(&request).unwrap_or_default();
        let verified_payload = verify_auth_cipher(&auth_cipher, &current_pin);

        if verified_payload.is_none() {
            let resp_data = serde_json::to_string(&StatusResponse {
                status: "error".to_string(),
                auth: Some(false),
                device_name: None,
                device_id: None,
                message: Some("pin_mismatch".to_string()),
            })
            .unwrap();
            let resp = Response::from_string(resp_data)
                .with_status_code(StatusCode(403))
                .with_header(cors_header)
                .with_header(content_type);
            let _ = request.respond(resp);
            return;
        }

        let payload = verified_payload.unwrap();
        broadcaster.register_peer(
            client_ip,
            payload.client_port.unwrap_or(18237),
            payload.device_id.unwrap_or_else(|| "android_phone".to_string()),
            payload.device_name.unwrap_or_else(|| "安卓手机".to_string()),
        );

        let (tx, rx) = channel::<String>();
        {
            let mut clients = broadcaster.clients.lock().unwrap();
            clients.push(tx);
        }

        let mut writer = request.into_writer();
        let response_line = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache, no-transform\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
        if writer.write_all(response_line.as_bytes()).is_err() {
            return;
        }
        let _ = writer.flush();

        let _ = writer.write_all(b": connected\n\n");
        let _ = writer.flush();

        while let Ok(msg) = rx.recv() {
            if writer.write_all(msg.as_bytes()).is_err() {
                break;
            }
            if writer.flush().is_err() {
                break;
            }
        }
        return;
    }

    if path == "/auth" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            let auth_req: Result<AuthRequest, _> = serde_json::from_str(&body);
            let current_pin = pin_code.read().unwrap().clone();

            let mut verified: Option<AuthChallengePayload> = None;
            if let Ok(req) = auth_req {
                if let Some(ref cipher) = req.auth_cipher {
                    verified = verify_auth_cipher(cipher, &current_pin);
                }
            }

            if let Some(payload) = verified {
                let client_port = payload.client_port.unwrap_or(18237);
                let dev_id = payload.device_id.unwrap_or_else(|| "android_phone".to_string());
                let dev_name = payload.device_name.unwrap_or_else(|| "安卓手机".to_string());
                broadcaster.register_peer(client_ip, client_port, dev_id, dev_name);

                let resp_data = serde_json::to_string(&StatusResponse {
                    status: "ok".to_string(),
                    auth: Some(true),
                    device_name: Some(device_name),
                    device_id: Some(broadcaster.device_id().to_string()),
                    message: None,
                })
                .unwrap();
                let resp = Response::from_string(resp_data)
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            } else {
                let resp_data = serde_json::to_string(&StatusResponse {
                    status: "error".to_string(),
                    auth: Some(false),
                    device_name: None,
                    device_id: None,
                    message: Some("pin_mismatch".to_string()),
                })
                .unwrap();
                let resp = Response::from_string(resp_data)
                    .with_status_code(StatusCode(403))
                    .with_header(cors_header)
                    .with_header(content_type);
                let _ = request.respond(resp);
            }
        }
        return;
    }

    if path == "/sync" && method == Method::Post {
        let mut body = String::new();
        if request.as_reader().read_to_string(&mut body).is_ok() {
            if let Ok(sync_req) = serde_json::from_str::<SyncRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                if let Ok(decrypted) = crate::crypto::decrypt(&sync_req.encrypted, &current_pin) {
                    if crate::crypto::compute_hash(&decrypted) == sync_req.hash {
                        broadcaster.register_peer(
                            client_ip,
                            18237,
                            "android_phone".to_string(),
                            "安卓手机".to_string(),
                        );

                        crate::clipboard::set_clipboard_text(&decrypted);

                        let sender = sync_req.sender_id.unwrap_or_else(|| "手机端".to_string());
                        on_text_received(decrypted, sender);

                        let resp = Response::from_string(r#"{"status":"ok","message":"synced"}"#)
                            .with_header(cors_header)
                            .with_header(content_type);
                        let _ = request.respond(resp);
                        return;
                    }
                }
            }
        }

        let resp = Response::from_string(r#"{"status":"error","message":"invalid_payload"}"#)
            .with_status_code(StatusCode(400))
            .with_header(cors_header)
            .with_header(content_type);
        let _ = request.respond(resp);
        return;
    }

    let resp = Response::from_string("404 Not Found")
        .with_status_code(StatusCode(404))
        .with_header(cors_header);
    let _ = request.respond(resp);
}
