# CrossClip 局域网协议说明

## 端口与服务

| 端口 | 协议 | 用途 |
| :--- | :--- | :--- |
| 18234 | UDP | Windows 端广播自发现(应答设备名、IP、HTTP 端口) |
| 18236 | HTTP/SSE | Windows 端服务:鉴权、密文同步、SSE 剪贴板下发 |
| 18237 | HTTP | Android 端本地服务:接收电脑端对等推送 |
| - | mDNS | 服务类型 `_crossclip._tcp.local.`,TXT 携带 `device_id` |

## 端到端加密

所有剪贴板明文在发送前加密:

```text
key       = SHA-256(pin_code)              # pin_code 为 6 位动态 PIN
nonce     = random(12 bytes)
payload   = base64(nonce || AES-256-GCM(key, nonce, text))
hash      = SHA-256(text)                  # 防回环与完整性校验
```

双端各自维护近期内容哈希环形队列:收到远端内容写入本地剪贴板后,若哈希命中本机近期的发送记录,则判定为回环并过滤,避免两台设备互相触发无限反射同步。

## Windows 端 HTTP 端点(端口 18236)

| 路径 | 方法 | 说明 | 请求 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| `/ping` | GET | 心跳与设备探测 | - | `{"status":"ok","device_name":…}` |
| `/auth` | POST | PIN 码握手 | `{"pin":"123456","pin_hash":…}` | 匹配 `200`,失败 `403` |
| `/events` | GET | SSE 长连接(手机主动出站) | 头 `Accept: text/event-stream` | `data: {"type":"CLIP_SYNC","encrypted":…,"hash":…}` |
| `/sync` | POST | 手机向电脑提交密文 | `{"encrypted":…,"hash":…,"sender_id":…,"sender_name":…}` | `{"status":"ok"}` |

Android 端配对成功后主动向电脑发起出站 SSE 长连接,避免国产定制 ROM 在息屏待机时拦截入站 TCP 握手。

## 剪贴板流转方向

1. **手机 → 电脑**:手机捕获剪贴板变化 → `POST http://<pc-ip>:18236/sync`(密文)→ 电脑解密写入本地剪贴板;
2. **电脑 → 手机**:电脑捕获剪贴板变化 → 推送到已注册的 Peer `http://<phone-ip>:18237/sync` → 手机 Shizuku 静默写入系统剪贴板;
3. 任一端写入后均按“防回环”规则校验哈希,过滤自反射。
