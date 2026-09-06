// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Oliver Bross

use crate::flexcat::{self, FlexFramer, FlexState};
use crate::flexdisc;
use crate::digi::{self, CwStreamDecoder, RttyStreamDecoder};
use crate::digi::spectrum::WindowN;
use std::ffi::{c_char, CStr};
use tempo_sstv::{SstvDecoder, SstvEvent, SourceImage};
use tempo_sstv::modespec::{for_mode, SstvMode};

#[repr(C)]
pub struct FlexContext {
    framer: FlexFramer,
    state: FlexState,
}

#[repr(C)]
pub struct DigiContext {
    cw: CwStreamDecoder,
    rtty: RttyStreamDecoder,
    sstv: SstvDecoder,
    sstv_mode: Option<SstvMode>,
    sstv_line: i32,
    sstv_complete: bool,
    sstv_fsk_id: String,
    image_width: u32,
    image_height: u32,
    image_rgb: Vec<u8>,
}

fn sstv_mode(index: i32) -> Option<SstvMode> {
    Some(match index {
        0 => SstvMode::Pd50,
        1 => SstvMode::Pd90,
        2 => SstvMode::Pd120,
        3 => SstvMode::Pd160,
        4 => SstvMode::Pd180,
        5 => SstvMode::Pd240,
        6 => SstvMode::Pd290,
        7 => SstvMode::Robot24,
        8 => SstvMode::Robot36,
        9 => SstvMode::Robot72,
        10 => SstvMode::Scottie1,
        11 => SstvMode::Scottie2,
        12 => SstvMode::ScottieDx,
        13 => SstvMode::Martin1,
        14 => SstvMode::Martin2,
        _ => return None,
    })
}

fn mode_index(mode: SstvMode) -> i32 {
    match mode {
        SstvMode::Pd50 => 0, SstvMode::Pd90 => 1, SstvMode::Pd120 => 2,
        SstvMode::Pd160 => 3, SstvMode::Pd180 => 4, SstvMode::Pd240 => 5,
        SstvMode::Pd290 => 6, SstvMode::Robot24 => 7, SstvMode::Robot36 => 8,
        SstvMode::Robot72 => 9, SstvMode::Scottie1 => 10, SstvMode::Scottie2 => 11,
        SstvMode::ScottieDx => 12, SstvMode::Martin1 => 13, SstvMode::Martin2 => 14,
        _ => -1,
    }
}

fn copy_samples(samples: &[f32], output: *mut f32, capacity: usize) -> i32 {
    if output.is_null() || capacity == 0 {
        return samples.len().try_into().unwrap_or(i32::MAX);
    }
    let count = samples.len().min(capacity);
    unsafe { std::ptr::copy_nonoverlapping(samples.as_ptr(), output, count); }
    count.try_into().unwrap_or(i32::MAX)
}

fn copy_text(value: &str, output: *mut c_char, capacity: usize) -> i32 {
    if output.is_null() || capacity == 0 {
        return -1;
    }
    let bytes = value.as_bytes();
    let count = bytes.len().min(capacity - 1);
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), output.cast(), count);
        *output.add(count) = 0;
    }
    count as i32
}

fn input(value: *const c_char) -> Option<String> {
    (!value.is_null()).then(|| unsafe { CStr::from_ptr(value).to_string_lossy().into_owned() })
}

pub(crate) fn json_string(value: &str) -> String {
    format!(
        "\"{}\"",
        value
            .replace('\\', "\\\\")
            .replace('"', "\\\"")
            .replace('\n', "\\n")
    )
}

fn state_json(state: &FlexState) -> String {
    let clients = state.clients.values().map(|v| format!("{{\"handle\":{},\"clientId\":{},\"program\":{},\"station\":{},\"connected\":{},\"gui\":{}}}", v.handle, json_string(&v.client_id), json_string(&v.program), json_string(&v.station), v.connected, v.is_gui)).collect::<Vec<_>>().join(",");
    let slices = state.slices.values().map(|v| format!("{{\"index\":{},\"letter\":{},\"inUse\":{},\"active\":{},\"tx\":{},\"clientHandle\":{},\"frequencyHz\":{},\"mode\":{},\"filterLowHz\":{},\"filterHighHz\":{},\"rxAntenna\":{}}}", v.index, json_string(&v.letter), v.in_use, v.active, v.tx, v.client_handle.unwrap_or(0), v.frequency_hz, json_string(&v.mode), v.filter_low_hz, v.filter_high_hz, json_string(&v.rx_antenna))).collect::<Vec<_>>().join(",");
    format!("{{\"connected\":{},\"handle\":{},\"version\":{},\"model\":{},\"nickname\":{},\"callsign\":{},\"serial\":{},\"firmware\":{},\"clients\":[{}],\"slices\":[{}]}}", state.connected, state.handle, json_string(&state.identity.version), json_string(&state.identity.model), json_string(&state.identity.nickname), json_string(&state.identity.callsign), json_string(&state.identity.serial), json_string(&state.identity.firmware), clients, slices)
}

fn discovery_json(record: &flexdisc::DiscoveryRecord) -> String {
    format!("{{\"model\":{},\"nickname\":{},\"ip\":{},\"port\":{},\"serial\":{},\"callsign\":{},\"version\":{},\"status\":{},\"guiClientHandles\":{},\"guiClientPrograms\":{},\"guiClientStations\":{}}}",
        json_string(&record.model), json_string(&record.nickname), json_string(&record.ip), record.port,
        json_string(&record.serial), json_string(&record.callsign), json_string(&record.version), json_string(&record.status),
        json_string(&record.gui_client_handles), json_string(&record.gui_client_programs), json_string(&record.gui_client_stations))
}

#[no_mangle]
pub extern "C" fn shackcq_flex_context_create() -> *mut FlexContext {
    Box::into_raw(Box::new(FlexContext {
        framer: FlexFramer::default(),
        state: FlexState::default(),
    }))
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_context_destroy(context: *mut FlexContext) {
    if !context.is_null() {
        drop(Box::from_raw(context));
    }
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_context_feed(
    context: *mut FlexContext,
    bytes: *const u8,
    count: usize,
) -> i32 {
    let Some(context) = context.as_mut() else {
        return -1;
    };
    if bytes.is_null() {
        return -1;
    }
    let messages = context
        .framer
        .push(std::slice::from_raw_parts(bytes, count));
    let applied = messages.len() as i32;
    for message in &messages {
        context.state.apply(message);
    }
    applied
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_state_json(
    context: *const FlexContext,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    context
        .as_ref()
        .map(|v| copy_text(&state_json(&v.state), output, capacity))
        .unwrap_or(-1)
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_client_identity(
    program: *const c_char,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    input(program)
        .and_then(|v| flexcat::client_identity_command(&v))
        .map(|v| copy_text(&v, output, capacity))
        .unwrap_or(-1)
}
#[no_mangle]
pub extern "C" fn shackcq_flex_subscriptions(output: *mut c_char, capacity: usize) -> i32 {
    copy_text(&flexcat::subscriptions().join("\n"), output, capacity)
}
#[no_mangle]
pub extern "C" fn shackcq_flex_keepalive(output: *mut c_char, capacity: usize) -> i32 {
    copy_text(flexcat::keepalive_command(), output, capacity)
}
#[no_mangle]
pub extern "C" fn shackcq_flex_frequency(
    slice: u32,
    hz: u64,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    flexcat::frequency_command(slice, hz)
        .map(|v| copy_text(&v, output, capacity))
        .unwrap_or(-1)
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_mode(
    slice: u32,
    mode: *const c_char,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    input(mode)
        .and_then(|v| flexcat::mode_command(slice, &v))
        .map(|v| copy_text(&v, output, capacity))
        .unwrap_or(-1)
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_filter(
    letter: *const c_char,
    low: i32,
    high: i32,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    input(letter)
        .and_then(|v| flexcat::filter_command(&v, low, high))
        .map(|v| copy_text(&v, output, capacity))
        .unwrap_or(-1)
}
#[no_mangle]
pub unsafe extern "C" fn shackcq_flex_parse_discovery(
    bytes: *const u8,
    count: usize,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    if bytes.is_null() {
        return -1;
    }
    flexdisc::parse_discovery(std::slice::from_raw_parts(bytes, count))
        .map(|v| copy_text(&discovery_json(&v), output, capacity))
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn shackcq_digi_context_create(
    sample_rate: u32,
    cw_pitch_hz: f32,
    rtty_reverse: bool,
    rtty_centre_hz: f32,
) -> *mut DigiContext {
    if sample_rate == 0 {
        return std::ptr::null_mut();
    }
    let Ok(sstv) = SstvDecoder::new(sample_rate) else {
        return std::ptr::null_mut();
    };
    Box::into_raw(Box::new(DigiContext {
        cw: CwStreamDecoder::new(sample_rate as f32, cw_pitch_hz),
        rtty: RttyStreamDecoder::new_at(rtty_reverse, rtty_centre_hz),
        sstv,
        sstv_mode: None,
        sstv_line: -1,
        sstv_complete: false,
        sstv_fsk_id: String::new(),
        image_width: 0,
        image_height: 0,
        image_rgb: Vec::new(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_context_destroy(context: *mut DigiContext) {
    if !context.is_null() {
        drop(Box::from_raw(context));
    }
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_feed_cw(
    context: *mut DigiContext,
    samples: *const f32,
    count: usize,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    let (Some(context), false) = (context.as_mut(), samples.is_null()) else { return -1; };
    context.cw.push(std::slice::from_raw_parts(samples, count));
    copy_text(
        &format!("{{\"text\":{},\"wpm\":{}}}", json_string(context.cw.transcript()), context.cw.wpm()),
        output,
        capacity,
    )
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_feed_rtty(
    context: *mut DigiContext,
    samples: *const f32,
    count: usize,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    let (Some(context), false) = (context.as_mut(), samples.is_null()) else { return -1; };
    let text = context.rtty.push(std::slice::from_raw_parts(samples, count)).to_string();
    copy_text(
        &format!(
            "{{\"text\":{},\"afcHz\":{:.2},\"locked\":{}}}",
            json_string(&text), context.rtty.afc_offset_hz(), context.rtty.afc_locked()
        ),
        output,
        capacity,
    )
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_feed_sstv(
    context: *mut DigiContext,
    samples: *const f32,
    count: usize,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    let (Some(context), false) = (context.as_mut(), samples.is_null()) else { return -1; };
    for event in context.sstv.process(std::slice::from_raw_parts(samples, count)) {
        match event {
            SstvEvent::VisDetected { mode, .. } => {
                let spec = for_mode(mode);
                context.sstv_mode = Some(mode);
                context.sstv_line = -1;
                context.sstv_complete = false;
                context.sstv_fsk_id.clear();
                context.image_width = spec.line_pixels;
                context.image_height = spec.image_lines;
                context.image_rgb = vec![0; spec.line_pixels as usize * spec.image_lines as usize * 3];
            }
            SstvEvent::LineDecoded { mode, line_index, pixels } => {
                context.sstv_mode = Some(mode);
                context.sstv_line = line_index as i32;
                let width = context.image_width as usize;
                let start = line_index as usize * width * 3;
                for (offset, pixel) in pixels.iter().take(width).enumerate() {
                    let at = start + offset * 3;
                    if at + 2 < context.image_rgb.len() {
                        context.image_rgb[at..at + 3].copy_from_slice(pixel);
                    }
                }
            }
            SstvEvent::ImageComplete { image, .. } => {
                context.sstv_mode = Some(image.mode);
                context.image_width = image.width;
                context.image_height = image.height;
                context.image_rgb = image.pixels.iter().flatten().copied().collect();
                context.sstv_complete = true;
            }
            SstvEvent::FskId { text } => context.sstv_fsk_id = text,
            SstvEvent::UnknownVis { .. } => {}
            _ => {}
        }
    }
    copy_text(
        &format!(
            "{{\"mode\":{},\"line\":{},\"complete\":{},\"width\":{},\"height\":{},\"fskId\":{}}}",
            context.sstv_mode.map(mode_index).unwrap_or(-1), context.sstv_line,
            context.sstv_complete, context.image_width, context.image_height,
            json_string(&context.sstv_fsk_id)
        ),
        output,
        capacity,
    )
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_decode_slot(
    mode: i32,
    samples: *const f32,
    count: usize,
    sample_rate: u32,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    if samples.is_null() {
        return -1;
    }
    copy_text(
        &digi::wsjt::decode(mode, std::slice::from_raw_parts(samples, count), sample_rate),
        output,
        capacity,
    )
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_spectrum(
    samples: *const f32,
    count: usize,
    sample_rate: u32,
    low_hz: f32,
    high_hz: f32,
    bins: usize,
    window: i32,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    if samples.is_null() || sample_rate == 0 || bins == 0 || bins > 512 ||
        !low_hz.is_finite() || !high_hz.is_finite() || low_hz < 0.0 || high_hz <= low_hz ||
        high_hz > sample_rate as f32 / 2.0
    {
        return -1;
    }
    let window = match window {
        0 => WindowN::Fast,
        1 => WindowN::Balanced,
        2 => WindowN::Sharp,
        _ => return -1,
    };
    let row = digi::spectrum::power_spectrum_n(
        std::slice::from_raw_parts(samples, count), sample_rate as f32,
        low_hz, high_hz, bins, window,
    );
    copy_samples(&row, output, capacity)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_encode_slot(
    mode: i32,
    text: *const c_char,
    base_hz: f32,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    input(text)
        .and_then(|text| digi::wsjt::encode(mode, &text, base_hz))
        .map(|samples| copy_samples(&samples, output, capacity))
        .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_decode_psk31(
    samples: *const f32,
    count: usize,
    carrier_hz: f32,
    output: *mut c_char,
    capacity: usize,
) -> i32 {
    if samples.is_null() { return -1; }
    let (text, carrier) = digi::psk31::decode_at(std::slice::from_raw_parts(samples, count), Some(carrier_hz));
    copy_text(
        &format!("{{\"text\":{},\"carrierHz\":{carrier:.1}}}", json_string(&text)),
        output,
        capacity,
    )
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_encode_psk31(
    text: *const c_char,
    carrier_hz: f32,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    input(text)
        .map(|text| copy_samples(&digi::psk31::encode(&text, carrier_hz), output, capacity))
        .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_copy_sstv_image(
    context: *const DigiContext,
    output: *mut u8,
    capacity: usize,
) -> i32 {
    let Some(context) = context.as_ref() else { return -1; };
    if output.is_null() || capacity == 0 {
        return context.image_rgb.len().try_into().unwrap_or(i32::MAX);
    }
    let count = context.image_rgb.len().min(capacity);
    std::ptr::copy_nonoverlapping(context.image_rgb.as_ptr(), output, count);
    count.try_into().unwrap_or(i32::MAX)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_encode_cw(
    text: *const c_char,
    wpm: u32,
    pitch_hz: f32,
    sample_rate: u32,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    input(text)
        .map(|text| copy_samples(&digi::morse_samples(&text, wpm, pitch_hz, sample_rate), output, capacity))
        .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_encode_rtty(
    text: *const c_char,
    sample_rate: u32,
    reverse: bool,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    input(text)
        .map(|text| copy_samples(&digi::rtty_samples(&text, sample_rate, reverse), output, capacity))
        .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn shackcq_digi_encode_sstv(
    mode: i32,
    rgb: *const u8,
    width: u32,
    height: u32,
    sample_rate: u32,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    let (Some(mode), false) = (sstv_mode(mode), rgb.is_null()) else { return -1; };
    let Some(pixel_count) = (width as usize).checked_mul(height as usize) else { return -1; };
    let bytes = std::slice::from_raw_parts(rgb, pixel_count.saturating_mul(3));
    let pixels = bytes.chunks_exact(3).map(|p| [p[0], p[1], p[2]]).collect();
    tempo_sstv::encode_image(mode, &SourceImage { width, height, rgb: pixels }, sample_rate)
        .map(|samples| copy_samples(&samples, output, capacity))
        .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;

    #[test]
    fn c_abi_round_trip_owns_context_and_output() {
        let context = shackcq_flex_context_create();
        let input =
            b"V3.8.23\nH2ABC\nS0|slice 0 in_use=1 active=1 tx=0 RF_frequency=14.074 mode=DIGU\n";
        let applied = unsafe { shackcq_flex_context_feed(context, input.as_ptr(), input.len()) };
        assert_eq!(applied, 3);
        let mut output = [0_i8; 4096];
        assert!(unsafe { shackcq_flex_state_json(context, output.as_mut_ptr(), output.len()) } > 0);
        let text = unsafe { CStr::from_ptr(output.as_ptr()) }.to_string_lossy();
        assert!(text.contains("\"handle\":10940"));
        assert!(text.contains("\"frequencyHz\":14074000"));
        unsafe { shackcq_flex_context_destroy(context) };
    }

    #[test]
    fn digital_c_abi_generates_and_decodes_cw() {
        let text = std::ffi::CString::new("CQ TEST").unwrap();
        let count = unsafe { shackcq_digi_encode_cw(text.as_ptr(), 20, 700.0, 12_000, std::ptr::null_mut(), 0) };
        assert!(count > 10_000);
        let mut samples = vec![0.0_f32; count as usize];
        assert_eq!(
            unsafe { shackcq_digi_encode_cw(text.as_ptr(), 20, 700.0, 12_000, samples.as_mut_ptr(), samples.len()) },
            count
        );
        let context = shackcq_digi_context_create(12_000, 700.0, false, 2_210.0);
        assert!(!context.is_null());
        let mut padded = vec![0.0_f32; 1_200];
        padded.extend(samples);
        padded.extend(vec![0.0_f32; 7_200]);
        let mut output = [0_i8; 4096];
        assert!(unsafe {
            shackcq_digi_feed_cw(context, padded.as_ptr(), padded.len(), output.as_mut_ptr(), output.len())
        } > 0);
        let decoded = unsafe { CStr::from_ptr(output.as_ptr()) }.to_string_lossy();
        assert!(decoded.contains("CQ TEST"));
        unsafe { shackcq_digi_context_destroy(context) };
    }

    #[test]
    fn digital_c_abi_rtty_loopback_and_sstv_dimension_gate() {
        let text = std::ffi::CString::new("CQ OM0RX 599").unwrap();
        let count = unsafe { shackcq_digi_encode_rtty(text.as_ptr(), 12_000, false, std::ptr::null_mut(), 0) };
        assert!(count > 1_000);
        let mut samples = vec![0.0_f32; count as usize];
        unsafe { shackcq_digi_encode_rtty(text.as_ptr(), 12_000, false, samples.as_mut_ptr(), samples.len()) };
        let context = shackcq_digi_context_create(12_000, 700.0, false, 2_210.0);
        let mut output = [0_i8; 4096];
        unsafe { shackcq_digi_feed_rtty(context, samples.as_ptr(), samples.len(), output.as_mut_ptr(), output.len()) };
        let decoded = unsafe { CStr::from_ptr(output.as_ptr()) }.to_string_lossy();
        assert!(decoded.contains("OM0RX"));
        let bad_rgb = [0_u8; 3];
        assert_eq!(
            unsafe { shackcq_digi_encode_sstv(2, bad_rgb.as_ptr(), 1, 1, 12_000, std::ptr::null_mut(), 0) },
            -1
        );
        unsafe { shackcq_digi_context_destroy(context) };
    }

    #[test]
    fn digital_spectrum_c_abi_is_bounded_and_monotonic_in_level() {
        let quiet = vec![0.01_f32; 4096];
        let loud = (0..4096).map(|n| {
            (std::f32::consts::TAU * 1_000.0 * n as f32 / 12_000.0).sin() * 0.8
        }).collect::<Vec<_>>();
        let mut quiet_row = [0.0_f32; 384];
        let mut loud_row = [0.0_f32; 384];
        assert_eq!(unsafe { shackcq_digi_spectrum(quiet.as_ptr(), quiet.len(), 12_000, 0.0, 3_000.0,
            quiet_row.len(), 1, quiet_row.as_mut_ptr(), quiet_row.len()) }, 384);
        assert_eq!(unsafe { shackcq_digi_spectrum(loud.as_ptr(), loud.len(), 12_000, 0.0, 3_000.0,
            loud_row.len(), 1, loud_row.as_mut_ptr(), loud_row.len()) }, 384);
        assert!(loud_row.iter().copied().fold(0.0_f32, f32::max) >
            quiet_row.iter().copied().fold(0.0_f32, f32::max));
        assert!(loud_row.iter().all(|value| (0.0..=1.0).contains(value)));
    }

    #[test]
    fn native_context_lifecycle_stress_returns_every_owner() {
        for _ in 0..500 {
            let flex = shackcq_flex_context_create();
            assert!(!flex.is_null());
            unsafe { shackcq_flex_context_destroy(flex) };

            let digi = shackcq_digi_context_create(12_000, 700.0, false, 2_210.0);
            assert!(!digi.is_null());
            unsafe { shackcq_digi_context_destroy(digi) };
        }
    }
}
