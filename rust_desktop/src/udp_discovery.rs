use serde::{Deserialize, Serialize};
use std::net::UdpSocket;
use std::sync::{Arc, RwLock};

#[derive(Serialize, Deserialize)]
struct DiscoveryResponse {
    #[serde(rename = "type")]
    msg_type: String,
    device_name: String,
    device_id: String,
    http_port: u16,
    ws_port: u16,
    ips: Vec<String>,
    pin_hash: String,
}

pub fn start_udp_discovery_responder(
    device_name: String,
    device_id: String,
    http_port: u16,
    pin_code: Arc<RwLock<String>>,
) {
    let dev_name = device_name.clone();
    let dev_id = device_id.clone();
    let pin_clone = pin_code.clone();

    // 1. 开机后前 12 秒内，每隔 3 秒向所有物理网卡广播地址主动发送上线宣告，唤醒局域网对端
    std::thread::spawn(move || {
        if let Ok(sender_socket) = UdpSocket::bind("0.0.0.0:0") {
            let _ = sender_socket.set_broadcast(true);
            for _ in 0..4 {
                let current_pin = pin_clone.read().unwrap().clone();
                let pin_hash = crate::crypto::compute_hash(&current_pin);
                let pin_hash_prefix = pin_hash[..16.min(pin_hash.len())].to_string();
                let lan_ips = crate::ip_util::get_local_lan_ips();

                let resp = DiscoveryResponse {
                    msg_type: "OFFER".to_string(),
                    device_name: dev_name.clone(),
                    device_id: dev_id.clone(),
                    http_port,
                    ws_port: 18238,
                    ips: lan_ips,
                    pin_hash: pin_hash_prefix,
                };

                if let Ok(resp_json) = serde_json::to_string(&resp) {
                    let broadcast_addrs = crate::ip_util::get_broadcast_addresses();
                    for bcast_ip in broadcast_addrs {
                        let target = format!("{}:18234", bcast_ip);
                        let _ = sender_socket.send_to(resp_json.as_bytes(), &target);
                    }
                }
                std::thread::sleep(std::time::Duration::from_secs(3));
            }
        }
    });

    // 2. 正常监听 18234 端口被动响应
    std::thread::spawn(move || {
        let socket = match UdpSocket::bind("0.0.0.0:18234") {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[UDP] 无法绑定发现端口 18234: {}", e);
                return;
            }
        };

        let _ = socket.set_broadcast(true);
        let mut buf = [0u8; 1024];

        loop {
            if let Ok((len, src_addr)) = socket.recv_from(&mut buf) {
                if len > 0 {
                    let current_pin = pin_code.read().unwrap().clone();
                    let pin_hash = crate::crypto::compute_hash(&current_pin);
                    let pin_hash_prefix = pin_hash[..16.min(pin_hash.len())].to_string();
                    let lan_ips = crate::ip_util::get_local_lan_ips();

                    let resp = DiscoveryResponse {
                        msg_type: "OFFER".to_string(),
                        device_name: device_name.clone(),
                        device_id: device_id.clone(),
                        http_port,
                        ws_port: 18238,
                        ips: lan_ips,
                        pin_hash: pin_hash_prefix,
                    };

                    if let Ok(resp_json) = serde_json::to_string(&resp) {
                        let _ = socket.send_to(resp_json.as_bytes(), src_addr);
                    }
                }
            }
        }
    });
}
