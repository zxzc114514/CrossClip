use std::net::IpAddr;

pub fn get_local_lan_ips() -> Vec<String> {
    let mut ips = Vec::new();
    if let Ok(interfaces) = if_addrs::get_if_addrs() {
        for iface in interfaces {
            if iface.is_loopback() {
                continue;
            }
            let name_lower = iface.name.to_lowercase();
            // 排除常见虚拟网卡 (Radmin VPN, Tailscale, ZeroTier, VMware, VirtualBox, Hyper-V, WSL)
            if name_lower.contains("radmin")
                || name_lower.contains("vpn")
                || name_lower.contains("tap")
                || name_lower.contains("tun")
                || name_lower.contains("virtual")
                || name_lower.contains("vEthernet")
                || name_lower.contains("tailscale")
                || name_lower.contains("zerotier")
            {
                continue;
            }

            if let IpAddr::V4(ipv4) = iface.ip() {
                let octets = ipv4.octets();
                // 排除 loopback, 链路本地与 VPN 常见段 (26.x.x.x 是 Radmin VPN 专用)
                if octets[0] == 127 || (octets[0] == 169 && octets[1] == 254) || octets[0] == 26 {
                    continue;
                }
                // 仅保留标准私有局域网网段 (10.x.x.x, 172.16-31.x.x, 192.168.x.x)
                let is_private = octets[0] == 10
                    || (octets[0] == 172 && (16..=31).contains(&octets[1]))
                    || (octets[0] == 192 && octets[1] == 168);

                if is_private {
                    let ip_str = ipv4.to_string();
                    if !ips.contains(&ip_str) {
                        ips.push(ip_str);
                    }
                }
            }
        }
    }

    if ips.is_empty() {
        // 如果严格过滤后为空，尝试通过 UDP 连接获取路由出口物理 IP
        if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
            if socket.connect("8.8.8.8:80").is_ok() {
                if let Ok(local_addr) = socket.local_addr() {
                    let ip_str = local_addr.ip().to_string();
                    if !ip_str.starts_with("127.") && !ip_str.starts_with("26.") {
                        ips.push(ip_str);
                    }
                }
            }
        }
    }

    ips
}
