use std::ffi::OsStr;
use std::os::windows::ffi::OsStrExt;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::System::DataExchange::*;
use windows_sys::Win32::System::Memory::*;

const CF_UNICODETEXT: u32 = 13;

static LAST_TEXT_HASH: Mutex<String> = Mutex::new(String::new());
static IS_UPDATING_SELF: AtomicBool = AtomicBool::new(false);

pub fn set_clipboard_text(text: &str) -> bool {
    let hash = crate::crypto::compute_hash(text);
    {
        let mut last = LAST_TEXT_HASH.lock().unwrap();
        *last = hash;
    }
    IS_UPDATING_SELF.store(true, Ordering::SeqCst);

    let wide: Vec<u16> = OsStr::new(text).encode_wide().chain(Some(0)).collect();
    let bytes_len = wide.len() * 2;

    let mut opened = false;
    for retry in 0..4 {
        unsafe {
            if OpenClipboard(null_mut()) != 0 {
                opened = true;
                break;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(30 + retry * 20));
    }

    if !opened {
        IS_UPDATING_SELF.store(false, Ordering::SeqCst);
        return false;
    }

    unsafe {
        EmptyClipboard();

        let h_mem = GlobalAlloc(GMEM_MOVEABLE, bytes_len);
        if h_mem.is_null() {
            CloseClipboard();
            IS_UPDATING_SELF.store(false, Ordering::SeqCst);
            return false;
        }

        let ptr = GlobalLock(h_mem) as *mut u16;
        if !ptr.is_null() {
            std::ptr::copy_nonoverlapping(wide.as_ptr(), ptr, wide.len());
            GlobalUnlock(h_mem);
            if SetClipboardData(CF_UNICODETEXT, h_mem as HANDLE).is_null() {
                GlobalFree(h_mem);
            }
        } else {
            GlobalFree(h_mem);
        }

        CloseClipboard();
    }

    IS_UPDATING_SELF.store(false, Ordering::SeqCst);
    true
}

pub fn get_clipboard_text() -> Option<String> {
    let mut opened = false;
    for retry in 0..5 {
        unsafe {
            if OpenClipboard(null_mut()) != 0 {
                opened = true;
                break;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(10 + retry * 10));
    }

    if !opened {
        return None;
    }

    unsafe {
        let h_data = GetClipboardData(CF_UNICODETEXT);
        if h_data.is_null() {
            CloseClipboard();
            return None;
        }

        let max_bytes = GlobalSize(h_data as HGLOBAL);
        if max_bytes == 0 {
            CloseClipboard();
            return None;
        }
        let max_words = (max_bytes as usize) / 2;

        let ptr = GlobalLock(h_data as HGLOBAL) as *const u16;
        if ptr.is_null() {
            CloseClipboard();
            return None;
        }

        let mut len = 0;
        while len < max_words && *ptr.add(len) != 0 {
            len += 1;
        }

        let slice = std::slice::from_raw_parts(ptr, len);
        let result = String::from_utf16_lossy(slice);

        GlobalUnlock(h_data as HGLOBAL);
        CloseClipboard();
        Some(result)
    }
}

pub fn on_clipboard_updated() -> Option<String> {
    if IS_UPDATING_SELF.load(Ordering::SeqCst) {
        return None;
    }

    if let Some(text) = get_clipboard_text() {
        if text.trim().is_empty() {
            return None;
        }
        let hash = crate::crypto::compute_hash(&text);
        let mut last = LAST_TEXT_HASH.lock().unwrap();
        if *last != hash {
            *last = hash;
            return Some(text);
        }
    }
    None
}
