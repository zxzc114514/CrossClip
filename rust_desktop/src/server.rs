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
#[allow(dead_code)]
struct AuthRequest {
    auth_cipher: Option<String>,
    pin: Option<String>,
    pin_hash: Option<String>,
    client_port: Option<u16>,
    device_id: Option<String>,
    device_name: Option<String>,
    timestamp: Option<u64>,
}

#[derive(Deserialize)]
struct AuthChallengePayload {
    timestamp: u64,
    device_id: Option<String>,
    device_name: Option<String>,
    client_port: Option<u16>,
}

#[derive(Deserialize)]
struct HeartbeatRequest {
    pin_hash: Option<String>,
    client_port: Option<u16>,
    device_id: Option<String>,
    device_name: Option<String>,
}

#[derive(Deserialize)]
#[allow(dead_code)]
struct SyncRequest {
    encrypted: String,
    hash: String,
    sender_id: Option<String>,
    timestamp: Option<u64>,
}

fn extract_pin_from_query(url: &str) -> Option<String> {
    let query = url.split_once('?')?.1;
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        if parts.next() == Some("pin") {
            return parts.next().map(|v| v.to_string());
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
            std::thread::sleep(Duration::from_secs(30));
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

            // 2. 异步向局域网已注册的对等节点做 HTTP POST 兜底通知，不阻塞主流程
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

    if let Ok(mut stream) = TcpStream::connect_timeout(&socket_addr, Duration::from_millis(2000)) {
        let _ = stream.set_write_timeout(Some(Duration::from_millis(2000)));
        let _ = stream.set_read_timeout(Some(Duration::from_millis(2000)));
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

pub fn start_http_server(
    port: u16,
    pin_code: Arc<RwLock<String>>,
    device_name: String,
    broadcaster: Broadcaster,
    on_text_received: Arc<dyn Fn(String, String) + Send + Sync + 'static>,
) {
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
                // SSE 长连接会持久阻塞在读取循环，使用独立线程处理，绝不耗尽普通 API 线程池
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
        &b"Content-Type, Accept, Authorization"[..],
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
            if let Ok(req) = serde_json::from_str::<HeartbeatRequest>(&body) {
                let current_pin = pin_code.read().unwrap().clone();
                let my_hash = crate::crypto::compute_hash(&current_pin);
                let my_hash_prefix = &my_hash[..16.min(my_hash.len())];
                if req.pin_hash.as_deref() == Some(my_hash_prefix) {
                    let port = req.client_port.unwrap_or(18237);
                    let dev_id = req.device_id.unwrap_or_default();
                    let dev_name = req.device_name.unwrap_or_default();
                    broadcaster.register_peer(client_ip, port, dev_id, dev_name);
                    let resp = Response::from_string(r#"{"status":"ok"}"#)
                        .with_header(cors_header)
                        .with_header(content_type);
                    let _ = request.respond(resp);
                    return;
                }
            }
        }
        let resp = Response::from_string(r#"{"status":"error"}"#)
            .with_status_code(StatusCode(403))
            .with_header(cors_header);
        let _ = request.respond(resp);
        return;
    }

    if path == "/events" && method == Method::Get {
        let current_pin = pin_code.read().unwrap().clone();
        let query_pin = extract_pin_from_query(&url).unwrap_or_default();
        if query_pin.trim() != current_pin.trim() {
            let resp_data = serde_json::to_string(&StatusResponse {
                status: "error".to_string(),
                auth: Some(false),
                device_name: None,
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

        broadcaster.register_peer(client_ip, 18237, "android_phone".to_string(), "安卓手机".to_string());

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
            let mut is_valid = false;
            let current_pin = pin_code.read().unwrap().clone();

            let mut client_port = 18237;
            let mut dev_id = "android_phone".to_string();
            let mut dev_name = "安卓手机".to_string();

            if let Ok(req) = auth_req {
                let now_ms = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64;

                if let Some(cipher) = req.auth_cipher {
                    if let Ok(decrypted) = crate::crypto::decrypt(&cipher, &current_pin) {
                        if let Ok(payload) = serde_json::from_str::<AuthChallengePayload>(&decrypted) {
                            let time_diff = now_ms.abs_diff(payload.timestamp);
                            if time_diff < 60_000 {
                                is_valid = true;
                                if let Some(p) = payload.client_port {
                                    client_port = p;
                                }
                                if let Some(id) = payload.device_id {
                                    dev_id = id;
                                }
                                if let Some(name) = payload.device_name {
                                    dev_name = name;
                                }
                            }
                        }
                    }
                }

                if !is_valid {
                    let my_hash = crate::crypto::compute_hash(&current_pin);
                    let my_hash_prefix = &my_hash[..16.min(my_hash.len())];

                    if let Some(pin) = req.pin {
                        if pin.trim() == current_pin.trim() {
                            is_valid = true;
                        }
                    }
                    if let Some(hash) = req.pin_hash {
                        if hash == my_hash_prefix {
                            is_valid = true;
                        }
                    }
                    if let Some(p) = req.client_port {
                        client_port = p;
                    }
                    if let Some(id) = req.device_id {
                        dev_id = id;
                    }
                    if let Some(name) = req.device_name {
                        dev_name = name;
                    }
                }
            }

            if is_valid {
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
                        broadcaster.register_peer(client_ip, 18237, "android_phone".to_string(), "安卓手机".to_string());

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
