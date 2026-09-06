// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 KD9TAW <kd9taw@protonmail.com>
// Copyright (C) 2026 Oliver Bross
// Derived from Nexus crates/tempo-net/src/flexcat.rs at the commit in UPSTREAM.md.

use std::collections::BTreeMap;

pub const MAX_LINE_BYTES: usize = 16 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FlexMessage {
    Version(String),
    Handle(u32),
    Reply {
        sequence: u32,
        code: u32,
        message: String,
    },
    Status {
        handle: u32,
        body: String,
    },
    Message {
        code: u32,
        text: String,
    },
    Unknown(String),
}

pub fn parse_line(line: &str) -> FlexMessage {
    let line = line.trim_end_matches(['\r', '\n']);
    let Some((tag, rest)) = line.split_at_checked(1) else {
        return FlexMessage::Unknown(String::new());
    };
    match tag {
        "V" => FlexMessage::Version(rest.to_string()),
        "H" => parse_u32(rest)
            .filter(|v| *v != 0)
            .map(FlexMessage::Handle)
            .unwrap_or_else(|| FlexMessage::Unknown(line.to_string())),
        "R" => {
            let mut parts = rest.splitn(3, '|');
            match (
                parts.next().and_then(|v| v.parse().ok()),
                parts.next().and_then(parse_hex),
            ) {
                (Some(sequence), Some(code)) => FlexMessage::Reply {
                    sequence,
                    code,
                    message: parts.next().unwrap_or_default().to_string(),
                },
                _ => FlexMessage::Unknown(line.to_string()),
            }
        }
        "S" => {
            let (handle, body) = rest.split_once('|').unwrap_or((rest, ""));
            parse_u32(handle)
                .map(|handle| FlexMessage::Status {
                    handle,
                    body: body.to_string(),
                })
                .unwrap_or_else(|| FlexMessage::Unknown(line.to_string()))
        }
        "M" => {
            let (code, text) = rest.split_once('|').unwrap_or((rest, ""));
            parse_hex(code)
                .map(|code| FlexMessage::Message {
                    code,
                    text: text.to_string(),
                })
                .unwrap_or_else(|| FlexMessage::Unknown(line.to_string()))
        }
        _ => FlexMessage::Unknown(line.to_string()),
    }
}

#[derive(Debug, Default)]
pub struct FlexFramer {
    pending: Vec<u8>,
    dropping_oversize: bool,
}

impl FlexFramer {
    pub fn push(&mut self, bytes: &[u8]) -> Vec<FlexMessage> {
        let mut output = Vec::new();
        for byte in bytes {
            if *byte == b'\n' || *byte == b'\r' {
                if self.dropping_oversize {
                    self.dropping_oversize = false;
                    self.pending.clear();
                    continue;
                }
                if !self.pending.is_empty() {
                    output.push(parse_line(&String::from_utf8_lossy(&self.pending)));
                    self.pending.clear();
                }
            } else if !self.dropping_oversize {
                if self.pending.len() < MAX_LINE_BYTES {
                    self.pending.push(*byte);
                } else {
                    self.pending.clear();
                    self.dropping_oversize = true;
                }
            }
        }
        output
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RadioIdentity {
    pub version: String,
    pub nickname: String,
    pub callsign: String,
    pub serial: String,
    pub firmware: String,
    pub model: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ClientState {
    pub handle: u32,
    pub client_id: String,
    pub program: String,
    pub station: String,
    pub connected: bool,
    pub is_gui: bool,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct SliceState {
    pub index: u32,
    pub letter: String,
    pub in_use: bool,
    pub active: bool,
    pub tx: bool,
    pub client_handle: Option<u32>,
    pub frequency_hz: u64,
    pub mode: String,
    pub filter_low_hz: i32,
    pub filter_high_hz: i32,
    pub rx_antenna: String,
}

impl SliceState {
    pub fn filter_width_hz(&self) -> i32 {
        self.filter_high_hz.saturating_sub(self.filter_low_hz)
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct FlexState {
    pub connected: bool,
    pub handle: u32,
    pub identity: RadioIdentity,
    pub clients: BTreeMap<u32, ClientState>,
    pub slices: BTreeMap<u32, SliceState>,
}

impl FlexState {
    pub fn apply(&mut self, message: &FlexMessage) {
        match message {
            FlexMessage::Version(version) => self.identity.version = version.clone(),
            FlexMessage::Handle(handle) if *handle != 0 => {
                self.handle = *handle;
                self.connected = true;
            }
            FlexMessage::Status { body, .. } => self.apply_status(body),
            _ => {}
        }
    }

    fn apply_status(&mut self, body: &str) {
        if let Some(rest) = body.strip_prefix("slice ") {
            self.apply_slice(rest);
        } else if let Some(rest) = body.strip_prefix("client ") {
            self.apply_client(rest);
        } else if let Some(rest) = body.strip_prefix("radio ") {
            self.apply_radio(rest);
        }
    }

    fn apply_slice(&mut self, rest: &str) {
        let mut words = rest.split_whitespace();
        let Some(index) = words.next().and_then(|v| v.parse::<u32>().ok()) else {
            return;
        };
        let tail: Vec<_> = words.collect();
        if tail.contains(&"removed") {
            self.slices.remove(&index);
            return;
        }
        let fields = key_values(tail.into_iter());
        if fields.get("in_use").is_some_and(|v| v == "0") {
            self.slices.remove(&index);
            return;
        }
        let slice = self.slices.entry(index).or_insert_with(|| SliceState {
            index,
            letter: char::from_u32('A' as u32 + index)
                .unwrap_or('?')
                .to_string(),
            ..Default::default()
        });
        if let Some(v) = fields.get("letter") {
            slice.letter = v.clone();
        }
        if let Some(v) = fields.get("in_use") {
            slice.in_use = bool_value(v);
        }
        if let Some(v) = fields.get("active") {
            slice.active = bool_value(v);
        }
        if let Some(v) = fields.get("tx") {
            slice.tx = bool_value(v);
        }
        if let Some(v) = fields.get("client_handle").and_then(|v| parse_u32(v)) {
            slice.client_handle = Some(v);
        }
        if let Some(v) = fields
            .get("RF_frequency")
            .and_then(|v| v.parse::<f64>().ok())
        {
            slice.frequency_hz = (v * 1_000_000.0).round().max(0.0) as u64;
        }
        if let Some(v) = fields.get("mode") {
            slice.mode = v.to_uppercase();
        }
        if let Some(v) = fields.get("filter_lo").and_then(|v| filter_hz(v)) {
            slice.filter_low_hz = v;
        }
        if let Some(v) = fields.get("filter_hi").and_then(|v| filter_hz(v)) {
            slice.filter_high_hz = v;
        }
        if let Some(v) = fields.get("rxant").or_else(|| fields.get("ant")) {
            slice.rx_antenna = v.clone();
        }
    }

    fn apply_client(&mut self, rest: &str) {
        let mut words = rest.split_whitespace();
        let Some(handle) = words.next().and_then(parse_u32) else {
            return;
        };
        let tail: Vec<_> = words.collect();
        if tail.iter().any(|v| *v == "removed" || *v == "disconnected") {
            self.clients.remove(&handle);
            return;
        }
        let fields = key_values(tail.into_iter());
        let client = self.clients.entry(handle).or_insert_with(|| ClientState {
            handle,
            connected: true,
            ..Default::default()
        });
        client.connected = fields.get("connected").map_or(true, |v| bool_value(v));
        if let Some(v) = fields.get("client_id") {
            client.client_id = v.clone();
            if !v.is_empty() {
                client.is_gui = true;
            }
        }
        if let Some(v) = fields.get("program") {
            client.program = v.clone();
        }
        if let Some(v) = fields.get("station") {
            client.station = v.clone();
        }
        if let Some(v) = fields.get("gui") {
            client.is_gui = bool_value(v);
        }
    }

    fn apply_radio(&mut self, rest: &str) {
        let fields = key_values(rest.split_whitespace());
        if let Some(v) = fields.get("nickname").or_else(|| fields.get("name")) {
            self.identity.nickname = v.clone();
        }
        if let Some(v) = fields.get("callsign") {
            self.identity.callsign = v.clone();
        }
        if let Some(v) = fields.get("serial") {
            self.identity.serial = v.clone();
        }
        if let Some(v) = fields.get("firmware").or_else(|| fields.get("version")) {
            self.identity.firmware = v.clone();
        }
        if let Some(v) = fields.get("model") {
            self.identity.model = v.clone();
        }
    }

    pub fn selected_slice(&self, preferred_station: &str) -> Option<&SliceState> {
        let compatible: Vec<_> = self
            .slices
            .values()
            .filter(|slice| {
                slice.in_use
                    && !slice.tx
                    && match slice.client_handle {
                        Some(handle) if !preferred_station.is_empty() => self
                            .clients
                            .get(&handle)
                            .is_some_and(|client| client.station == preferred_station),
                        _ => preferred_station.is_empty(),
                    }
            })
            .collect();
        if compatible.len() == 1 {
            compatible.first().copied()
        } else {
            compatible.iter().copied().find(|slice| slice.active)
        }
    }
}

fn key_values<'a>(words: impl Iterator<Item = &'a str>) -> BTreeMap<String, String> {
    words
        .filter_map(|word| {
            word.split_once('=')
                .map(|(k, v)| (k.to_string(), v.trim_matches('"').to_string()))
        })
        .collect()
}

fn bool_value(value: &str) -> bool {
    matches!(value.to_ascii_lowercase().as_str(), "1" | "true" | "yes")
}
fn parse_hex(value: &str) -> Option<u32> {
    u32::from_str_radix(value.trim().trim_start_matches("0x"), 16).ok()
}
fn parse_u32(value: &str) -> Option<u32> {
    if value.trim().starts_with("0x") {
        parse_hex(value)
    } else {
        value.trim().parse().ok().or_else(|| parse_hex(value))
    }
}
fn filter_hz(value: &str) -> Option<i32> {
    value.parse::<f64>().ok().map(|v| {
        if v.abs() < 1.0 {
            (v * 1_000_000.0).round() as i32
        } else {
            v.round() as i32
        }
    })
}

pub fn encode_command(sequence: u32, command: &str) -> String {
    format!("C{}|{}\n", sequence.max(1), command)
}

pub fn client_identity_command(program: &str) -> Option<String> {
    (!program.is_empty()
        && program.len() <= 32
        && program
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-'))
    .then(|| format!("client program {program}"))
}
pub fn subscriptions() -> [&'static str; 3] {
    ["sub radio all", "sub client all", "sub slice all"]
}
pub fn keepalive_command() -> &'static str {
    "ping"
}
pub fn frequency_command(slice: u32, frequency_hz: u64) -> Option<String> {
    (100_000..=77_000_000_000)
        .contains(&frequency_hz)
        .then(|| format!("slice t {slice} {:.6}", frequency_hz as f64 / 1_000_000.0))
}
pub fn mode_command(slice: u32, mode: &str) -> Option<String> {
    const RECEIVE_MODES: &[&str] = &[
        "LSB", "USB", "DSB", "CW", "CWL", "CWU", "FM", "NFM", "AM", "SAM", "DIGL", "DIGU", "RTTY",
    ];
    let mode = mode.to_ascii_uppercase();
    RECEIVE_MODES
        .contains(&mode.as_str())
        .then(|| format!("slice s {slice} mode={mode}"))
}
pub fn filter_command(slice_letter: &str, low_hz: i32, high_hz: i32) -> Option<String> {
    (slice_letter.len() == 1
        && slice_letter.chars().all(|c| c.is_ascii_uppercase())
        && low_hz < high_hz
        && (-12_000..=12_000).contains(&low_hz)
        && (-12_000..=12_000).contains(&high_hz))
    .then(|| format!("filt {slice_letter} {low_hz} {high_hz}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nexus_frames_and_incremental_reads() {
        assert_eq!(
            parse_line("V3.8.23\r\n"),
            FlexMessage::Version("3.8.23".into())
        );
        assert_eq!(parse_line("H2ABC"), FlexMessage::Handle(0x2abc));
        let mut framer = FlexFramer::default();
        assert!(framer.push(b"S0|slice 1 in_use").is_empty());
        let messages = framer.push(b"=1\r\nM10000000|ok\n");
        assert_eq!(messages.len(), 2);
    }

    #[test]
    fn state_tracks_clients_slices_and_removals() {
        let mut state = FlexState::default();
        for line in [
            "V3.8.23", "H2ABC", "S0|radio model=FLEX-8400 nickname=Remote callsign=OM0RX serial=123 firmware=3.8.23",
            "S0|client 0x100 connected=1 client_id=id program=SmartSDR station=Shack gui=1",
            "S0|slice 1 in_use=1 letter=B active=1 tx=0 client_handle=0x100 RF_frequency=14.074 mode=DIGU filter_lo=300 filter_hi=3000 rxant=ANT1",
        ] { state.apply(&parse_line(line)); }
        assert!(state.connected);
        assert_eq!(
            state.selected_slice("Shack").unwrap().frequency_hz,
            14_074_000
        );
        assert_eq!(state.slices[&1].filter_width_hz(), 2700);
        state.apply(&parse_line("S0|slice 1 removed"));
        state.apply(&parse_line("S0|client 0x100 disconnected"));
        assert!(state.slices.is_empty() && state.clients.is_empty());
    }

    #[test]
    fn real_client_id_status_identifies_gui_without_synthetic_gui_field() {
        let mut state = FlexState::default();
        state.apply(&parse_line("S0|client 0x100 connected local_ptt=0 client_id=12345678-1234-4234-8234-123456789abc program=ShackCQ station=ShackCQ"));
        assert!(state.clients[&0x100].is_gui);
        assert!(state.clients[&0x100].connected);
    }

    #[test]
    fn builders_are_explicitly_receive_only() {
        let commands = [
            client_identity_command("ShackCQ").unwrap(),
            subscriptions().join(" "),
            keepalive_command().into(),
            frequency_command(1, 14_074_000).unwrap(),
            mode_command(1, "DIGU").unwrap(),
            filter_command("B", 300, 3000).unwrap(),
        ]
        .join(" ")
        .to_ascii_lowercase();
        for prohibited in [
            "xmit",
            "mox",
            "tune",
            "cwx",
            "dax",
            "stream",
            "display",
            "slice create",
            "slice remove",
            "transmit set",
            "power",
            "tx=",
        ] {
            assert!(
                !commands.contains(prohibited),
                "prohibited token {prohibited}"
            );
        }
        assert!(mode_command(0, "TX").is_none());
        assert!(frequency_command(0, 0).is_none());
    }
}
