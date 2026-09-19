use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use gpui::{
    AnyElement, App, ClickEvent, Context, Div, FocusHandle, Render, SharedString, Window,
    WindowOptions, div, hsla, prelude::*, px,
};
use nester_core::scan::ScanStats;
use qrcode::QrCode;

use crate::theme::{self, Mode, Palette};
use crate::{HostRuntime, win};

pub struct HostWindow {
    runtime: Arc<HostRuntime>,
    mode: Mode,
    qr: Option<QrMatrix>,
    last_scan: Option<ScanLog>,
    focus: FocusHandle,
}

#[derive(Clone)]
pub struct QrMatrix {
    size: usize,
    dark: Vec<bool>,
}

#[derive(Clone)]
pub struct ScanLog {
    pub label: String,
    pub stats: ScanStats,
}

impl HostWindow {
    pub fn open(cx: &mut App, runtime: Arc<HostRuntime>) -> gpui::WindowHandle<HostWindow> {
        let mode = if win::system_is_dark() {
            Mode::Dark
        } else {
            Mode::Light
        };
        let size = gpui::size(px(win::WINDOW_W as f32), px(win::WINDOW_H as f32));
        let bounds = gpui::Bounds::centered(None, size, cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(gpui::WindowBounds::Windowed(bounds)),
                app_id: Some("nester".into()),
                titlebar: Some(gpui::TitlebarOptions {
                    title: Some(win::WINDOW_TITLE.into()),
                    appears_transparent: true,
                    ..Default::default()
                }),
                window_background: gpui::WindowBackgroundAppearance::Blurred,
                is_resizable: false,
                ..Default::default()
            },
            |_, cx| cx.new(|cx| Self::new(runtime, mode, cx)),
        )
        .expect("failed to open nester window")
    }

    fn new(runtime: Arc<HostRuntime>, mode: Mode, cx: &mut Context<Self>) -> Self {
        let qr = QrMatrix::build(&runtime.pairing_payload()).ok();
        let last_scan = runtime.last_scan.lock().unwrap().clone();

        let poll_runtime = Arc::clone(&runtime);
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(2)).await;
                let log = poll_runtime.last_scan.lock().unwrap().clone();
                if this.update(cx, |this, _| this.last_scan = log).is_err() {
                    return;
                }
            }
        })
        .detach();

        Self {
            runtime,
            mode,
            qr,
            last_scan,
            focus: cx.focus_handle(),
        }
    }
}

impl HostRuntime {
    fn folder_rows(&self) -> Vec<(String, String)> {
        self.folders
            .lock()
            .unwrap()
            .iter()
            .map(|f| (f.label.clone(), f.root.to_string_lossy().into_owned()))
            .collect()
    }

    pub fn add_folder(&self, path: &std::path::Path) -> bool {
        let Ok(canonical) = path.canonicalize() else {
            tracing::error!("cannot add folder {}", path.display());
            return false;
        };
        let mut config = self.config.lock().unwrap().clone();
        let key = canonical.to_string_lossy().into_owned();
        if config.folders.iter().any(|f| f.eq_ignore_ascii_case(&key)) {
            return false;
        }
        config.folders.push(key);
        if config.save(&self.config_path).is_ok() {
            self.pending_restart.store(true, Ordering::Relaxed);
            true
        } else {
            false
        }
    }

    pub fn remove_folder(&self, path: &str) -> bool {
        let mut config = self.config.lock().unwrap().clone();
        let before = config.folders.len();
        config.folders.retain(|p| !p.eq_ignore_ascii_case(path));
        if config.folders.len() == before {
            return false;
        }
        let ok = config.save(&self.config_path).is_ok();
        if ok {
            self.pending_restart.store(true, Ordering::Relaxed);
        }
        ok
    }
}

impl QrMatrix {
    pub fn build(payload: &str) -> anyhow::Result<Self> {
        let code = QrCode::with_error_correction_level(payload.as_bytes(), qrcode::EcLevel::L)?;
        let size = code.width();
        let dark = code
            .to_colors()
            .into_iter()
            .map(|c| c == qrcode::Color::Dark)
            .collect();
        Ok(QrMatrix { size, dark })
    }

    fn element(&self) -> impl IntoElement + use<> {
        let size = self.size;
        let dark = self.dark.clone();
        div()
            .rounded(px(10.))
            .bg(hsla(0.0, 0.0, 1.0, 1.0))
            .p(px(10.))
            .flex()
            .flex_col()
            .children((0..size).map(move |y| {
                let dark = dark.clone();
                div().flex().children((0..size).map(move |x| {
                    div().size(px(3.)).bg(if dark[y * size + x] {
                        hsla(0.0, 0.0, 0.0, 1.0)
                    } else {
                        hsla(0.0, 0.0, 1.0, 1.0)
                    })
                }))
            }))
    }
}

impl Render for HostWindow {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let p = theme::palette(self.mode);
        let qr = self.qr.clone();
        div()
            .font(theme::ui_font())
            .text_color(p.foreground)
            .text_size(px(13.))
            .size_full()
            .flex()
            .flex_col()
            .bg(p.card)
            .rounded(px(22.))
            .track_focus(&self.focus)
            .child(header(&p))
            .child(
                div()
                    .id("scroll")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .flex()
                    .flex_col()
                    .px(px(20.))
                    .gap(px(12.))
                    .pb(px(12.))
                    .child(pairing_section(&p, &self.runtime, qr.as_ref()))
                    .children(folders_section(self, &p, cx)),
            )
            .child(footer(self, &p))
    }
}

fn header(p: &Palette) -> Div {
    div()
        .flex()
        .items_center()
        .gap(px(9.))
        .px(px(20.))
        .pt(px(18.))
        .pb(px(4.))
        .child(
            div()
                .text_size(px(17.))
                .font_weight(gpui::FontWeight::SEMIBOLD)
                .child("Nester"),
        )
        .child(
            div()
                .rounded(px(9.))
                .bg(p.badge_bg)
                .text_color(p.badge_fg)
                .px(px(8.))
                .py(px(2.))
                .text_size(px(11.5))
                .font_weight(gpui::FontWeight::MEDIUM)
                .child("LAN"),
        )
}

fn pairing_section(p: &Palette, runtime: &Arc<HostRuntime>, qr: Option<&QrMatrix>) -> Div {
    let ip: Option<IpAddr> = runtime.ip;
    let port = runtime.port;
    let url = match ip {
        Some(ip) => SharedString::from(format!("http://{ip}:{port}/api/health")),
        None => "LAN IP not detected yet".into(),
    };
    div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .pt(px(4.))
        .child(
            div()
                .text_size(px(14.))
                .font_weight(gpui::FontWeight::SEMIBOLD)
                .child("Pair a device"),
        )
        .child(
            div()
                .text_color(p.muted)
                .text_size(px(12.5))
                .child("Scan this with the Nester app. Both devices must be on the same WiFi."),
        )
        .child(
            div()
                .rounded(px(16.))
                .bg(p.inner)
                .px(px(14.))
                .py(px(14.))
                .flex()
                .flex_col()
                .items_center()
                .gap(px(8.))
                .child(match qr {
                    Some(qr) => qr.element().into_any_element(),
                    None => div()
                        .text_color(p.muted)
                        .text_size(px(12.))
                        .child("QR unavailable")
                        .into_any_element(),
                })
                .child(div().text_color(p.muted).text_size(px(11.5)).child(url))
                .child(
                    div()
                        .text_color(p.muted)
                        .text_size(px(11.))
                        .child(format!("token {}", truncate(&runtime.pairing_token, 18))),
                ),
        )
}

fn folders_section(
    state: &mut HostWindow,
    p: &Palette,
    cx: &mut Context<HostWindow>,
) -> Vec<AnyElement> {
    let rows = state.runtime.folder_rows();
    let mut out = Vec::new();

    out.push(
        div()
            .flex()
            .items_center()
            .justify_between()
            .pt(px(4.))
            .child(
                div()
                    .text_size(px(14.))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .child("Folders"),
            )
            .child(
                div()
                    .id("add-folder")
                    .rounded(px(9.))
                    .bg(p.button_bg)
                    .text_color(p.button_fg)
                    .px(px(12.))
                    .py(px(5.))
                    .text_size(px(12.))
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .cursor_pointer()
                    .hover(|s| s.opacity(0.85))
                    .on_click(cx.listener(|this, _: &ClickEvent, _, cx| {
                        if let Some(path) = rfd::FileDialog::new().pick_folder()
                            && this.runtime.add_folder(&path)
                        {
                            cx.notify();
                        }
                    }))
                    .child("Add"),
            )
            .into_any_element(),
    );

    out.push(
        div()
            .rounded(px(16.))
            .bg(p.inner)
            .px(px(14.))
            .py(px(6.))
            .flex()
            .flex_col()
            .children(
                rows.into_iter()
                    .map(|(name, path)| folder_row(p, &name, &path, cx)),
            )
            .into_any_element(),
    );

    if state.runtime.pending_restart.load(Ordering::Relaxed) {
        out.push(
            div()
                .rounded(px(9.))
                .bg(p.badge_bg)
                .text_color(p.warn)
                .px(px(10.))
                .py(px(6.))
                .text_size(px(11.5))
                .child("Folder changes saved. Restart the host to apply.")
                .into_any_element(),
        );
    }
    out
}

fn folder_row(p: &Palette, name: &str, path: &str, cx: &mut Context<HostWindow>) -> AnyElement {
    div()
        .id(format!("folder-{}", path))
        .flex()
        .items_center()
        .justify_between()
        .py(px(7.))
        .child(
            div().flex().flex_col().child(
                div()
                    .text_size(px(12.5))
                    .font_weight(gpui::FontWeight::MEDIUM)
                    .child(name.to_string()),
            ),
        )
        .child(
            div()
                .id(format!("remove-{}", path))
                .px(px(8.))
                .py(px(3.))
                .rounded(px(7.))
                .text_color(p.danger)
                .text_size(px(11.5))
                .cursor_pointer()
                .hover(|s| s.bg(p.divider))
                .on_click(cx.listener({
                    let path = path.to_string();
                    move |this, _: &ClickEvent, _, cx| {
                        if this.runtime.remove_folder(&path) {
                            cx.notify();
                        }
                    }
                }))
                .child("Remove"),
        )
        .into_any_element()
}

fn footer(state: &HostWindow, p: &Palette) -> Div {
    let status = match &state.last_scan {
        Some(log) => SharedString::from(format!(
            "{}: +{} ~{} -{}",
            log.label, log.stats.added, log.stats.updated, log.stats.removed
        )),
        None => "Watching for changes".into(),
    };
    div()
        .flex()
        .items_center()
        .justify_between()
        .px(px(20.))
        .py(px(14.))
        .child(div().text_color(p.muted).text_size(px(12.)).child(status))
        .child(
            div()
                .text_color(p.ok)
                .text_size(px(12.))
                .child(format!(":{}", state.runtime.port)),
        )
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max).collect();
        format!("{cut}…")
    }
}
