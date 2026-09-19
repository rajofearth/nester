use gpui::{Hsla, hsla};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Light,
    Dark,
}

#[derive(Clone, Copy)]
pub struct Palette {
    pub card: Hsla,
    pub inner: Hsla,
    pub divider: Hsla,
    pub foreground: Hsla,
    pub muted: Hsla,
    pub badge_bg: Hsla,
    pub badge_fg: Hsla,
    pub button_bg: Hsla,
    pub button_fg: Hsla,
    pub danger: Hsla,
    pub ok: Hsla,
    pub warn: Hsla,
}

pub fn palette(mode: Mode) -> Palette {
    match mode {
        Mode::Light => Palette {
            card: hsla(0.0, 0.0, 0.98, 0.72),
            inner: hsla(0.0, 0.0, 0.85, 0.5),
            divider: hsla(0.0, 0.0, 0.0, 0.07),
            foreground: hsla(0.0, 0.0, 0.08, 1.0),
            muted: hsla(0.0, 0.0, 0.45, 1.0),
            badge_bg: hsla(0.0, 0.0, 1.0, 0.6),
            badge_fg: hsla(0.0, 0.0, 0.1, 1.0),
            button_bg: hsla(0.0, 0.0, 0.0, 0.75),
            button_fg: hsla(0.0, 0.0, 1.0, 1.0),
            danger: hsla(0.01, 0.7, 0.45, 1.0),
            ok: hsla(0.35, 0.55, 0.35, 1.0),
            warn: hsla(0.07, 0.8, 0.42, 1.0),
        },
        Mode::Dark => Palette {
            card: hsla(0.0, 0.0, 0.09, 0.72),
            inner: hsla(0.0, 0.0, 1.0, 0.06),
            divider: hsla(0.0, 0.0, 1.0, 0.08),
            foreground: hsla(0.0, 0.0, 0.97, 1.0),
            muted: hsla(0.0, 0.0, 1.0, 0.52),
            badge_bg: hsla(0.0, 0.0, 1.0, 0.12),
            badge_fg: hsla(0.0, 0.0, 0.97, 1.0),
            button_bg: hsla(0.0, 0.0, 1.0, 0.16),
            button_fg: hsla(0.0, 0.0, 0.97, 1.0),
            danger: hsla(0.01, 0.85, 0.65, 1.0),
            ok: hsla(0.35, 0.6, 0.65, 1.0),
            warn: hsla(0.07, 0.85, 0.68, 1.0),
        },
    }
}

pub fn ui_font() -> gpui::Font {
    let mut f = gpui::font("Segoe UI Variable");
    f.fallbacks = Some(gpui::FontFallbacks::from_fonts(vec![
        "Segoe UI".into(),
        "Tahoma".into(),
    ]));
    f
}

#[allow(dead_code)]
pub fn glyphs_font() -> gpui::Font {
    let mut f = gpui::font("Segoe Fluent Icons");
    f.fallbacks = Some(gpui::FontFallbacks::from_fonts(vec![
        "Segoe MDL2 Assets".into(),
    ]));
    f
}
