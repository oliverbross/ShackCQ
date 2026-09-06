// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 KD9TAW <kd9taw@protonmail.com>
// Copyright (C) 2026 AetherSDR contributors
// Copyright (C) 2026 Oliver Bross
//
// Adapted from Nexus flexvita.rs and AetherSDR PanadapterStream/VitaBinCoverage
// at the immutable commits recorded in ../UPSTREAM.md.

use std::collections::{BTreeMap, BTreeSet};

pub const FLEX_OUI: u32 = 0x001C2D;
pub const METER_CLASS: u16 = 0x8002;
pub const FFT_CLASS: u16 = 0x8003;
pub const WATERFALL_CLASS: u16 = 0x8004;
pub const OPUS_CLASS: u16 = 0x8005;
pub const NARROW_AUDIO_CLASS: u16 = 0x03E3;
pub const REDUCED_AUDIO_CLASS: u16 = 0x0123;
pub const AUDIO_SAMPLE_RATE: u32 = 24_000;
pub const MAX_DATAGRAM_BYTES: usize = 65_536;
pub const MAX_DISPLAY_BINS: usize = 16_384;

fn u16be(bytes: &[u8], offset: usize) -> Option<u16> {
    let value = bytes.get(offset..offset + 2)?;
    Some(u16::from_be_bytes([value[0], value[1]]))
}
fn i16be(bytes: &[u8], offset: usize) -> Option<i16> {
    u16be(bytes, offset).map(|value| value as i16)
}
fn u32be(bytes: &[u8], offset: usize) -> Option<u32> {
    let value = bytes.get(offset..offset + 4)?;
    Some(u32::from_be_bytes([value[0], value[1], value[2], value[3]]))
}
fn i64be(bytes: &[u8], offset: usize) -> Option<i64> {
    let value = bytes.get(offset..offset + 8)?;
    Some(i64::from_be_bytes(value.try_into().ok()?))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VitaEnvelope<'a> {
    pub packet_type: u8,
    pub sequence: u8,
    pub stream_id: Option<u32>,
    pub oui: Option<u32>,
    pub information_class: Option<u16>,
    pub packet_class: Option<u16>,
    pub integer_timestamp: Option<u32>,
    pub fractional_timestamp: Option<u64>,
    pub has_trailer: bool,
    pub payload: &'a [u8],
}

pub fn parse_vita(datagram: &[u8]) -> Option<VitaEnvelope<'_>> {
    if datagram.len() < 4 || datagram.len() > MAX_DATAGRAM_BYTES {
        return None;
    }
    let header = u32be(datagram, 0)?;
    let declared_bytes = (header as usize & 0xffff).checked_mul(4)?;
    if declared_bytes < 4 || declared_bytes > datagram.len().saturating_add(3) {
        return None;
    }
    let packet = datagram;
    let packet_type = ((header >> 28) & 0xf) as u8;
    let class_present = header & (1 << 27) != 0;
    let has_trailer = header & (1 << 26) != 0;
    let tsi = ((header >> 22) & 0x3) as u8;
    let tsf = ((header >> 20) & 0x3) as u8;
    let sequence = ((header >> 16) & 0xf) as u8;
    let mut offset = 4;
    let stream_id = if matches!(packet_type, 1 | 3 | 5) {
        let value = u32be(packet, offset)?;
        offset += 4;
        Some(value)
    } else {
        None
    };
    let (oui, information_class, packet_class) = if class_present {
        let oui_word = u32be(packet, offset)?;
        let class_word = u32be(packet, offset + 4)?;
        offset += 8;
        (
            Some(oui_word & 0x00ff_ffff),
            Some((class_word >> 16) as u16),
            Some(class_word as u16),
        )
    } else {
        (None, None, None)
    };
    let integer_timestamp = if tsi != 0 {
        let value = u32be(packet, offset)?;
        offset += 4;
        Some(value)
    } else {
        None
    };
    let fractional_timestamp = if tsf != 0 {
        let high = u32be(packet, offset)? as u64;
        let low = u32be(packet, offset + 4)? as u64;
        offset += 8;
        Some((high << 32) | low)
    } else {
        None
    };
    let payload_end = declared_bytes
        .min(datagram.len())
        .checked_sub(if has_trailer { 4 } else { 0 })?;
    if offset > payload_end {
        return None;
    }
    Some(VitaEnvelope {
        packet_type,
        sequence,
        stream_id,
        oui,
        information_class,
        packet_class,
        integer_timestamp,
        fractional_timestamp,
        has_trailer,
        payload: &packet[offset..payload_end],
    })
}

pub fn is_valid_flex_packet(packet: &VitaEnvelope<'_>) -> bool {
    packet.oui == Some(FLEX_OUI) && packet.stream_id.is_some() && packet.packet_class.is_some()
}

#[derive(Debug, Default, Clone)]
pub struct StreamRegistry {
    owned: BTreeMap<u32, u16>,
}
impl StreamRegistry {
    pub fn register(&mut self, stream_id: u32, packet_class: u16) -> bool {
        stream_id != 0 && self.owned.insert(stream_id, packet_class).is_none()
    }
    pub fn unregister(&mut self, stream_id: u32) -> bool {
        self.owned.remove(&stream_id).is_some()
    }
    pub fn accepts(&self, packet: &VitaEnvelope<'_>) -> bool {
        packet
            .stream_id
            .zip(packet.packet_class)
            .is_some_and(|pair| self.owned.get(&pair.0) == Some(&pair.1))
    }
    pub fn clear(&mut self) {
        self.owned.clear();
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct SequenceStats {
    pub packets: u64,
    pub gaps: u64,
    pub duplicates: u64,
}
#[derive(Debug, Default)]
pub struct SequenceTracker {
    last: BTreeMap<u32, u8>,
    stats: BTreeMap<u32, SequenceStats>,
}
impl SequenceTracker {
    pub fn observe(&mut self, stream_id: u32, sequence: u8) -> SequenceStats {
        let stats = self.stats.entry(stream_id).or_default();
        stats.packets += 1;
        if let Some(last) = self.last.insert(stream_id, sequence & 0xf) {
            let distance = (sequence.wrapping_sub(last)) & 0xf;
            if distance == 0 {
                stats.duplicates += 1;
            } else if distance > 1 {
                stats.gaps += (distance - 1) as u64;
            }
        }
        *stats
    }
    pub fn reset(&mut self) {
        self.last.clear();
        self.stats.clear();
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FftFragment {
    pub start_bin: usize,
    pub total_bins: usize,
    pub frame_index: u32,
    pub bins: Vec<u16>,
}
pub fn parse_fft(payload: &[u8]) -> Option<FftFragment> {
    let start_bin = u16be(payload, 0)? as usize;
    let declared_bins = u16be(payload, 2)? as usize;
    let bin_size = u16be(payload, 4)? as usize;
    let total_bins = u16be(payload, 6)? as usize;
    let frame_index = u32be(payload, 8)?;
    if declared_bins == 0
        || bin_size != 2
        || total_bins == 0
        || total_bins > MAX_DISPLAY_BINS
        || start_bin >= total_bins
    {
        return None;
    }
    let available = payload.len().saturating_sub(12) / 2;
    let count = declared_bins.min(available).min(total_bins - start_bin);
    if count == 0 {
        return None;
    }
    let bins = (0..count)
        .filter_map(|index| u16be(payload, 12 + index * 2))
        .collect();
    Some(FftFragment {
        start_bin,
        total_bins,
        frame_index,
        bins,
    })
}

#[derive(Debug, Default)]
pub struct FftAssembler {
    frame: Option<u32>,
    total: usize,
    bins: Vec<u16>,
    coverage: Vec<bool>,
    pub dropped_frames: u64,
}
impl FftAssembler {
    pub fn push(&mut self, fragment: FftFragment) -> Option<Vec<u16>> {
        if self.frame != Some(fragment.frame_index) || self.total != fragment.total_bins {
            if self.frame.is_some() && self.coverage.iter().any(|value| !value) {
                self.dropped_frames += 1;
            }
            self.frame = Some(fragment.frame_index);
            self.total = fragment.total_bins;
            self.bins = vec![0; self.total];
            self.coverage = vec![false; self.total];
        }
        if fragment.start_bin.checked_add(fragment.bins.len())? > self.total {
            return None;
        }
        for (offset, value) in fragment.bins.into_iter().enumerate() {
            let index = fragment.start_bin + offset;
            if !self.coverage[index] {
                self.coverage[index] = true;
                self.bins[index] = value;
            }
        }
        if !self.coverage.is_empty() && self.coverage.iter().all(|value| *value) {
            self.frame = None;
            self.coverage.clear();
            Some(std::mem::take(&mut self.bins))
        } else {
            None
        }
    }
}

pub fn fft_pixels_to_dbm(
    bins: &[u16],
    min_dbm: f32,
    max_dbm: f32,
    y_pixels: u16,
) -> Option<Vec<f32>> {
    if !min_dbm.is_finite() || !max_dbm.is_finite() || min_dbm >= max_dbm || y_pixels < 2 {
        return None;
    }
    let bottom = (y_pixels - 1) as f32;
    Some(
        bins.iter()
            .map(|value| {
                let pixel = (*value as f32).clamp(0.0, bottom);
                (max_dbm - pixel / bottom * (max_dbm - min_dbm)).clamp(min_dbm, max_dbm)
            })
            .collect(),
    )
}

#[derive(Debug, Clone, PartialEq)]
pub struct WaterfallFragment {
    pub timecode: u32,
    pub first_bin: usize,
    pub total_bins: usize,
    pub low_mhz: f64,
    pub bin_width_mhz: f64,
    pub line_duration_ms: u32,
    pub auto_black: u32,
    pub bins: Vec<f32>,
}
pub fn parse_waterfall(payload: &[u8]) -> Option<WaterfallFragment> {
    if payload.len() < 36 {
        return None;
    }
    let low_raw = i64be(payload, 0)?;
    let width_raw = i64be(payload, 8)?;
    let line_duration_ms = u32be(payload, 16)?;
    let tile_width = u16be(payload, 20)? as usize;
    let tile_height = u16be(payload, 22)? as usize;
    let timecode = u32be(payload, 24)?;
    let auto_black = u32be(payload, 28)?;
    let total_bins = u16be(payload, 32)? as usize;
    let first_bin = u16be(payload, 34)? as usize;
    if tile_width == 0
        || tile_height == 0
        || total_bins == 0
        || total_bins > MAX_DISPLAY_BINS
        || first_bin >= total_bins
    {
        return None;
    }
    let count = tile_width
        .min((payload.len() - 36) / 2)
        .min(total_bins - first_bin);
    if count == 0 {
        return None;
    }
    let bins = (0..count)
        .filter_map(|index| i16be(payload, 36 + index * 2))
        .map(|value| value as f32 / 128.0)
        .collect();
    Some(WaterfallFragment {
        timecode,
        first_bin,
        total_bins,
        line_duration_ms,
        auto_black,
        bins,
        low_mhz: low_raw as f64 / 1_048_576.0 / 1_000_000.0,
        bin_width_mhz: width_raw as f64 / 1_048_576.0 / 1_000_000.0,
    })
}

#[derive(Debug, Clone, PartialEq)]
pub struct WaterfallRow {
    pub timecode: u32,
    pub low_mhz: f64,
    pub high_mhz: f64,
    pub line_duration_ms: u32,
    pub auto_black: u32,
    pub bins: Vec<f32>,
}
#[derive(Debug, Default)]
pub struct WaterfallAssembler {
    timecode: Option<u32>,
    total: usize,
    low: f64,
    width: f64,
    duration: u32,
    black: u32,
    bins: Vec<f32>,
    coverage: Vec<bool>,
    pub dropped_rows: u64,
}
impl WaterfallAssembler {
    pub fn push(&mut self, fragment: WaterfallFragment) -> Option<WaterfallRow> {
        if self.timecode != Some(fragment.timecode) || self.total != fragment.total_bins {
            if self.timecode.is_some() && self.coverage.iter().any(|value| !value) {
                self.dropped_rows += 1;
            }
            self.timecode = Some(fragment.timecode);
            self.total = fragment.total_bins;
            self.low = fragment.low_mhz;
            self.width = fragment.bin_width_mhz;
            self.duration = fragment.line_duration_ms;
            self.black = fragment.auto_black;
            self.bins = vec![0.0; self.total];
            self.coverage = vec![false; self.total];
        }
        if fragment.first_bin.checked_add(fragment.bins.len())? > self.total {
            return None;
        }
        for (offset, value) in fragment.bins.into_iter().enumerate() {
            let index = fragment.first_bin + offset;
            if !self.coverage[index] {
                self.coverage[index] = true;
                self.bins[index] = value;
            }
        }
        if !self.coverage.is_empty() && self.coverage.iter().all(|value| *value) {
            let row = WaterfallRow {
                timecode: fragment.timecode,
                low_mhz: self.low,
                high_mhz: self.low + self.width * self.total as f64,
                line_duration_ms: self.duration,
                auto_black: self.black,
                bins: std::mem::take(&mut self.bins),
            };
            self.timecode = None;
            self.coverage.clear();
            Some(row)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MeterDefinition {
    pub id: u16,
    pub source: String,
    pub name: String,
    pub unit: String,
}
#[derive(Debug, Default)]
pub struct MeterDefinitions {
    entries: BTreeMap<u16, MeterDefinition>,
}
impl MeterDefinitions {
    pub fn apply_status(&mut self, body: &str) {
        let Some(rest) = body.strip_prefix("meter ") else {
            return;
        };
        let mut words = rest.split_whitespace();
        let Some(id) = words.next().and_then(|value| value.parse().ok()) else {
            return;
        };
        let words: Vec<_> = words.collect();
        if words.contains(&"removed") {
            self.entries.remove(&id);
            return;
        }
        let values: BTreeMap<_, _> = words
            .into_iter()
            .filter_map(|value| value.split_once('='))
            .collect();
        let definition = self.entries.entry(id).or_insert(MeterDefinition {
            id,
            source: String::new(),
            name: String::new(),
            unit: String::new(),
        });
        if let Some(value) = values.get("src").or_else(|| values.get("source")) {
            definition.source = value.trim_matches('"').to_string();
        }
        if let Some(value) = values.get("nam").or_else(|| values.get("name")) {
            definition.name = value.trim_matches('"').to_string();
        }
        if let Some(value) = values.get("unit") {
            definition.unit = value.trim_matches('"').to_string();
        }
    }
    pub fn get(&self, id: u16) -> Option<&MeterDefinition> {
        self.entries.get(&id)
    }
    pub fn clear(&mut self) {
        self.entries.clear();
    }
}
pub fn parse_meter_values(payload: &[u8]) -> Vec<(u16, i16)> {
    payload
        .chunks_exact(4)
        .map(|chunk| {
            (
                u16::from_be_bytes([chunk[0], chunk[1]]),
                i16::from_be_bytes([chunk[2], chunk[3]]),
            )
        })
        .collect()
}
pub fn convert_meter_value(unit: &str, raw: i16) -> f32 {
    match unit.to_ascii_lowercase().as_str() {
        "dbm" | "db" | "dbfs" | "swr" => raw as f32 / 128.0,
        "volts" | "amps" => raw as f32 / 256.0,
        "degf" | "degc" => raw as f32 / 64.0,
        _ => raw as f32,
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum AudioPayload<'a> {
    FloatStereo(Vec<f32>),
    ReducedMono(Vec<f32>),
    Opus(&'a [u8]),
}
pub fn decode_audio(packet_class: u16, payload: &[u8]) -> Option<AudioPayload<'_>> {
    match packet_class {
        NARROW_AUDIO_CLASS if payload.len() >= 8 => Some(AudioPayload::FloatStereo(
            payload
                .chunks_exact(4)
                .map(|chunk| f32::from_bits(u32::from_be_bytes(chunk.try_into().unwrap())))
                .collect(),
        )),
        REDUCED_AUDIO_CLASS if payload.len() >= 2 => Some(AudioPayload::ReducedMono(
            payload
                .chunks_exact(2)
                .map(|chunk| i16::from_be_bytes(chunk.try_into().unwrap()) as f32 / 32768.0)
                .collect(),
        )),
        OPUS_CLASS if !payload.is_empty() => Some(AudioPayload::Opus(payload)),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpMode {
    Lan,
    Wan,
}
pub fn udp_registration_command(mode: UdpMode, handle: u32, port: u16) -> Option<String> {
    match mode {
        UdpMode::Lan => (port != 0).then(|| format!("client udpport {port}")),
        UdpMode::Wan => (handle != 0).then(|| format!("client udp_register handle=0x{handle:X}")),
    }
}
pub fn udp_ping_command(handle: u32) -> Option<String> {
    (handle != 0).then(|| format!("client ping handle=0x{handle:X}"))
}

#[derive(Debug, Default)]
pub struct OwnedObjects {
    pans: BTreeSet<u32>,
    waterfalls: BTreeSet<u32>,
    slices: BTreeSet<u32>,
    streams: BTreeSet<u32>,
}
impl OwnedObjects {
    pub fn own_pan(&mut self, id: u32) {
        if id != 0 {
            self.pans.insert(id);
        }
    }
    pub fn own_waterfall(&mut self, id: u32) {
        if id != 0 {
            self.waterfalls.insert(id);
        }
    }
    pub fn own_slice(&mut self, id: u32) {
        self.slices.insert(id);
    }
    pub fn own_stream(&mut self, id: u32) {
        if id != 0 {
            self.streams.insert(id);
        }
    }
    pub fn may_remove_pan(&self, id: u32) -> bool {
        self.pans.contains(&id)
    }
    pub fn may_remove_waterfall(&self, id: u32) -> bool {
        self.waterfalls.contains(&id)
    }
    pub fn may_remove_slice(&self, id: u32) -> bool {
        self.slices.contains(&id)
    }
    pub fn may_remove_stream(&self, id: u32) -> bool {
        self.streams.contains(&id)
    }
    pub fn clear(&mut self) {
        self.pans.clear();
        self.waterfalls.clear();
        self.slices.clear();
        self.streams.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fft_payload(start: u16, total: u16, frame: u32, bins: &[u16]) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&start.to_be_bytes());
        bytes.extend_from_slice(&(bins.len() as u16).to_be_bytes());
        bytes.extend_from_slice(&2u16.to_be_bytes());
        bytes.extend_from_slice(&total.to_be_bytes());
        bytes.extend_from_slice(&frame.to_be_bytes());
        for bin in bins {
            bytes.extend_from_slice(&bin.to_be_bytes());
        }
        bytes
    }
    #[test]
    fn fft_coverage_ignores_duplicate_and_recovers_next_frame() {
        let mut assembler = FftAssembler::default();
        let first = parse_fft(&fft_payload(0, 4, 7, &[1, 2])).unwrap();
        assert!(assembler.push(first.clone()).is_none());
        assert!(assembler.push(first).is_none());
        assert_eq!(
            assembler.push(parse_fft(&fft_payload(2, 4, 7, &[3, 4])).unwrap()),
            Some(vec![1, 2, 3, 4])
        );
        assert!(assembler
            .push(parse_fft(&fft_payload(0, 4, 8, &[1])).unwrap())
            .is_none());
        assert_eq!(
            assembler.push(parse_fft(&fft_payload(0, 2, 9, &[5, 6])).unwrap()),
            Some(vec![5, 6])
        );
        assert_eq!(assembler.dropped_frames, 1);
    }
    #[test]
    fn pixel_scale_uses_radio_y_pixels() {
        let values = fft_pixels_to_dbm(&[0, 50, 99], -130.0, -30.0, 100).unwrap();
        for (actual, expected) in values.iter().zip([-30.0, -80.50505, -130.0]) {
            assert!((actual - expected).abs() < 0.0001);
        }
    }
    #[test]
    fn stream_registry_rejects_unowned_packets() {
        let mut registry = StreamRegistry::default();
        registry.register(7, FFT_CLASS);
        let packet = VitaEnvelope {
            packet_type: 3,
            sequence: 0,
            stream_id: Some(8),
            oui: Some(FLEX_OUI),
            information_class: None,
            packet_class: Some(FFT_CLASS),
            integer_timestamp: None,
            fractional_timestamp: None,
            has_trailer: false,
            payload: &[],
        };
        assert!(!registry.accepts(&packet));
    }
    #[test]
    fn sequence_tracker_counts_gaps_and_duplicates() {
        let mut t = SequenceTracker::default();
        t.observe(1, 1);
        t.observe(1, 1);
        let s = t.observe(1, 4);
        assert_eq!((s.packets, s.duplicates, s.gaps), (3, 1, 2));
    }
    #[test]
    fn reduced_audio_format_decodes() {
        let payload = [100i16, -100, 200, -200]
            .into_iter()
            .flat_map(i16::to_be_bytes)
            .collect::<Vec<_>>();
        assert!(
            matches!(decode_audio(REDUCED_AUDIO_CLASS, &payload),Some(AudioPayload::ReducedMono(v)) if v.len()==4)
        );
    }
    #[test]
    fn udp_policy_distinguishes_lan_and_wan() {
        assert_eq!(
            udp_registration_command(UdpMode::Lan, 0, 4995).unwrap(),
            "client udpport 4995"
        );
        assert!(udp_registration_command(UdpMode::Wan, 0, 4995).is_none());
        assert_eq!(udp_ping_command(0xabc).unwrap(), "client ping handle=0xABC");
    }
    #[test]
    fn ownership_is_fail_closed() {
        let mut owned = OwnedObjects::default();
        owned.own_slice(2);
        assert!(owned.may_remove_slice(2));
        assert!(!owned.may_remove_slice(3));
        owned.clear();
        assert!(!owned.may_remove_slice(2));
    }
}
