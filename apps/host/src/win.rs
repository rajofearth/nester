pub const WINDOW_TITLE: &str = "Nester";
pub const WINDOW_W: i32 = 380;
pub const WINDOW_H: i32 = 640;

#[cfg(windows)]
pub fn system_is_dark() -> bool {
    use windows_sys::Win32::System::Registry::{
        HKEY_CURRENT_USER as HKEY, RRF_RT_REG_DWORD, RegGetValueW,
    };
    unsafe {
        let sub: Vec<u16> = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect();
        let val: Vec<u16> = "AppsUseLightTheme"
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect();
        let mut data: u32 = 1;
        let mut size: u32 = 4;
        let status = RegGetValueW(
            HKEY,
            sub.as_ptr(),
            val.as_ptr(),
            RRF_RT_REG_DWORD,
            std::ptr::null_mut(),
            &mut data as *mut u32 as *mut core::ffi::c_void,
            &mut size,
        );
        if status == 0 { data == 0 } else { true }
    }
}

#[cfg(not(windows))]
pub fn system_is_dark() -> bool {
    true
}

#[cfg(windows)]
pub fn find_hwnd() -> Option<isize> {
    use windows_sys::Win32::UI::WindowsAndMessaging::FindWindowW;
    let title: Vec<u16> = WINDOW_TITLE
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    let hwnd = unsafe { FindWindowW(std::ptr::null(), title.as_ptr()) };
    (!hwnd.is_null()).then_some(hwnd as isize)
}

#[cfg(windows)]
pub fn is_visible() -> bool {
    use windows_sys::Win32::UI::WindowsAndMessaging::IsWindowVisible;
    match find_hwnd() {
        Some(hwnd) => unsafe { IsWindowVisible(hwnd as _) != 0 },
        None => false,
    }
}

#[cfg(windows)]
pub fn show(hwnd: isize) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{SW_SHOW, SetForegroundWindow, ShowWindow};
    unsafe {
        ShowWindow(hwnd as _, SW_SHOW);
        SetForegroundWindow(hwnd as _);
    }
}

#[cfg(windows)]
pub fn hide(hwnd: isize) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{SW_HIDE, ShowWindow};
    unsafe { ShowWindow(hwnd as _, SW_HIDE) };
}

#[cfg(not(windows))]
pub fn find_hwnd() -> Option<isize> {
    None
}

#[cfg(not(windows))]
pub fn is_visible() -> bool {
    false
}

#[cfg(not(windows))]
pub fn show(_hwnd: isize) {}

#[cfg(not(windows))]
pub fn hide(_hwnd: isize) {}

#[cfg(windows)]
const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
#[cfg(windows)]
const APPROVED_KEY: &str =
    r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
#[cfg(windows)]
const AUTOSTART_VALUE: &str = "nester";

/// Task Manager owns StartupApproved\Run: its first byte's low bit means
/// "user disabled this entry", so an existing Run value with that byte odd
/// counts as off. We read it but never write it.
#[cfg(windows)]
pub fn autostart_enabled() -> bool {
    use winreg::enums::HKEY_CURRENT_USER;

    let hkcu = winreg::RegKey::predef(HKEY_CURRENT_USER);
    let Ok(run) = hkcu.open_subkey(RUN_KEY) else {
        return false;
    };
    if run.get_value::<String, _>(AUTOSTART_VALUE).is_err() {
        return false;
    }
    match hkcu
        .open_subkey(APPROVED_KEY)
        .and_then(|k| k.get_raw_value(AUTOSTART_VALUE))
    {
        Ok(value) => value.bytes.first().map(|b| b % 2 == 0).unwrap_or(true),
        Err(_) => true,
    }
}

#[cfg(windows)]
pub fn set_autostart(on: bool) {
    use winreg::enums::{HKEY_CURRENT_USER, KEY_WRITE};

    let hkcu = winreg::RegKey::predef(HKEY_CURRENT_USER);
    let Ok(exe) = std::env::current_exe() else {
        return;
    };
    if on {
        if let Ok((key, _)) = hkcu.create_subkey(RUN_KEY) {
            let _ = key.set_value(AUTOSTART_VALUE, &format!("\"{}\"", exe.display()));
        }
    } else if let Ok(key) = hkcu.open_subkey_with_flags(RUN_KEY, KEY_WRITE) {
        let _ = key.delete_value(AUTOSTART_VALUE);
    }
}

#[cfg(not(windows))]
pub fn autostart_enabled() -> bool {
    false
}

#[cfg(not(windows))]
pub fn set_autostart(_on: bool) {}
