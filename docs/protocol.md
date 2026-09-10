# CrossClip 局域网协议说明

## 端口与服务

| 端口 | 协议 | 用途 |
| :--- | :--- | :--- |
| 18234 | UDP | 自发现广播/应答(携带 `device_id`、设备名、HTTP 端口与候选 IP 列表) |
| 18236 | HTTP/SSE | Windows 端服务:鉴权、密文同步、SSE 剪贴板下发 |
| 18237 | HTTP | Android 端本地服务:接收电脑端唤醒与对等推送 |
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
| `/ping` | GET | 心跳与设备探测 | - | `{"status":"ok","device_name":…,"device_id":…}` |
| `/auth` | POST | PIN 码握手 | `{"pin":"123456","pin_hash":…,"device_id":…,"device_name":…,"client_port":…}` | 匹配 `200`(含 `device_id`),失败 `403` |
| `/events` | GET | SSE 长连接(手机主动出站) | 头 `Accept: text/event-stream` | `data: {"type":"CLIP_SYNC","encrypted":…,"hash":…}` |
| `/sync` | POST | 手机向电脑提交密文 | `{"encrypted":…,"hash":…,"sender_id":…,"sender_name":…}` | `{"status":"ok"}` |

## Android 端本地端点(端口 18237)

| 路径 | 方法 | 说明 |
| :--- | :--- | :--- |
| `/wakeup` | POST | 接收电脑端单播唤醒:瞬态获取 3 秒 WakeLock,提取电脑 IP/端口后发起静默握手并重建 SSE |
| `/sync` | POST | 接收电脑端推送的剪贴板密文并静默写入系统剪贴板 |
| `/ping` | GET | 探活应答 |

## 唤醒与电脑重启自愈

- 电脑端在 `config.json` 中持久化最近一次成功通信的手机 IP;
- 电脑端启动后由唤醒守护线程在前 15 秒内每 3 秒向手机 `18237` 端口发送 `POST /wakeup`,常态运行期间每 60 秒一次,以单播报文击穿 Wi-Fi 硬件级 DTIM 休眠;
- 电脑端启动时遍历物理网卡,按各子网广播地址发送上线宣告,避免全局广播被派发到虚拟网卡或 WSL 适配器;
- 手机端未连接时按 25 秒、已连接时按 60 秒间隔守候,并在亮屏/解锁/网络切换等事件驱动下单次定向探活;扫描任务引入世代标记与互斥保护,避免线程池任务积压。

Android 端配对成功后主动向电脑发起出站 SSE 长连接,避免国产定制 ROM 在息屏待机时拦截入站 TCP 握手。

## 剪贴板流转方向

1. **手机 → 电脑**:手机捕获剪贴板变化 → `POST http://<pc-ip>:18236/sync`(密文)→ 电脑解密写入本地剪贴板;
2. **电脑 → 手机**:电脑捕获剪贴板变化 → 推送到已注册的 Peer `http://<phone-ip>:18237/sync` → 手机 Shizuku 静默写入系统剪贴板;
3. 任一端写入后均按“防回环”规则校验哈希,过滤自反射。
