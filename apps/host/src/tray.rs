use std::sync::mpsc::{Receiver, Sender};

#[cfg(windows)]
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
#[cfg(windows)]
use windows_sys::Win32::Graphics::Gdi::{
    BI_RGB, BITMAPINFO, BITMAPINFOHEADER, CreateBitmap, CreateDIBSection, DIB_RGB_COLORS,
    DeleteObject, GetDC, ReleaseDC,
};
#[cfg(windows)]
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
#[cfg(windows)]
use windows_sys::Win32::UI::Shell::{
    NIF_ICON, NIF_MESSAGE, NIF_TIP, NIM_ADD, NIM_DELETE, NOTIFYICONDATAW, Shell_NotifyIconW,
};
#[cfg(windows)]
use windows_sys::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CW_USEDEFAULT, CreateIconIndirect, CreatePopupMenu, CreateWindowExW,
    DefWindowProcW, DestroyIcon, DestroyMenu, DispatchMessageW, GWLP_USERDATA, GetCursorPos,
    GetMessageW, GetWindowLongPtrW, ICONINFO, KillTimer, MF_SEPARATOR, MF_STRING, PostMessageW,
    PostQuitMessage, RegisterClassW, SetForegroundWindow, SetTimer, SetWindowLongPtrW,
    TPM_BOTTOMALIGN, TPM_LEFTALIGN, TrackPopupMenu, TranslateMessage, WM_APP, WM_COMMAND,
    WM_CREATE, WM_DESTROY, WM_LBUTTONUP, WM_NULL, WM_PAINT, WM_RBUTTONUP, WM_TIMER, WNDCLASSW,
    WS_EX_TOOLWINDOW, WS_POPUP,
};

pub enum TrayCommand {
    Shutdown,
}

#[derive(Clone, Copy)]
pub enum UiMessage {
    ToggleWindow,
    ShowWindow,
    Quit,
}

pub struct TrayController {
    commands: Sender<TrayCommand>,
}

impl TrayController {
    pub fn spawn(ui: Sender<UiMessage>) -> Self {
        let (commands, rx) = std::sync::mpsc::channel();
        #[cfg(windows)]
        std::thread::Builder::new()
            .name("nester-tray".into())
            .spawn(move || tray_thread(ui, rx))
            .expect("spawn tray thread");
        #[cfg(not(windows))]
        std::mem::forget((ui, rx));
        Self { commands }
    }
}

impl Drop for TrayController {
    fn drop(&mut self) {
        let _ = self.commands.send(TrayCommand::Shutdown);
    }
}

#[cfg(windows)]
const TRAY_CALLBACK: u32 = WM_APP + 1;
#[cfg(windows)]
const TRAY_ID: u32 = 1;
#[cfg(windows)]
const MENU_OPEN: usize = 1;
#[cfg(windows)]
const MENU_QUIT: usize = 2;
#[cfg(windows)]
const ICON_PX: i32 = 32;

#[cfg(windows)]
struct TrayState {
    ui: Sender<UiMessage>,
    icon: NOTIFYICONDATAW,
    quitting: bool,
}

#[cfg(windows)]
fn tray_thread(ui: Sender<UiMessage>, commands: Receiver<TrayCommand>) {
    unsafe {
        let class_name: Vec<u16> = "NesterTray\0".encode_utf16().collect();
        let state = Box::into_raw(Box::new(TrayState {
            ui,
            icon: std::mem::zeroed(),
            quitting: false,
        }));

        let class = WNDCLASSW {
            lpfnWndProc: Some(tray_wnd_proc),
            hInstance: GetModuleHandleW(std::ptr::null()),
            lpszClassName: class_name.as_ptr(),
            ..std::mem::zeroed()
        };
        RegisterClassW(&class);

        let hwnd = CreateWindowExW(
            WS_EX_TOOLWINDOW,
            class_name.as_ptr(),
            std::ptr::null(),
            WS_POPUP,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            0,
            0,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            GetModuleHandleW(std::ptr::null()),
            state as *const _,
        );
        if hwnd.is_null() {
            return;
        }

        let hicon = create_icon();

        let state = &mut *state;
        let mut icon: NOTIFYICONDATAW = std::mem::zeroed();
        icon.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        icon.hWnd = hwnd;
        icon.uID = TRAY_ID;
        icon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        icon.uCallbackMessage = TRAY_CALLBACK;
        icon.hIcon = hicon as _;
        let tip: Vec<u16> = "Nester\0".encode_utf16().collect();
        let n = tip.len().min(icon.szTip.len());
        icon.szTip[..n].copy_from_slice(&tip[..n]);
        state.icon = icon;
        Shell_NotifyIconW(NIM_ADD, &state.icon);

        SetTimer(hwnd, 1, 100, None);
        let mut msg = std::mem::zeroed();
        loop {
            while let Ok(cmd) = commands.try_recv() {
                match cmd {
                    TrayCommand::Shutdown => {
                        state.quitting = true;
                        PostMessageW(hwnd, WM_NULL, 0, 0);
                    }
                }
            }
            let result = GetMessageW(&mut msg, hwnd, 0, 0);
            if result <= 0 || state.quitting {
                break;
            }
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        KillTimer(hwnd, 1);
        Shell_NotifyIconW(NIM_DELETE, &state.icon);
        if !state.icon.hIcon.is_null() {
            DestroyIcon(state.icon.hIcon as _);
        }
    }
}

#[cfg(windows)]
unsafe extern "system" fn tray_wnd_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    unsafe {
        if message == WM_CREATE {
            let create =
                &*(lparam as *const windows_sys::Win32::UI::WindowsAndMessaging::CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
            return 0;
        }
        let state = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut TrayState;
        if state.is_null() {
            return DefWindowProcW(hwnd, message, wparam, lparam);
        }
        let state = &mut *state;

        match message {
            TRAY_CALLBACK if lparam as u32 == WM_LBUTTONUP => {
                let _ = state.ui.send(UiMessage::ToggleWindow);
                0
            }
            TRAY_CALLBACK if lparam as u32 == WM_RBUTTONUP => {
                show_menu(hwnd);
                0
            }
            WM_COMMAND => {
                let id = wparam & 0xffff;
                match id {
                    MENU_OPEN => {
                        let _ = state.ui.send(UiMessage::ShowWindow);
                    }
                    MENU_QUIT => {
                        let _ = state.ui.send(UiMessage::Quit);
                    }
                    _ => {}
                }
                0
            }
            WM_TIMER => 0,
            WM_PAINT => {
                let mut ps: windows_sys::Win32::Graphics::Gdi::PAINTSTRUCT = std::mem::zeroed();
                windows_sys::Win32::Graphics::Gdi::BeginPaint(hwnd, &mut ps);
                windows_sys::Win32::Graphics::Gdi::EndPaint(hwnd, &ps);
                0
            }
            WM_DESTROY => {
                PostQuitMessage(0);
                0
            }
            _ => DefWindowProcW(hwnd, message, wparam, lparam),
        }
    }
}

#[cfg(windows)]
fn show_menu(hwnd: HWND) {
    unsafe {
        let menu = CreatePopupMenu();
        let open: Vec<u16> = "Open Nester\0".encode_utf16().collect();
        let quit: Vec<u16> = "Quit\0".encode_utf16().collect();
        AppendMenuW(menu, MF_STRING, MENU_OPEN, open.as_ptr());
        AppendMenuW(menu, MF_SEPARATOR, 0, std::ptr::null());
        AppendMenuW(menu, MF_STRING, MENU_QUIT, quit.as_ptr());
        let mut point = POINT { x: 0, y: 0 };
        GetCursorPos(&mut point);
        SetForegroundWindow(hwnd);
        TrackPopupMenu(
            menu,
            TPM_LEFTALIGN | TPM_BOTTOMALIGN,
            point.x,
            point.y,
            0,
            hwnd,
            std::ptr::null(),
        );
        PostMessageW(hwnd, WM_NULL, 0, 0);
        DestroyMenu(menu);
    }
}

#[cfg(windows)]
fn create_icon() -> windows_sys::Win32::UI::WindowsAndMessaging::HICON {
    unsafe {
        let pixels = icon_pixels();
        let mut bmi: BITMAPINFO = std::mem::zeroed();
        bmi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        bmi.bmiHeader.biWidth = ICON_PX;
        bmi.bmiHeader.biHeight = -ICON_PX;
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = BI_RGB;
        let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
        let hdc = GetDC(std::ptr::null_mut());
        let color = CreateDIBSection(
            hdc,
            &bmi,
            DIB_RGB_COLORS,
            &mut bits,
            std::ptr::null_mut(),
            0,
        );
        ReleaseDC(std::ptr::null_mut(), hdc);
        if color.is_null() || bits.is_null() {
            return std::ptr::null_mut();
        }
        std::ptr::copy_nonoverlapping(pixels.as_ptr(), bits as *mut u8, pixels.len());

        let mask_row = ((ICON_PX + 31) / 32) * 4;
        let mask = vec![0u8; (mask_row * ICON_PX) as usize];
        let mask_bmp = CreateBitmap(
            ICON_PX,
            ICON_PX,
            1,
            1,
            mask.as_ptr() as *const core::ffi::c_void,
        );

        let info = ICONINFO {
            fIcon: 1,
            xHotspot: 0,
            yHotspot: 0,
            hbmMask: mask_bmp,
            hbmColor: color,
        };
        let hicon = CreateIconIndirect(&info);
        DeleteObject(color);
        DeleteObject(mask_bmp);
        hicon
    }
}

#[cfg(windows)]
fn icon_pixels() -> Vec<u8> {
    let s = ICON_PX;
    let mut px = vec![0u8; (s * s * 4) as usize];
    let radius = 7.0f32;
    let dot_r = 5.0f32;
    let half = s as f32 / 2.0;
    for y in 0..s {
        for x in 0..s {
            let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
            let qx = (fx - half).abs() - (half - radius);
            let qy = (fy - half).abs() - (half - radius);
            let outside = qx.max(0.0).powi(2) + qy.max(0.0).powi(2);
            let d = outside.sqrt() + qx.max(qy).min(0.0) - radius;
            if d >= 0.0 {
                continue;
            }
            let i = ((y * s + x) * 4) as usize;
            let dot = (fx - half).powi(2) + (fy - half).powi(2) < dot_r * dot_r;
            let (b, g, r) = if dot { (255, 255, 255) } else { (80, 175, 76) };
            px[i] = b;
            px[i + 1] = g;
            px[i + 2] = r;
            px[i + 3] = 255;
        }
    }
    px
}
