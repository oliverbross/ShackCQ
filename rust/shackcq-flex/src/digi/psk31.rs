// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Oliver Bross
//
// BPSK31 modem. Varicode values and the pulse-shaped waveform model are
// derived from Scott Harden AJ4VD's MIT-licensed psk-experiments project.
// Provenance is recorded in ../../UPSTREAM.md.

use std::f32::consts::{PI, TAU};

const RATE: usize = 12_000;
const SYMBOL: usize = 384; // 12000 / 31.25
const DEFAULT_CARRIER: f32 = 1_000.0;

// Printable ASCII 32..126, in code-point order.
const VARICODE: [&str; 95] = [
    "1","111111111","101011111","111110101","111011011","1011010101","1010111011","101111111",
    "11111011","11110111","101101111","111011111","1110101","110101","1010111","110101111",
    "10110111","10111101","11101101","11111111","101110111","101011011","101101011","110101101",
    "110101011","110110111","11110101","110111101","111101101","1010101","111010111","1010101111",
    "1010111101","1111101","11101011","10101101","10110101","1110111","11011011","11111101",
    "101010101","1111111","111111101","101111101","11010111","10111011","11011101","10101011",
    "11010101","111011101","10101111","1101111","1101101","101010111","110110101","101011101",
    "101110101","101111011","1010101101","111110111","111101111","111111011","1010111111",
    "101101101","1011011111","1011","1011111","101111","101101","11","111101","1011011","101011",
    "1101","111101011","10111111","11011","111011","1111","111","111111","110111111","10101",
    "10111","101","110111","1111011","1101011","11011111","1011101","111010101","1010110111",
    "110111011","1010110101","1011010111",
];

fn code_for(ch: char) -> &'static str {
    let value = ch as usize;
    if (32..=126).contains(&value) { VARICODE[value - 32] } else { VARICODE[0] }
}

fn char_for(code: &str) -> Option<char> {
    VARICODE.iter().position(|value| *value == code).map(|index| (index as u8 + 32) as char)
}

pub fn encode(text: &str, carrier_hz: f32) -> Vec<f32> {
    let mut bits = vec![0_u8; 25];
    for ch in text.chars() {
        bits.extend(code_for(ch).bytes().map(|value| (value == b'1') as u8));
        bits.extend([0, 0]);
    }
    bits.extend([1_u8; 25]);
    let mut phases = Vec::with_capacity(bits.len());
    let mut phase = 0.0_f32;
    for bit in bits {
        if bit == 0 { phase = if phase == 0.0 { PI } else { 0.0 }; }
        phases.push(phase);
    }
    let mut out = vec![0.0_f32; phases.len() * SYMBOL];
    for (symbol, &phase) in phases.iter().enumerate() {
        let transition_before = symbol == 0 || phases[symbol - 1] != phase;
        let transition_after = symbol + 1 == phases.len() || phases[symbol + 1] != phase;
        for offset in 0..SYMBOL {
            let at = symbol * SYMBOL + offset;
            let mut gain = 0.55_f32;
            if transition_before && offset < SYMBOL / 2 {
                gain *= ((offset as f32 + 0.5) * PI / SYMBOL as f32).sin();
            }
            if transition_after && offset >= SYMBOL / 2 {
                gain *= (((SYMBOL - offset) as f32 - 0.5) * PI / SYMBOL as f32).sin();
            }
            out[at] = gain * (TAU * carrier_hz * at as f32 / RATE as f32 + phase).cos();
        }
    }
    out
}

fn carrier_score(samples: &[f32], frequency: f32) -> f32 {
    let window = samples.len().min(SYMBOL * 12);
    let mut total = 0.0_f32;
    for chunk in samples[..window].chunks(SYMBOL) {
        let mut re = 0.0_f32;
        let mut im = 0.0_f32;
        for (index, &sample) in chunk.iter().enumerate() {
            let phase = TAU * frequency * index as f32 / RATE as f32;
            re += sample * phase.cos();
            im -= sample * phase.sin();
        }
        total += re * re + im * im;
    }
    total
}

fn find_carrier(samples: &[f32]) -> f32 {
    (400..=2_600)
        .step_by(5)
        .map(|hz| (hz as f32, carrier_score(samples, hz as f32)))
        .max_by(|a, b| a.1.total_cmp(&b.1))
        .map(|value| value.0)
        .unwrap_or(DEFAULT_CARRIER)
}

fn symbol_phasor(samples: &[f32], start: usize, carrier_hz: f32) -> (f32, f32) {
    let mut re = 0.0_f32;
    let mut im = 0.0_f32;
    for index in SYMBOL / 8..SYMBOL * 7 / 8 {
        let at = start + index;
        if at >= samples.len() { break; }
        let phase = TAU * carrier_hz * at as f32 / RATE as f32;
        re += samples[at] * phase.cos();
        im -= samples[at] * phase.sin();
    }
    (re, im)
}

fn decode_bits(bits: &[u8]) -> (String, usize) {
    let mut text = String::new();
    let mut recognized = 0usize;
    let mut code = String::new();
    let mut zeros = 0;
    for &bit in bits {
        if bit == 0 {
            zeros += 1;
            if zeros == 2 {
                if let Some(ch) = char_for(&code) {
                    text.push(ch);
                    recognized += 1;
                }
                code.clear();
            }
        } else {
            if zeros == 1 { code.push('0'); }
            zeros = 0;
            code.push('1');
        }
    }
    (text.trim_matches(char::from(0)).to_string(), recognized)
}

/// Decode a complete recording. Timing phase is selected by maximizing valid
/// Varicode characters, which also makes imported recordings deterministic.
pub fn decode_at(samples: &[f32], selected_carrier: Option<f32>) -> (String, f32) {
    if samples.len() < SYMBOL * 4 { return (String::new(), DEFAULT_CARRIER); }
    let carrier = selected_carrier.filter(|value| value.is_finite() && (200.0..=3_500.0).contains(value))
        .unwrap_or_else(|| find_carrier(samples));
    let mut best = (String::new(), 0usize);
    for offset in (0..SYMBOL).step_by(8) {
        let mut phasors = Vec::new();
        let mut at = offset;
        while at + SYMBOL <= samples.len() {
            phasors.push(symbol_phasor(samples, at, carrier));
            at += SYMBOL;
        }
        let bits = phasors.windows(2).map(|pair| {
            let dot = pair[0].0 * pair[1].0 + pair[0].1 * pair[1].1;
            (dot >= 0.0) as u8
        }).collect::<Vec<_>>();
        let decoded = decode_bits(&bits);
        if decoded.1 > best.1 { best = decoded; }
    }
    (best.0, carrier)
}

pub fn decode(samples: &[f32]) -> (String, f32) { decode_at(samples, None) }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_bpsk31_round_trip() {
        let samples = encode("CQ OM0RX TEST 123", DEFAULT_CARRIER);
        let (text, carrier) = decode(&samples);
        assert!(text.contains("OM0RX"), "{text:?}");
        assert!((carrier - DEFAULT_CARRIER).abs() <= 5.0, "{carrier}");
    }

    #[test]
    fn public_mit_recording_decodes() {
        let Ok(path) = std::env::var("SHACKCQ_PSK31_WAV") else { return; };
        let bytes = std::fs::read(path).expect("read PSK31 WAV");
        let rate = u32::from_le_bytes(bytes[24..28].try_into().unwrap()) as usize;
        let channels = u16::from_le_bytes(bytes[22..24].try_into().unwrap()) as usize;
        let mut at = 12usize;
        let mut data = &[][..];
        while at + 8 <= bytes.len() {
            let size = u32::from_le_bytes(bytes[at + 4..at + 8].try_into().unwrap()) as usize;
            if &bytes[at..at + 4] == b"data" { data = &bytes[at + 8..at + 8 + size]; break; }
            at += 8 + size + (size & 1);
        }
        let interleaved = data.chunks_exact(2)
            .map(|v| i16::from_le_bytes([v[0], v[1]]) as f32 / 32768.0)
            .collect::<Vec<_>>();
        let mono = interleaved.chunks_exact(channels)
            .map(|frame| frame.iter().sum::<f32>() / channels as f32)
            .collect::<Vec<_>>();
        let output_len = mono.len() * RATE / rate;
        let pcm = (0..output_len).map(|index| {
            let position = index as f64 * rate as f64 / RATE as f64;
            let left = (position as usize).min(mono.len() - 1);
            let right = (left + 1).min(mono.len() - 1);
            mono[left] * (1.0 - (position - left as f64) as f32)
                + mono[right] * (position - left as f64) as f32
        }).collect::<Vec<_>>();
        let (text, carrier) = decode(&pcm);
        eprintln!("PSK31 fixture carrier={carrier}: {text}");
        assert!(text.to_lowercase().contains("quick brown fox"), "{text:?}");
    }
}
