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
