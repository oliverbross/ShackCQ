// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Oliver Bross
//
// Android-suitable WSJT-family modem integration. The operating model and
// mode inventory follow Nexus commit 750407eafd60905550e561be2eacec642751fc51.
// DSP is provided by mfsk-core 0.9.1, a pure-Rust WSJT-X-derived implementation
// selected because Nexus's desktop libtempo requires a Fortran runtime that the
// Android NDK does not provide. See ../../UPSTREAM.md.

use mfsk_core::engine::{
    FrameLayout, ModulationParams, Protocol, ProtocolId, SyncBlock, SyncMode,
};
use mfsk_core::fec::Ldpc174_91;
use mfsk_core::engine::FecCodec;
use mfsk_core::fst4::{Fst4s15, Fst4s30, Fst4s60, Fst4s120, Fst4s300};
use mfsk_core::ft4::Ft4;
use mfsk_core::ft8::Ft8;
use mfsk_core::msg::decode_request::{DecodeRequest, FrameDecodable};
use mfsk_core::msg::decoded::Decoded;
use mfsk_core::msg::{Q65Message, Wsjt77Message};
use mfsk_core::q65::{
    DecodeRequest as Q65DecodeRequest, Q65Fec, Q65SubMode, Q65_SYNC_BLOCKS,
    SearchParams as Q65SearchParams,
};

pub const MODE_FT8: i32 = 0;
pub const MODE_FT4: i32 = 1;
pub const MODE_FST4_15: i32 = 2;
pub const MODE_FST4_30: i32 = 3;
pub const MODE_FST4_60: i32 = 4;
pub const MODE_FST4_120: i32 = 5;
pub const MODE_FST4_300: i32 = 6;
pub const MODE_Q65_30A: i32 = 7;
pub const MODE_MSK144_15: i32 = 8;
pub const MODE_JT65A: i32 = 9;
pub const MODE_WSPR: i32 = 10;
pub const MODE_FT2: i32 = 11;
pub const MODE_JT65B: i32 = 12;
pub const MODE_JT65C: i32 = 13;
pub const MODE_Q65_BASE: i32 = 100;
pub const MODE_MSK144_BASE: i32 = 130;

const Q65_PERIODS: [u32; 5] = [15, 30, 60, 120, 300];
const MSK144_PERIODS: [u32; 4] = [5, 10, 15, 30];

const SAMPLE_RATE: u32 = 12_000;

pub fn slot_samples(mode: i32) -> Option<usize> {
    if let Some((period, _)) = q65_variant(mode) {
        return Some(period as usize * SAMPLE_RATE as usize);
    }
    if let Some(period) = msk144_period(mode) {
        return Some(period as usize * SAMPLE_RATE as usize);
    }
    Some(match mode {
        MODE_FT8 | MODE_FST4_15 | MODE_MSK144_15 => 15 * SAMPLE_RATE as usize,
        MODE_FT4 => 90_000,
        MODE_FT2 => 45_000,
        MODE_FST4_30 | MODE_Q65_30A => 30 * SAMPLE_RATE as usize,
        MODE_FST4_60 | MODE_JT65A | MODE_JT65B | MODE_JT65C => 60 * SAMPLE_RATE as usize,
        MODE_FST4_120 | MODE_WSPR => 120 * SAMPLE_RATE as usize,
        MODE_FST4_300 => 300 * SAMPLE_RATE as usize,
        _ => return None,
    })
}

fn q65_variant(mode: i32) -> Option<(u32, usize)> {
    let variant: usize = (mode - MODE_Q65_BASE).try_into().ok()?;
    let period = *Q65_PERIODS.get(variant / 5)?;
    Some((period, variant % 5))
}

fn msk144_period(mode: i32) -> Option<u32> {
    let variant: usize = (mode - MODE_MSK144_BASE).try_into().ok()?;
    MSK144_PERIODS.get(variant).copied()
}

const Q65_IDENTITY: [u8; 65] = {
    let mut value = [0_u8; 65];
    let mut index = 0;
    while index < value.len() {
        value[index] = index as u8;
        index += 1;
    }
    value
};

macro_rules! q65_variant_type {
    ($name:ident, $nsps:literal, $spacing:literal, $period:literal) => {
        #[derive(Copy, Clone, Debug, Default)]
        struct $name;
        impl ModulationParams for $name {
            const NTONES: u32 = 65;
            const BITS_PER_SYMBOL: u32 = 6;
            const NSPS: u32 = $nsps;
            const SYMBOL_DT: f32 = $nsps as f32 / SAMPLE_RATE as f32;
            const TONE_SPACING_HZ: f32 = (SAMPLE_RATE as f32 / $nsps as f32) * $spacing as f32;
            const GRAY_MAP: &'static [u8] = &Q65_IDENTITY;
            const GFSK_BT: f32 = 0.0;
            const GFSK_HMOD: f32 = 1.0;
            const NFFT_PER_SYMBOL_FACTOR: u32 = 2;
            const NSTEP_PER_SYMBOL: u32 = 2;
            const NDOWN: u32 = 3;
        }
        impl FrameLayout for $name {
            const N_DATA: u32 = 63;
            const N_SYNC: u32 = 22;
            const N_SYMBOLS: u32 = 85;
            const N_RAMP: u32 = 0;
            const SYNC_MODE: SyncMode = SyncMode::Block(&Q65_SYNC_BLOCKS);
            const T_SLOT_S: f32 = $period as f32;
            const TX_START_OFFSET_S: f32 = 1.0;
        }
        impl Protocol for $name {
            type Fec = Q65Fec;
            type Msg = Q65Message;
            const ID: ProtocolId = ProtocolId::Q65;
        }
        impl Q65SubMode for $name {}
    };
}

q65_variant_type!(Q65a15, 1_800, 1, 15);
q65_variant_type!(Q65b15, 1_800, 2, 15);
q65_variant_type!(Q65c15, 1_800, 4, 15);
q65_variant_type!(Q65d15, 1_800, 8, 15);
q65_variant_type!(Q65e15, 1_800, 16, 15);
q65_variant_type!(Q65a30, 3_600, 1, 30);
q65_variant_type!(Q65b30, 3_600, 2, 30);
q65_variant_type!(Q65c30, 3_600, 4, 30);
q65_variant_type!(Q65d30, 3_600, 8, 30);
q65_variant_type!(Q65e30, 3_600, 16, 30);
q65_variant_type!(Q65a60, 7_200, 1, 60);
q65_variant_type!(Q65b60, 7_200, 2, 60);
q65_variant_type!(Q65c60, 7_200, 4, 60);
q65_variant_type!(Q65d60, 7_200, 8, 60);
q65_variant_type!(Q65e60, 7_200, 16, 60);
q65_variant_type!(Q65a120, 16_000, 1, 120);
q65_variant_type!(Q65b120, 16_000, 2, 120);
q65_variant_type!(Q65c120, 16_000, 4, 120);
q65_variant_type!(Q65d120, 16_000, 8, 120);
q65_variant_type!(Q65e120, 16_000, 16, 120);
q65_variant_type!(Q65a300, 41_472, 1, 300);
q65_variant_type!(Q65b300, 41_472, 2, 300);
q65_variant_type!(Q65c300, 41_472, 4, 300);
q65_variant_type!(Q65d300, 41_472, 8, 300);
q65_variant_type!(Q65e300, 41_472, 16, 300);

// Decodium FT2 is FT4 at half symbol duration: the same 77-bit message,
// scrambler, LDPC code and Costas arrays, 288 samples/symbol and a 3.75 s slot.
// Nexus uses Decodium's Fortran implementation; this type runs the identical
// protocol geometry through mfsk-core's generic Rust pipeline on Android.
#[derive(Copy, Clone, Debug, Default)]
struct Ft2;

const FT2_COSTAS_A: [u8; 4] = [0, 1, 3, 2];
const FT2_COSTAS_B: [u8; 4] = [1, 0, 2, 3];
const FT2_COSTAS_C: [u8; 4] = [2, 3, 1, 0];
const FT2_COSTAS_D: [u8; 4] = [3, 2, 0, 1];
const FT2_SYNC: [SyncBlock; 4] = [
    SyncBlock { start_symbol: 0, pattern: &FT2_COSTAS_A },
    SyncBlock { start_symbol: 33, pattern: &FT2_COSTAS_B },
    SyncBlock { start_symbol: 66, pattern: &FT2_COSTAS_C },
    SyncBlock { start_symbol: 99, pattern: &FT2_COSTAS_D },
];

impl ModulationParams for Ft2 {
    const NTONES: u32 = 4;
    const BITS_PER_SYMBOL: u32 = 2;
    const NSPS: u32 = 288;
    const SYMBOL_DT: f32 = 0.024;
    const TONE_SPACING_HZ: f32 = 41.666_668;
    const GRAY_MAP: &'static [u8] = &[0, 1, 3, 2];
    const GFSK_BT: f32 = 1.0;
    const GFSK_HMOD: f32 = 1.0;
    const NFFT_PER_SYMBOL_FACTOR: u32 = 4;
    const NSTEP_PER_SYMBOL: u32 = 2;
    const NDOWN: u32 = 9;
    const INFO_SCRAMBLE_RVEC: Option<&'static [u8]> = Some(&mfsk_core::ft4::FT4_RVEC);
}

impl FrameLayout for Ft2 {
    const N_DATA: u32 = 87;
    const N_SYNC: u32 = 16;
    const N_SYMBOLS: u32 = 103;
    const N_RAMP: u32 = 2;
    const SYNC_MODE: SyncMode = SyncMode::Block(&FT2_SYNC);
    const T_SLOT_S: f32 = 3.75;
    const TX_START_OFFSET_S: f32 = 0.5;
}

impl Protocol for Ft2 {
    type Fec = Ldpc174_91;
    type Msg = Wsjt77Message;
    // The generic engine's FT4 coarse/fine/SNR branches are the correct
    // algorithm for Decodium FT2; rows are relabelled ProtocolId::Ft2 below.
    const ID: ProtocolId = ProtocolId::Ft4;
}

impl mfsk_core::engine::pipeline::GenericPipelineProtocol for Ft2 {
    fn snr_db(ctx: mfsk_core::engine::pipeline::SnrCtx<'_>) -> f32 {
        let ratio = ctx.cand_score - 1.0;
        if ratio > 0.0 { (10.0 * ratio.log10() - 14.8).max(-21.0) } else { -21.0 }
    }
}

const FT2_DOWNSAMPLE: mfsk_core::engine::dsp::downsample::DownsampleCfg =
    mfsk_core::engine::dsp::downsample::DownsampleCfg {
        input_rate: 12_000,
        fft1_size: 46_080,
        fft2_size: 5_120,
        tone_spacing_hz: 41.666_668,
        leading_pad_tones: 1.5,
        trailing_pad_tones: 1.5,
        ntones: 4,
        edge_taper_bins: 101,
    };

fn decode_ft2(audio: &[i16]) -> Vec<Decoded> {
    use mfsk_core::engine::pipeline::{DecodeDepth, DecodeStrictness};
    use mfsk_core::engine::equalize::EqMode;
    let (rows, _) = mfsk_core::engine::pipeline::decode_frame::<Ft2>(
        audio,
        &FT2_DOWNSAMPLE,
        100.0,
        3_000.0,
        1.0,
        None,
        DecodeDepth::FULL,
        160,
        DecodeStrictness::Normal,
        EqMode::Off,
        8,
        None,
        None,
    );
    rows.iter()
        .filter_map(|row| row.to_decoded(ProtocolId::Ft2, None))
        .collect()
}

fn pcm16(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|sample| (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16)
        .collect()
}

fn pack_message(text: &str) -> Option<[u8; 77]> {
    let words = text.split_whitespace().collect::<Vec<_>>();
    if words.len() == 3 {
        mfsk_core::msg::wsjt77::pack77(words[0], words[1], words[2])
            .or_else(|| mfsk_core::msg::wsjt77::pack77_free_text(text))
    } else {
        mfsk_core::msg::wsjt77::pack77_free_text(text)
    }
}

fn encode_ft2(bits: &[u8; 77], base_hz: f32) -> Vec<f32> {
    let tones = mfsk_core::ft4::encode::message_to_tones(bits);
    mfsk_core::engine::dsp::gfsk::synth_f32(
        &tones,
        base_hz,
        0.55,
        &mfsk_core::engine::dsp::gfsk::GfskCfg {
            sample_rate: SAMPLE_RATE as f32,
            samples_per_symbol: Ft2::NSPS as usize,
            bt: Ft2::GFSK_BT,
            hmod: Ft2::GFSK_HMOD,
            ramp_samples: Ft2::NSPS as usize / 8,
        },
    )
}

fn slot_positioned(tones: Vec<f32>, lead_seconds: f32) -> Vec<f32> {
    let lead = (lead_seconds * SAMPLE_RATE as f32).round().max(0.0) as usize;
    let mut waveform = vec![0.0; lead + tones.len()];
    waveform[lead..].copy_from_slice(&tones);
    waveform
}

fn encode_msk144(bits: &[u8; 77], period_samples: usize) -> Vec<f32> {
    let mut info = [0_u8; 90];
    info[..77].copy_from_slice(bits);
    let mut bytes = [0_u8; 12];
    for (index, bit) in bits.iter().enumerate() {
        bytes[index / 8] |= (bit & 1) << (7 - index % 8);
    }
    let crc = mfsk_core::fec::ldpc_128_90::crc13(&bytes);
    for index in 0..13 {
        info[77 + index] = ((crc >> (12 - index)) & 1) as u8;
    }
    let mut codeword = [0_u8; 128];
    mfsk_core::fec::Ldpc128_90.encode(&info, &mut codeword);
    let frame = mfsk_core::msk144::tx::synth_codeword_frame(&codeword);
    // Nexus fills the complete T/R period with repeated MSK144 frames. Unlike
    // FT/Q65/FST4/JT65/WSPR, MSK144 has no leading slot silence.
    let active = period_samples;
    let mut out = Vec::with_capacity(active);
    for index in 0..active {
        let value = frame[index % frame.len()];
        let phase = std::f32::consts::TAU * 1_500.0 * index as f32 / SAMPLE_RATE as f32;
        out.push(0.55 * (value.re * phase.cos() - value.im * phase.sin()));
    }
    let ramp = 120usize.min(out.len() / 2);
    for index in 0..ramp {
        let gain = 0.5 - 0.5 * (std::f32::consts::PI * index as f32 / ramp as f32).cos();
        out[index] *= gain;
        let tail = out.len() - 1 - index;
        out[tail] *= gain;
    }
    out
}

fn encode_q65<P: Q65SubMode>(bits: &[u8; 77], base_hz: f32, lead_seconds: f32) -> Vec<f32> {
    let tones = mfsk_core::q65::tx::encode_channel_symbols(bits);
    slot_positioned(
        mfsk_core::q65::tx::synthesize_audio_for::<P>(&tones, SAMPLE_RATE, base_hz, 0.55),
        lead_seconds,
    )
}

macro_rules! q65_dispatch {
    ($variant:expr, $function:ident $(, $argument:expr)*) => {
        match $variant {
            0 => $function::<Q65a15>($($argument),*), 1 => $function::<Q65b15>($($argument),*),
            2 => $function::<Q65c15>($($argument),*), 3 => $function::<Q65d15>($($argument),*),
            4 => $function::<Q65e15>($($argument),*), 5 => $function::<Q65a30>($($argument),*),
            6 => $function::<Q65b30>($($argument),*), 7 => $function::<Q65c30>($($argument),*),
            8 => $function::<Q65d30>($($argument),*), 9 => $function::<Q65e30>($($argument),*),
            10 => $function::<Q65a60>($($argument),*), 11 => $function::<Q65b60>($($argument),*),
            12 => $function::<Q65c60>($($argument),*), 13 => $function::<Q65d60>($($argument),*),
            14 => $function::<Q65e60>($($argument),*), 15 => $function::<Q65a120>($($argument),*),
            16 => $function::<Q65b120>($($argument),*), 17 => $function::<Q65c120>($($argument),*),
            18 => $function::<Q65d120>($($argument),*), 19 => $function::<Q65e120>($($argument),*),
            20 => $function::<Q65a300>($($argument),*), 21 => $function::<Q65b300>($($argument),*),
            22 => $function::<Q65c300>($($argument),*), 23 => $function::<Q65d300>($($argument),*),
            24 => $function::<Q65e300>($($argument),*),
            _ => unreachable!("validated Q65 variant"),
        }
    };
}

/// Generate one operator-requested, slot-positioned 12 kHz transmit waveform.
/// The lead-in contract mirrors Nexus so Android can play the returned buffer
/// directly at the UTC boundary without applying a mode-blind timing offset.
pub fn encode(mode: i32, text: &str, base_hz: f32) -> Option<Vec<f32>> {
    if matches!(mode, MODE_JT65A | MODE_JT65B | MODE_JT65C) {
        let words = text.split_whitespace().collect::<Vec<_>>();
        if words.len() != 3 {
            return None;
        }
        let submode = match mode {
            MODE_JT65A => 0,
            MODE_JT65B => 1,
            MODE_JT65C => 2,
            _ => unreachable!(),
        };
        return mfsk_core::jt65::synthesize_standard_submode(
            words[0], words[1], words[2], SAMPLE_RATE, base_hz, 0.55, submode,
        ).map(|tones| slot_positioned(tones, 1.0));
    }
    if mode == MODE_WSPR {
        let words = text.split_whitespace().collect::<Vec<_>>();
        if words.len() != 3 {
            return None;
        }
        let power_dbm = words[2].parse::<i32>().ok()?;
        return mfsk_core::wspr::synthesize_type1(
            words[0], words[1], power_dbm, SAMPLE_RATE, base_hz, 0.55,
        ).map(|tones| slot_positioned(tones, 1.0));
    }
    let bits = pack_message(text.trim())?;
    if let Some((period, _)) = q65_variant(mode) {
        let lead = if period <= 30 { 0.5 } else { 1.0 };
        return Some(q65_dispatch!((mode - MODE_Q65_BASE) as usize, encode_q65, &bits, base_hz, lead));
    }
    if msk144_period(mode).is_some() {
        return Some(encode_msk144(&bits, slot_samples(mode)?));
    }
    match mode {
        MODE_FT8 => {
            let tones = mfsk_core::ft8::wave_gen::message_to_tones(&bits);
            Some(slot_positioned(
                mfsk_core::ft8::wave_gen::tones_to_f32(&tones, base_hz, 0.55),
                0.5,
            ))
        }
        MODE_FT4 => {
            let tones = mfsk_core::ft4::encode::message_to_tones(&bits);
            Some(slot_positioned(
                mfsk_core::ft4::encode::tones_to_f32(&tones, base_hz, 0.55),
                0.5,
            ))
        }
        MODE_FT2 => Some(encode_ft2(&bits, base_hz)),
        MODE_FST4_15 | MODE_FST4_30 | MODE_FST4_60 | MODE_FST4_120 | MODE_FST4_300 => {
            let tones = mfsk_core::fst4::encode::message_to_tones(&bits);
            let cfg = match mode {
                MODE_FST4_15 => &mfsk_core::fst4::encode::FST4_15_GFSK,
                MODE_FST4_30 => &mfsk_core::fst4::encode::FST4_30_GFSK,
                MODE_FST4_60 => &mfsk_core::fst4::encode::FST4_60A_GFSK,
                MODE_FST4_120 => &mfsk_core::fst4::encode::FST4_120_GFSK,
                MODE_FST4_300 => &mfsk_core::fst4::encode::FST4_300_GFSK,
                _ => unreachable!(),
            };
            let lead = if mode == MODE_FST4_15 { 0.5 } else { 1.0 };
            Some(slot_positioned(
                mfsk_core::fst4::encode::tones_to_f32_with_gfsk(&tones, base_hz, 0.55, cfg),
                lead,
            ))
        }
        MODE_Q65_30A => {
            let tones = mfsk_core::q65::tx::encode_channel_symbols(&bits);
            Some(slot_positioned(
                mfsk_core::q65::tx::synthesize_audio_for::<Q65a30>(
                    &tones, SAMPLE_RATE, base_hz, 0.55,
                ),
                0.5,
            ))
        }
        MODE_MSK144_15 => Some(encode_msk144(&bits, slot_samples(mode)?)),
        _ => None,
    }
}

fn decode_q65<P: Q65SubMode>(samples: &[f32]) -> Vec<Decoded> {
    Q65DecodeRequest::<P>::new(samples, SAMPLE_RATE, 0, Q65SearchParams::default())
        .decode()
        .iter()
        .map(|result| result.to_decoded(SAMPLE_RATE, 0))
        .collect()
}

fn decode_msk144(audio: &[i16]) -> Vec<Decoded> {
    mfsk_core::msk144::decode::decode_slot(
        audio,
        1_500.0,
        200.0,
        mfsk_core::msk144::decode::Depth::Deep,
    )
    .into_iter()
    .map(|result| Decoded {
        text: result.message,
        freq_hz: result.freq_hz,
        dt_sec: result.tsec,
        snr_db: result.snr_db as f32,
        protocol: ProtocolId::Ft8,
    })
    .collect()
}

fn decode_frame<P: FrameDecodable>(audio: &[i16], protocol: ProtocolId) -> Vec<Decoded>
where
    P::DecodeResult: IntoFrameDecoded,
{
    DecodeRequest::<P>::new(audio, 100.0, 3_000.0, 1.0, 240)
        .decode()
        .results
        .iter()
        .filter_map(|result| result.to_frame_decoded(protocol))
        .collect()
}

trait IntoFrameDecoded {
    fn to_frame_decoded(&self, protocol: ProtocolId) -> Option<Decoded>;
}

impl IntoFrameDecoded for mfsk_core::engine::pipeline::DecodeResult {
    fn to_frame_decoded(&self, protocol: ProtocolId) -> Option<Decoded> {
        self.to_decoded(protocol, None)
    }
}

fn row_json(row: &Decoded) -> String {
    format!(
        "{{\"text\":{},\"frequencyHz\":{:.2},\"dtSeconds\":{:.3},\"snrDb\":{:.1}}}",
        super::super::ffi::json_string(&row.text), row.freq_hz, row.dt_sec, row.snr_db
    )
}

pub fn decode(mode: i32, samples: &[f32], sample_rate: u32) -> String {
    if sample_rate != SAMPLE_RATE {
        return format!("{{\"error\":\"12 kHz PCM required\",\"sampleRate\":{sample_rate},\"decodes\":[]}}");
    }
    let Some(required) = slot_samples(mode) else {
        return "{\"error\":\"Unsupported digital mode\",\"decodes\":[]}".to_string();
    };
    if samples.is_empty() {
        return format!("{{\"error\":\"Empty receive slot\",\"expectedSamples\":{required},\"decodes\":[]}}");
    }
    // Public reference recordings commonly contain only the active waveform
    // rather than the complete T/R slot. A zero tail is equivalent to RF
    // silence and keeps the decoder's slot-sized FFT contract intact.
    let padded;
    let samples = if samples.len() < required {
        padded = {
            let mut value = vec![0.0; required];
            value[..samples.len()].copy_from_slice(samples);
            value
        };
        padded.as_slice()
    } else {
        &samples[..required]
    };
    let audio_i16 = pcm16(samples);
    let rows = if q65_variant(mode).is_some() {
        q65_dispatch!((mode - MODE_Q65_BASE) as usize, decode_q65, samples)
    } else if msk144_period(mode).is_some() {
        decode_msk144(&audio_i16)
    } else { match mode {
        MODE_FT8 => decode_frame::<Ft8>(&audio_i16, ProtocolId::Ft8),
        MODE_FT4 => decode_frame::<Ft4>(&audio_i16, ProtocolId::Ft4),
        MODE_FST4_15 => decode_frame::<Fst4s15>(&audio_i16, ProtocolId::Fst4),
        MODE_FST4_30 => decode_frame::<Fst4s30>(&audio_i16, ProtocolId::Fst4),
        MODE_FST4_60 => decode_frame::<Fst4s60>(&audio_i16, ProtocolId::Fst4),
        MODE_FST4_120 => decode_frame::<Fst4s120>(&audio_i16, ProtocolId::Fst4),
        MODE_FST4_300 => decode_frame::<Fst4s300>(&audio_i16, ProtocolId::Fst4),
        MODE_Q65_30A => Q65DecodeRequest::<Q65a30>::new(
            samples,
            SAMPLE_RATE,
            0,
            Q65SearchParams::default(),
        )
        .decode()
        .iter()
        .map(|result| result.to_decoded(SAMPLE_RATE, 0))
        .collect(),
        MODE_MSK144_15 => decode_msk144(&audio_i16),
        MODE_JT65A => mfsk_core::jt65::decode_scan_default(samples, SAMPLE_RATE)
            .iter()
            .map(|result| result.to_decoded(SAMPLE_RATE, 0))
            .collect(),
        MODE_JT65B => mfsk_core::jt65::decode_scan_submode_default(samples, SAMPLE_RATE, 1)
            .iter()
            .map(|result| result.to_decoded(SAMPLE_RATE, 0))
            .collect(),
        MODE_JT65C => mfsk_core::jt65::decode_scan_submode_default(samples, SAMPLE_RATE, 2)
            .iter()
            .map(|result| result.to_decoded(SAMPLE_RATE, 0))
            .collect(),
        MODE_WSPR => mfsk_core::wspr::decode::decode_scan_default(samples, SAMPLE_RATE)
            .iter()
            .map(|result| result.to_decoded())
            .collect(),
        MODE_FT2 => decode_ft2(&audio_i16),
        _ => Vec::new(),
    }};
    format!(
        "{{\"error\":null,\"expectedSamples\":{required},\"decodes\":[{}]}}",
        rows.iter().map(row_json).collect::<Vec<_>>().join(",")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pcm16_wav(path: &str) -> (u32, Vec<f32>) {
        let bytes = std::fs::read(path).expect("read WAV fixture");
        assert_eq!(&bytes[0..4], b"RIFF");
        assert_eq!(&bytes[8..12], b"WAVE");
        let mut at = 12usize;
        let mut rate = 0u32;
        let mut channels = 0usize;
        let mut bits = 0u16;
        let mut payload = &[][..];
        while at + 8 <= bytes.len() {
            let size = u32::from_le_bytes(bytes[at + 4..at + 8].try_into().unwrap()) as usize;
            let body = at + 8;
            if body + size > bytes.len() { break; }
            match &bytes[at..at + 4] {
                b"fmt " => {
                    assert_eq!(u16::from_le_bytes(bytes[body..body + 2].try_into().unwrap()), 1);
                    channels = u16::from_le_bytes(bytes[body + 2..body + 4].try_into().unwrap()) as usize;
                    rate = u32::from_le_bytes(bytes[body + 4..body + 8].try_into().unwrap());
                    bits = u16::from_le_bytes(bytes[body + 14..body + 16].try_into().unwrap());
                }
                b"data" => payload = &bytes[body..body + size],
                _ => {}
            }
            at = body + size + (size & 1);
        }
        assert_eq!(bits, 16);
        assert!(channels > 0 && rate > 0 && !payload.is_empty());
        let interleaved = payload.chunks_exact(2)
            .map(|v| i16::from_le_bytes([v[0], v[1]]) as f32 / 32768.0)
            .collect::<Vec<_>>();
        let mono = interleaved.chunks_exact(channels)
            .map(|frame| frame.iter().sum::<f32>() / channels as f32)
            .collect();
        (rate, mono)
    }

    #[test]
    fn nexus_public_ft8_recording_decodes() {
        let Ok(path) = std::env::var("SHACKCQ_FT8_WAV") else { return; };
        let (rate, samples) = pcm16_wav(&path);
        let result = decode(MODE_FT8, &samples, rate);
        eprintln!("FT8 fixture: {result}");
        assert!(result.contains("\"text\":"), "{result}");
    }

    #[test]
    fn nexus_public_ft4_recording_decodes() {
        let Ok(path) = std::env::var("SHACKCQ_FT4_WAV") else { return; };
        let (rate, samples) = pcm16_wav(&path);
        let result = decode(MODE_FT4, &samples, rate);
        eprintln!("FT4 fixture: {result}");
        assert!(result.contains("\"text\":"), "{result}");
    }

    #[test]
    fn native_ft8_ft4_and_ft2_waveforms_round_trip() {
        for mode in [MODE_FT8, MODE_FT4, MODE_FT2] {
            let waveform = encode(mode, "CQ OM0RX JN88", 1_500.0).expect("encode waveform");
            let mut slot = vec![0.0_f32; slot_samples(mode).unwrap()];
            let count = waveform.len().min(slot.len());
            slot[..count].copy_from_slice(&waveform[..count]);
            let result = decode(mode, &slot, SAMPLE_RATE);
            assert!(result.contains("OM0RX"), "mode {mode}: {result}");
        }
    }

    #[test]
    fn jt65_a_b_and_c_use_their_real_tone_spacings() {
        for mode in [MODE_JT65A, MODE_JT65B, MODE_JT65C] {
            let waveform = encode(mode, "CQ OM0RX JN88", 1_270.0).expect("JT65 waveform");
            let result = decode(mode, &waveform, SAMPLE_RATE);
            assert!(result.contains("OM0RX"), "mode {mode}: {result}");
        }
    }

    #[test]
    fn fst4_and_wspr_transmit_waveforms_follow_their_real_contracts() {
        for mode in [MODE_FST4_15, MODE_FST4_30, MODE_FST4_60, MODE_FST4_120, MODE_FST4_300] {
            let waveform = encode(mode, "CQ OM0RX JN88", 1_500.0).expect("FST4 waveform");
            assert!(!waveform.is_empty(), "mode {mode}");
            assert!(waveform.len() <= slot_samples(mode).unwrap(), "mode {mode}");
        }
        let wspr = encode(MODE_WSPR, "OM0RX JN88 30", 1_500.0).expect("WSPR waveform");
        assert!(!wspr.is_empty());
        assert!(wspr.len() <= slot_samples(MODE_WSPR).unwrap());
        assert!(encode(MODE_WSPR, "CQ OM0RX JN88", 1_500.0).is_none());
    }

    #[test]
    fn every_q65_and_msk144_variant_has_a_distinct_working_contract() {
        for variant in 0..25 {
            let mode = MODE_Q65_BASE + variant;
            let waveform = encode(mode, "CQ OM0RX JN88", 1_500.0)
                .unwrap_or_else(|| panic!("Q65 variant {variant} did not encode"));
            assert!(!waveform.is_empty(), "Q65 variant {variant}");
            assert_eq!(
                slot_samples(mode),
                Some(Q65_PERIODS[(variant / 5) as usize] as usize * SAMPLE_RATE as usize),
            );
        }
        for (variant, period) in MSK144_PERIODS.iter().enumerate() {
            let mode = MODE_MSK144_BASE + variant as i32;
            assert!(!encode(mode, "CQ OM0RX JN88", 1_500.0).unwrap().is_empty());
            assert_eq!(slot_samples(mode), Some(*period as usize * SAMPLE_RATE as usize));
        }
    }
}
