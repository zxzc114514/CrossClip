use rand::Rng;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub device_id: String,
    pub device_name: String,
    pub pin_code: String,
    pub http_port: u16,
    pub auto_sync: bool,
}

pub fn generate_random_pin() -> String {
    let mut rng = rand::thread_rng();
    let pin: u32 = rng.gen_range(100000..=999999);
    pin.to_string()
}

pub fn get_config_path() -> PathBuf {
    // 优先使用当前可执行文件同目录下的 config.json
    if let Ok(exe_path) = std::env::current_exe() {
        if let Some(parent) = exe_path.parent() {
            let local_cfg = parent.join("config.json");
            return local_cfg;
        }
    }
    // 降级使用当前工作目录
    PathBuf::from("config.json")
}

pub fn load_or_init_config() -> AppConfig {
    let path = get_config_path();
    if path.exists() {
        if let Ok(content) = fs::read_to_string(&path) {
            if let Ok(mut cfg) = serde_json::from_str::<AppConfig>(&content) {
                // 校验 PIN 码是否有效（6位数字）
                if cfg.pin_code.len() == 6 && cfg.pin_code.chars().all(|c| c.is_ascii_digit()) {
                    // 更新可能变更的计算机名
                    let hostname = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows 电脑".to_string());
                    cfg.device_name = hostname;
                    return cfg;
                }
            }
        }
    }

    // 文件不存在或校验失败，创建全新配置并持久化
    let mut rng = rand::thread_rng();
    let hostname = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows 电脑".to_string());
    let pin = generate_random_pin();

    let new_cfg = AppConfig {
        device_id: format!("win_{:04x}", rng.gen::<u16>()),
        device_name: hostname,
        pin_code: pin,
        http_port: 18236,
        auto_sync: true,
    };

    save_config(&new_cfg);
    new_cfg
}

pub fn save_config(config: &AppConfig) {
    let path = get_config_path();
    if let Ok(json) = serde_json::to_string_pretty(config) {
        let _ = fs::write(path, json);
    }
}

pub fn update_persisted_pin(new_pin: &str) {
    let mut cfg = load_or_init_config();
    cfg.pin_code = new_pin.to_string();
    save_config(&cfg);
}

pub fn update_persisted_auto_sync(auto_sync: bool) {
    let mut cfg = load_or_init_config();
    cfg.auto_sync = auto_sync;
    save_config(&cfg);
}

