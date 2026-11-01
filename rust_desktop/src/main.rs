#![windows_subsystem = "windows"]

mod clipboard;
mod config;
mod crypto;
mod ip_util;
mod mdns;
mod server;
mod udp_discovery;

use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::System::DataExchange::*;
use windows_sys::Win32::System::Registry::*;
use windows_sys::Win32::System::Threading::*;
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const WM_TRAYICON: u32 = WM_USER + 1;
const WM_CLIPBOARDUPDATE: u32 = 0x031D;
const WM_QUERYENDSESSION: u32 = 0x0011;
const WM_ENDSESSION: u32 = 0x0016;

const ID_TRAY_STATUS: usize = 1001;
const ID_TRAY_IP: usize = 1002;
const ID_TRAY_PIN: usize = 1003;
const ID_TRAY_REGEN_PIN: usize = 1004;
const ID_TRAY_TOGGLE_AUTO: usize = 1005;
const ID_TRAY_AUTO_START: usize = 1006;
const ID_TRAY_SEND_MANUAL: usize = 1007;
const ID_TRAY_QUIT: usize = 1008;

static GLOBAL_STATE: Mutex<Option<AppState>> = Mutex::new(None);

#[derive(Clone)]
pub struct AppState {
    pub pin_code: Arc<RwLock<String>>,
    pub device_name: String,
    pub device_id: String,
    pub http_port: u16,
    pub main_ip: String,
    pub auto_sync: Arc<AtomicBool>,
    pub broadcaster: server::Broadcaster,
    pub hwnd: isize,
}

impl AppState {
    pub fn regen_pin(&self) -> String {
        let new_pin = config::generate_random_pin();
        {
            let mut pin_lock = self.pin_code.write().unwrap();
            *pin_lock = new_pin.clone();
        }
        // 同步持久化写入 config.json
        config::update_persisted_pin(&new_pin);
        // 断开旧客户端长连接，强制手机端使用新 PIN 重新握手
        self.broadcaster.disconnect_all();
        update_tray_tooltip(
            self.hwnd as HWND,
            &new_pin,
            &self.main_ip,
            self.http_port,
            self.auto_sync.load(Ordering::SeqCst),
        );
        crate::clipboard::set_clipboard_text(&new_pin);
        new_pin
    }

    pub fn toggle_auto_sync(&self) -> bool {
        let current = self.auto_sync.load(Ordering::SeqCst);
        let new_val = !current;
        self.auto_sync.store(new_val, Ordering::SeqCst);
        config::update_persisted_auto_sync(new_val);
        let pin = self.pin_code.read().unwrap().clone();
        update_tray_tooltip(self.hwnd as HWND, &pin, &self.main_ip, self.http_port, new_val);
        new_val
    }

    pub fn send_manual(&self) {
        if let Some(txt) = crate::clipboard::get_clipboard_text() {
            if !txt.trim().is_empty() {
                self.broadcaster.broadcast_text(&txt);
            }
        }
    }
}

fn to_wide(s: &str) -> Vec<u16> {
    OsStr::new(s).encode_wide().chain(Some(0)).collect()
}

fn is_auto_start_enabled() -> bool {
    unsafe {
        let subkey = to_wide(r"Software\Microsoft\Windows\CurrentVersion\Run");
        let mut hkey: HKEY = null_mut();
        let res = RegOpenKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            KEY_QUERY_VALUE,
            &mut hkey,
        );
        if res != 0 {
            return false;
        }

        let val_name = to_wide("CrossClip");
        let mut val_type: u32 = 0;
        let mut cb_data: u32 = 0;
        let query_res = RegQueryValueExW(
            hkey,
            val_name.as_ptr(),
            null_mut(),
            &mut val_type,
            null_mut(),
            &mut cb_data,
        );
        RegCloseKey(hkey);
        query_res == 0
    }
}

fn set_auto_start(enabled: bool) -> bool {
    unsafe {
        let subkey = to_wide(r"Software\Microsoft\Windows\CurrentVersion\Run");
        let val_name = to_wide("CrossClip");

        if enabled {
            let exe_path = match std::env::current_exe() {
                Ok(p) => p,
                Err(_) => return false,
            };
            let path_str = exe_path.to_string_lossy().to_string();
            let quoted_path = format!("\"{}\"", path_str);
            let wide_val = to_wide(&quoted_path);
            let byte_len = (wide_val.len() * std::mem::size_of::<u16>()) as u32;

            let mut hkey: HKEY = null_mut();
            let res = RegOpenKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                KEY_SET_VALUE,
                &mut hkey,
            );
            if res != 0 {
                return false;
            }

            let set_res = RegSetValueExW(
                hkey,
                val_name.as_ptr(),
                0,
                REG_SZ,
                wide_val.as_ptr() as *const u8,
                byte_len,
            );
            RegCloseKey(hkey);
            set_res == 0
        } else {
            let mut hkey: HKEY = null_mut();
            let res = RegOpenKeyExW(
                HKEY_CURRENT_USER,
                subkey.as_ptr(),
                0,
                KEY_SET_VALUE,
                &mut hkey,
            );
            if res != 0 {
                return false;
            }

            let del_res = RegDeleteValueW(hkey, val_name.as_ptr());
            RegCloseKey(hkey);
            del_res == 0 || del_res == 2
        }
    }
}


fn update_tray_tooltip(hwnd: HWND, pin: &str, ip: &str, port: u16, auto_sync: bool) {
    unsafe {
        let mode_str = if auto_sync { "自动互传" } else { "手动模式" };
        let tip: Vec<u16> = OsStr::new(&format!(
            "CrossClip 互传 ({})\nIP: {}:{}\nPIN 码: {}",
            mode_str, ip, port, pin
        ))
        .encode_wide()
        .chain(Some(0))
        .collect();

        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_TIP;

        let tip_len = tip.len().min(nid.szTip.len() - 1);
        std::ptr::copy_nonoverlapping(tip.as_ptr(), nid.szTip.as_mut_ptr(), tip_len);

        Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}

/// 动态创建精致美观的剪贴板互传图标 (天蓝板夹 + 白色互传线，适配 Windows 任务栏)
unsafe fn create_crossclip_tray_icon() -> HICON {
    let hdc_screen = GetDC(null_mut());
    let hdc_mem = CreateCompatibleDC(hdc_screen);
    let hdc_mask = CreateCompatibleDC(hdc_screen);

    let hbm_color = CreateCompatibleBitmap(hdc_screen, 32, 32);
    let hbm_mask = CreateBitmap(32, 32, 1, 1, null_mut());

    let old_color = SelectObject(hdc_mem, hbm_color);
    let old_mask = SelectObject(hdc_mask, hbm_mask);

    let black_brush = CreateSolidBrush(0x000000);
    let white_brush = CreateSolidBrush(0xFFFFFF);

    // 1. 初始化背景透明
    let full_rect = RECT {
        left: 0,
        top: 0,
        right: 32,
        bottom: 32,
    };
    FillRect(hdc_mask, &full_rect, white_brush);
    FillRect(hdc_mem, &full_rect, black_brush);

    // 2. 剪贴板主体板身 (天蓝色 #0284C7 -> BGR 0xC78402)
    let clip_brush = CreateSolidBrush(0xC78402);
    let clip_rect = RECT {
        left: 5,
        top: 6,
        right: 27,
        bottom: 30,
    };
    FillRect(hdc_mem, &clip_rect, clip_brush);
    FillRect(hdc_mask, &clip_rect, black_brush);

    // 3. 顶部金属夹扣
    let top_clip_brush = CreateSolidBrush(0xE2E8F0); // 银白色
    let top_clip_rect = RECT {
        left: 10,
        top: 2,
        right: 22,
        bottom: 8,
    };
    FillRect(hdc_mem, &top_clip_rect, top_clip_brush);
    FillRect(hdc_mask, &top_clip_rect, black_brush);

    // 4. 白色横线与互传标识
    let line_brush = CreateSolidBrush(0xFFFFFF);
    let line1 = RECT {
        left: 9,
        top: 12,
        right: 23,
        bottom: 14,
    };
    let line2 = RECT {
        left: 9,
        top: 17,
        right: 23,
        bottom: 19,
    };
    let line3 = RECT {
        left: 9,
        top: 22,
        right: 18,
        bottom: 24,
    };
    FillRect(hdc_mem, &line1, line_brush);
    FillRect(hdc_mem, &line2, line_brush);
    FillRect(hdc_mem, &line3, line_brush);

    SelectObject(hdc_mem, old_color);
    SelectObject(hdc_mask, old_mask);
    DeleteDC(hdc_mem);
    DeleteDC(hdc_mask);
    ReleaseDC(null_mut(), hdc_screen);

    DeleteObject(black_brush);
    DeleteObject(white_brush);
    DeleteObject(clip_brush);
    DeleteObject(top_clip_brush);
    DeleteObject(line_brush);

    let icon_info = ICONINFO {
        fIcon: 1,
        xHotspot: 0,
        yHotspot: 0,
        hbmMask: hbm_mask,
        hbmColor: hbm_color,
    };

    let icon = CreateIconIndirect(&icon_info);
    DeleteObject(hbm_color);
    DeleteObject(hbm_mask);
    icon
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_CLIPBOARDUPDATE => {
            let state_opt = GLOBAL_STATE.lock().unwrap().clone();
            if let Some(state) = state_opt {
                if state.auto_sync.load(Ordering::SeqCst) {
                    // 避让 15ms 允许复制源应用程序完成写操作并安全关闭剪贴板句柄
                    std::thread::sleep(std::time::Duration::from_millis(15));
                    if let Some(text) = crate::clipboard::on_clipboard_updated() {
                        state.broadcaster.broadcast_text(&text);
                    }
                }
            }
            0
        }
        WM_TRAYICON => {
            if lparam as u32 == WM_RBUTTONUP || lparam as u32 == WM_LBUTTONUP {
                let state_opt = GLOBAL_STATE.lock().unwrap().clone();
                if let Some(state) = state_opt {
                    let mut pt: POINT = std::mem::zeroed();
                    GetCursorPos(&mut pt);

                    let hmenu = CreatePopupMenu();
                    let current_pin = state.pin_code.read().unwrap().clone();
                    let is_auto = state.auto_sync.load(Ordering::SeqCst);

                    let status_text: Vec<u16> =
                        OsStr::new(&format!("🟢 状态: 运行中 (端口: {})", state.http_port))
                            .encode_wide()
                            .chain(Some(0))
                            .collect();
                    let live_ip = ip_util::get_local_lan_ips()
                        .first()
                        .cloned()
                        .unwrap_or_else(|| state.main_ip.clone());
                    let ip_text: Vec<u16> = OsStr::new(&format!("🌐 本机 IP: {}", live_ip))
                        .encode_wide()
                        .chain(Some(0))
                        .collect();
                    let pin_text: Vec<u16> =
                        OsStr::new(&format!("🔑 随机 PIN: {} (点击复制)", current_pin))
                            .encode_wide()
                            .chain(Some(0))
                            .collect();
                    let regen_text: Vec<u16> = OsStr::new("🔄 重新生成随机 PIN 码")
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let auto_str = if is_auto {
                        "✅ 自动互传模式: 已开启 (点击切换)"
                    } else {
                        "⚪ 自动互传模式: 已关闭 (点击切换)"
                    };
                    let toggle_auto_text: Vec<u16> = OsStr::new(auto_str)
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let is_autostart = is_auto_start_enabled();
                    let autostart_str = if is_autostart {
                        "✅ 开机自启动: 已开启 (点击切换)"
                    } else {
                        "⚪ 开机自启动: 已关闭 (点击切换)"
                    };
                    let autostart_text: Vec<u16> = OsStr::new(autostart_str)
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    let manual_send_text: Vec<u16> =
                        OsStr::new("📤 手动发送当前剪贴板到手机")
                            .encode_wide()
                            .chain(Some(0))
                            .collect();

                    let quit_text: Vec<u16> = OsStr::new("❌ 退出 CrossClip")
                        .encode_wide()
                        .chain(Some(0))
                        .collect();

                    AppendMenuW(hmenu, MF_STRING | MF_GRAYED, ID_TRAY_STATUS, status_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING | MF_GRAYED, ID_TRAY_IP, ip_text.as_ptr());
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_PIN, pin_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_REGEN_PIN, regen_text.as_ptr());
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_TOGGLE_AUTO, toggle_auto_text.as_ptr());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_AUTO_START, autostart_text.as_ptr());
                    if !is_auto {
                        AppendMenuW(
                            hmenu,
                            MF_STRING,
                            ID_TRAY_SEND_MANUAL,
                            manual_send_text.as_ptr(),
                        );
                    }
                    AppendMenuW(hmenu, MF_SEPARATOR, 0, null_mut());
                    AppendMenuW(hmenu, MF_STRING, ID_TRAY_QUIT, quit_text.as_ptr());

                    SetForegroundWindow(hwnd);
                    let cmd = TrackPopupMenu(
                        hmenu,
                        TPM_RETURNCMD | TPM_NONOTIFY,
                        pt.x,
                        pt.y,
                        0,
                        hwnd,
                        null_mut(),
                    );
                    DestroyMenu(hmenu);
                    PostMessageW(hwnd, WM_NULL, 0, 0);

                    if cmd == ID_TRAY_PIN as i32 {
                        crate::clipboard::set_clipboard_text(&current_pin);
                    } else if cmd == ID_TRAY_REGEN_PIN as i32 {
                        state.regen_pin();
                    } else if cmd == ID_TRAY_TOGGLE_AUTO as i32 {
                        state.toggle_auto_sync();
                    } else if cmd == ID_TRAY_AUTO_START as i32 {
                        let cur = is_auto_start_enabled();
                        set_auto_start(!cur);
                    } else if cmd == ID_TRAY_SEND_MANUAL as i32 {
                        state.send_manual();
                    } else if cmd == ID_TRAY_QUIT as i32 {
                        state.broadcaster.disconnect_all();
                        PostQuitMessage(0);
                    }
                }
            }
            0
        }
        WM_QUERYENDSESSION => {
            // 系统正在查询是否可以关机，返回 1 (TRUE) 明确同意关机，绝不阻止
            1
        }
        WM_ENDSESSION => {
            if wparam != 0 {
                // 系统已确定关机或注销，立即断开连接并极速退出进程
                let state_opt = GLOBAL_STATE.lock().unwrap().clone();
                if let Some(state) = state_opt {
                    state.broadcaster.disconnect_all();
                }
                RemoveClipboardFormatListener(hwnd);
                std::process::exit(0);
            }
            0
        }
        WM_DESTROY => {
            RemoveClipboardFormatListener(hwnd);
            0
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn main() {
    std::panic::set_hook(Box::new(|info| {
        let msg = format!("CrossClip 崩溃: {:?}", info);
        let _ = std::fs::write("crossclip_crash.log", msg);
    }));

    // 单实例互斥检查：通过系统命名互斥体彻底防止复数启动
    let mutex_name = to_wide("Local\\CrossClipSingleInstanceMutex");
    let _h_single_instance = unsafe {
        let h = CreateMutexW(null_mut(), 1, mutex_name.as_ptr());
        if !h.is_null() && GetLastError() == ERROR_ALREADY_EXISTS {
            std::process::exit(0);
        }
        h
    };

    let cfg = config::load_or_init_config();
    let initial_pin = cfg.pin_code.clone();
    let pin_code_arc = Arc::new(RwLock::new(initial_pin.clone()));
    let lan_ips = ip_util::get_local_lan_ips();
    let main_ip = lan_ips
        .first()
        .cloned()
        .unwrap_or_else(|| "127.0.0.1".to_string());
    let auto_sync_arc = Arc::new(AtomicBool::new(cfg.auto_sync));

    // 1. 广播器 (用于将电脑剪贴板并发推送到手机)
    let broadcaster =
        server::Broadcaster::new(pin_code_arc.clone(), cfg.device_id.clone());

    // 2. 启动 UDP 广播自动应答服务 (附带物理 IP，不暴露 PIN 哈希)
    udp_discovery::start_udp_discovery_responder(
        cfg.device_name.clone(),
        cfg.device_id.clone(),
        cfg.http_port,
    );

    // 3. 启动 mDNS 广播
    let _mdns =
        mdns::start_mdns_broadcast(&cfg.device_name, cfg.http_port, &cfg.device_id);

    // 4. 启动 HTTP API 服务与 SSE 下发服务 (非阻塞多线程模型)
    let pin_for_http = pin_code_arc.clone();
    let broadcaster_for_server = broadcaster.clone();
    let on_received = Arc::new(move |_text: String, _sender: String| {
        // 完全静默写入系统剪贴板
    });
    server::start_http_server(
        cfg.http_port,
        pin_for_http,
        cfg.device_name.clone(),
        broadcaster_for_server,
        on_received,
    );

    // 5. 独立后台剪贴板兜底监控线程 (Win32 原生事件为主，2000ms 轮询超低频防漏)
    let auto_sync_for_poll = auto_sync_arc.clone();
    let broadcaster_for_poll = broadcaster.clone();
    std::thread::spawn(move || {
        loop {
            std::thread::sleep(std::time::Duration::from_millis(2000));
            if auto_sync_for_poll.load(Ordering::SeqCst) {
                if let Some(text) = crate::clipboard::on_clipboard_updated() {
                    broadcaster_for_poll.broadcast_text(&text);
                }
            }
        }
    });

    // 6. 纯后台托盘图标与消息循环 (Win32 原生剪贴板监听由主窗口过程捕获)
    unsafe {
        let class_name: Vec<u16> = OsStr::new("CrossClipTrayWndMainV2")
            .encode_wide()
            .chain(Some(0))
            .collect();

        let wnd_class = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: 0,
            lpfnWndProc: Some(wnd_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: null_mut(),
            hIcon: null_mut(),
            hCursor: null_mut(),
            hbrBackground: null_mut(),
            lpszMenuName: null_mut(),
            lpszClassName: class_name.as_ptr(),
            hIconSm: null_mut(),
        };
        RegisterClassExW(&wnd_class);

        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            class_name.as_ptr(),
            0,
            0,
            0,
            0,
            0,
            null_mut(),
            null_mut(),
            null_mut(),
            null_mut(),
        );

        let state = AppState {
            pin_code: pin_code_arc,
            device_name: cfg.device_name.clone(),
            device_id: cfg.device_id.clone(),
            http_port: cfg.http_port,
            main_ip: main_ip.clone(),
            auto_sync: auto_sync_arc,
            broadcaster,
            hwnd: hwnd as isize,
        };
        {
            let mut g = GLOBAL_STATE.lock().unwrap();
            *g = Some(state);
        }

        // 注册 Win32 系统剪贴板格式监听器至主窗口
        AddClipboardFormatListener(hwnd);

        // 创建现代高清剪贴板互传图标
        let h_icon = create_crossclip_tray_icon();

        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.uCallbackMessage = WM_TRAYICON;
        nid.hIcon = if !h_icon.is_null() {
            h_icon
        } else {
            LoadIconW(null_mut(), IDI_APPLICATION)
        };

        let tip: Vec<u16> = OsStr::new(&format!(
            "CrossClip 互传 (自动互传)\nIP: {}:{}\nPIN 码: {}",
            main_ip, cfg.http_port, initial_pin
        ))
        .encode_wide()
        .chain(Some(0))
        .collect();
        let tip_len = tip.len().min(nid.szTip.len() - 1);
        std::ptr::copy_nonoverlapping(tip.as_ptr(), nid.szTip.as_mut_ptr(), tip_len);

        Shell_NotifyIconW(NIM_ADD, &nid);

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        Shell_NotifyIconW(NIM_DELETE, &nid);
        if !h_icon.is_null() {
            DestroyIcon(h_icon);
        }
    }
}
