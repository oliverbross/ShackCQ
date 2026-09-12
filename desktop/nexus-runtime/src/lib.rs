// SPDX-License-Identifier: GPL-3.0-only
//! ShackCQ's bounded adapter over the pinned Nexus native modem.
//!
//! The package enables `tempo-audio/device` only through this crate's
//! `live-audio` feature. `serial` and `ai-cw` remain disabled. It cannot launch
//! rigctld, use OmniRig/native CI-V, open an output
//! device, key PTT, upload to external loggers, or fall back to ShackCQ's legacy
//! modem. Hardware ownership remains behind ShackCQ's existing Hamlib helper.

#[cfg(feature = "live-audio")]
mod live_audio;
mod queue;

use rustfft::{num_complex::Complex, FftPlanner};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::path::Path;
use tempo_audio::capture_resample::CaptureResampler;
use thiserror::Error;

pub use queue::{ContactQueue, QueueError, QueueKey, ReviewedContact};

pub const CONTRACT_VERSION: u16 = 1;
pub const NEXUS_COMMIT: &str = "7618390658f8f92431dec0ac65979b84f2c0fb76";
pub const NEXUS_RELEASE: &str = "v1.10.3";
pub const TX_ENABLED: bool = false;
pub const MAX_COMMAND_BYTES: usize = 64 * 1024;
pub const MAX_EVENT_HISTORY: usize = 512;
pub const MAX_DECODE_ROWS: usize = 200;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DigiMode {
    Ft8,
    Ft4,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeState {
    Safe,
    Configured,
    Receiving,
    Stopped,
    RxUnconfirmed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct EngineIdentity {
    pub product: String,
    pub engine: String,
    pub upstream_release: String,
    pub upstream_commit: String,
    pub adapter_version: String,
    pub compiled_modes: Vec<DigiMode>,
    pub legacy_fallback: bool,
}

impl Default for EngineIdentity {
    fn default() -> Self {
        Self {
            product: "ShackCQ Desktop".into(),
            engine: "kd9taw/Nexus native libtempo".into(),
            upstream_release: NEXUS_RELEASE.into(),
            upstream_commit: NEXUS_COMMIT.into(),
            adapter_version: env!("CARGO_PKG_VERSION").into(),
            compiled_modes: vec![DigiMode::Ft8, DigiMode::Ft4],
            legacy_fallback: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub native_decode: Vec<DigiMode>,
    pub encoder_sinks: Vec<String>,
    pub live_audio_input: bool,
    pub audio_output: bool,
    pub cat_owner: String,
    pub cloud_owner: String,
    pub external_loggers: bool,
    pub browser_bridge: bool,
    pub tx_enabled: bool,
    pub tx_lock_reason: String,
}

impl Default for Capabilities {
    fn default() -> Self {
        Self {
            native_decode: vec![DigiMode::Ft8, DigiMode::Ft4],
            encoder_sinks: vec!["NULL".into(), "TEST_FILE".into()],
            live_audio_input: cfg!(feature = "live-audio"),
            audio_output: false,
            cat_owner: "SHACKCQ_HAMLIB_HELPER".into(),
            cloud_owner: "SHACKCQ_CLOUD_AGENT".into(),
            external_loggers: false,
            browser_bridge: false,
            tx_enabled: TX_ENABLED,
            tx_lock_reason:
                "SHACKCQ_DIGI_TX_ENABLED=false; owner hardware and RF acceptance pending".into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RxProfile {
    pub device_id: String,
    pub output_device_id: Option<String>,
    pub channel: u16,
    pub input_rate_hz: u32,
    pub mode: DigiMode,
}

impl RxProfile {
    fn validate(&self) -> Result<(), RuntimeError> {
        if self.device_id.is_empty() || self.device_id.len() > 256 || self.device_id.contains('\0')
        {
            return Err(RuntimeError::InvalidProfile);
        }
        if self
            .output_device_id
            .as_ref()
            .is_some_and(|id| id.len() > 256 || id.contains('\0'))
        {
            return Err(RuntimeError::InvalidProfile);
        }
        if self.channel > 63 || !(8_000..=384_000).contains(&self.input_rate_hz) {
            return Err(RuntimeError::InvalidProfile);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DecodeEvent {
    pub sequence: u64,
    pub mode: DigiMode,
    pub message: String,
    pub snr_db: i32,
    pub dt_seconds: f32,
    pub audio_hz: f32,
    pub quality: f32,
    pub fixture: bool,
    pub slot_start_millis: u64,
    pub exact_slot_timing: bool,
    pub provenance: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct WaterfallFrame {
    pub sequence: u64,
    pub observed_unix_millis: u64,
    pub low_hz: u32,
    pub high_hz: u32,
    pub bins: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct FrameDecode {
    pub message: String,
    pub snr_db: i32,
    pub dt_seconds: f32,
    pub audio_hz: f32,
    pub quality: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct CaptureFrameOutput {
    pub decodes: Vec<FrameDecode>,
    pub waterfall_bins: Vec<u8>,
}

/// The single production capture-frame adapter used by live RX and reference recordings.
pub struct FrameDecoder;

impl FrameDecoder {
    pub fn process_capture_frame(
        mode: DigiMode,
        samples: &[i16],
    ) -> Result<CaptureFrameOutput, RuntimeError> {
        let decodes = match mode {
            DigiMode::Ft8 => {
                if samples.len() < ft8::NMAX {
                    return Err(RuntimeError::InvalidProfile);
                }
                ft8::decode_frame(samples, 200, 2900, 3, "", "", 0, 0, true, false)
                    .into_iter()
                    .take(MAX_DECODE_ROWS)
                    .map(|d| FrameDecode {
                        message: d.message,
                        snr_db: d.snr,
                        dt_seconds: d.dt,
                        audio_hz: d.freq,
                        quality: d.qual,
                    })
                    .collect()
            }
            DigiMode::Ft4 => {
                if samples.len() < ft4::NMAX {
                    return Err(RuntimeError::InvalidProfile);
                }
                ft4::decode_frame(samples, 200, 2900, 3, "", "", 0, 0, false)
                    .into_iter()
                    .take(MAX_DECODE_ROWS)
                    .map(|d| FrameDecode {
                        message: d.message,
                        snr_db: d.snr,
                        dt_seconds: d.dt,
                        audio_hz: d.freq,
                        quality: d.qual,
                    })
                    .collect()
            }
        };
        let size = 2048usize.min(samples.len());
        let mut input: Vec<Complex<f32>> = samples[..size]
            .iter()
            .enumerate()
            .map(|(i, &v)| {
                let window = 0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / size as f32).cos();
                Complex::new(v as f32 / i16::MAX as f32 * window, 0.0)
            })
            .collect();
        input.resize(2048, Complex::new(0.0, 0.0));
        FftPlanner::<f32>::new()
            .plan_fft_forward(2048)
            .process(&mut input);
        let waterfall_bins = input[..1024]
            .iter()
            .map(|v| {
                let db = 20.0 * (v.norm() / 2048.0).max(1e-7).log10();
                (((db + 100.0) * 2.55).round().clamp(0.0, 255.0)) as u8
            })
            .collect();
        Ok(CaptureFrameOutput {
            decodes,
            waterfall_bins,
        })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct AlignedCaptureFrame {
    pub slot_start_millis: u64,
    pub exact_slot_timing: bool,
    pub samples: Vec<i16>,
}

/// Aligns 12 kHz capture to UTC FT slots and resets on a detected wall-clock step.
pub struct SlotFrameAligner {
    slot_millis: u64,
    frame_samples: usize,
    pending: Vec<i16>,
    slot_start: Option<u64>,
    expected_next_millis: Option<f64>,
    clock_resets: u64,
}
impl SlotFrameAligner {
    pub fn new(mode: DigiMode) -> Self {
        match mode {
            DigiMode::Ft8 => Self {
                slot_millis: 15_000,
                frame_samples: ft8::NMAX,
                pending: Vec::with_capacity(ft8::NMAX),
                slot_start: None,
                expected_next_millis: None,
                clock_resets: 0,
            },
            DigiMode::Ft4 => Self {
                slot_millis: 7_500,
                frame_samples: ft4::NMAX,
                pending: Vec::with_capacity(ft4::NMAX),
                slot_start: None,
                expected_next_millis: None,
                clock_resets: 0,
            },
        }
    }
    pub fn clock_resets(&self) -> u64 {
        self.clock_resets
    }
    pub fn push_chunk(
        &mut self,
        chunk_start_millis: f64,
        samples: &[i16],
    ) -> Vec<AlignedCaptureFrame> {
        if self
            .expected_next_millis
            .is_some_and(|expected| (chunk_start_millis - expected).abs() > 100.0)
        {
            self.pending.clear();
            self.slot_start = None;
            self.clock_resets = self.clock_resets.saturating_add(1);
        }
        self.expected_next_millis = Some(chunk_start_millis + samples.len() as f64 / 12.0);
        let mut cursor = 0usize;
        if self.slot_start.is_none() {
            let next =
                ((chunk_start_millis / self.slot_millis as f64).ceil() as u64) * self.slot_millis;
            let discard = (((next as f64 - chunk_start_millis).max(0.0)) * 12.0).ceil() as usize;
            if discard >= samples.len() {
                return Vec::new();
            }
            cursor = discard;
            self.slot_start = Some(next);
        }
        let mut out = Vec::new();
        while cursor < samples.len() {
            let take = (self.frame_samples - self.pending.len()).min(samples.len() - cursor);
            self.pending
                .extend_from_slice(&samples[cursor..cursor + take]);
            cursor += take;
            if self.pending.len() == self.frame_samples {
                let start = self.slot_start.unwrap();
                out.push(AlignedCaptureFrame {
                    slot_start_millis: start,
                    // Boundary grouping is deterministic, but the capture callback has no
                    // calibrated hardware timestamp. Public timing therefore stays inexact.
                    exact_slot_timing: false,
                    samples: std::mem::replace(
                        &mut self.pending,
                        Vec::with_capacity(self.frame_samples),
                    ),
                });
                self.slot_start = Some(start + self.slot_millis);
            }
        }
        out
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeSnapshot {
    pub identity: EngineIdentity,
    pub capabilities: Capabilities,
    pub state: RuntimeState,
    pub generation: u64,
    pub rx_profile: Option<RxProfile>,
    pub stop_latched: bool,
    pub pending_contacts: usize,
    pub event_sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimePresence {
    pub snapshot: RuntimeSnapshot,
    pub decodes: Vec<DecodeEvent>,
    pub waterfall: Vec<WaterfallFrame>,
    pub input_devices: Vec<AudioDeviceInfo>,
    pub output_devices: Vec<AudioDeviceInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandEnvelope {
    pub version: u16,
    pub command_id: String,
    pub generation: u64,
    pub launch_nonce: String,
    pub command: RuntimeCommand,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "type",
    content = "parameters",
    rename_all = "SCREAMING_SNAKE_CASE"
)]
pub enum RuntimeCommand {
    Identity,
    Snapshot,
    Presence,
    ConfigureRx(RxProfile),
    StartReceiving,
    Stop {
        reason: String,
        radio_rx_readback: Option<bool>,
    },
    QueueReviewedContact(ReviewedContact),
    PendingReviewedContacts,
    AcknowledgeContact {
        event_id: String,
        durable_receipt: bool,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandResult {
    pub version: u16,
    pub command_id: String,
    pub generation: u64,
    pub ok: bool,
    pub code: String,
    pub payload: Value,
}

#[derive(Debug, Error)]
pub enum RuntimeError {
    #[error("invalid RX profile")]
    InvalidProfile,
    #[error("unsupported mode")]
    UnsupportedMode,
    #[error("audio input is intentionally not opened by this adapter build")]
    AudioBackendUnavailable,
    #[error("stale generation")]
    StaleGeneration,
    #[error("invalid IPC envelope")]
    InvalidEnvelope,
    #[error("transmit is locked")]
    TxLocked,
    #[error("queue error: {0}")]
    Queue(#[from] QueueError),
}

pub struct StationRuntime {
    generation: u64,
    state: RuntimeState,
    profile: Option<RxProfile>,
    stop_latched: bool,
    sequence: u64,
    events: VecDeque<DecodeEvent>,
    waterfall: VecDeque<WaterfallFrame>,
    queue: ContactQueue,
    #[cfg(feature = "live-audio")]
    audio_input: Option<live_audio::LiveReceiver>,
}

impl StationRuntime {
    pub fn new(queue_path: impl AsRef<Path>, queue_key: QueueKey) -> Result<Self, RuntimeError> {
        Ok(Self {
            generation: 1,
            state: RuntimeState::Safe,
            profile: None,
            stop_latched: false,
            sequence: 0,
            events: VecDeque::with_capacity(MAX_EVENT_HISTORY),
            waterfall: VecDeque::with_capacity(8),
            queue: ContactQueue::open(queue_path, queue_key)?,
            #[cfg(feature = "live-audio")]
            audio_input: None,
        })
    }

    pub fn snapshot(&self) -> RuntimeSnapshot {
        RuntimeSnapshot {
            identity: EngineIdentity::default(),
            capabilities: Capabilities::default(),
            state: self.state.clone(),
            generation: self.generation,
            rx_profile: self.profile.clone(),
            stop_latched: self.stop_latched,
            pending_contacts: self.queue.len(),
            event_sequence: self.sequence,
        }
    }

    pub fn configure_rx(&mut self, profile: RxProfile) -> Result<(), RuntimeError> {
        profile.validate()?;
        self.profile = Some(profile);
        #[cfg(feature = "live-audio")]
        {
            self.audio_input = None;
        }
        self.generation = self.generation.saturating_add(1);
        self.stop_latched = false;
        self.state = RuntimeState::Configured;
        Ok(())
    }

    pub fn start_receiving(&mut self) -> Result<(), RuntimeError> {
        let profile = self.profile.as_ref().ok_or(RuntimeError::InvalidProfile)?;
        #[cfg(feature = "live-audio")]
        {
            let receiver = live_audio::LiveReceiver::start(profile.clone())?;
            if let Some(profile) = self.profile.as_mut() {
                profile.input_rate_hz = receiver.opened_sample_rate();
            }
            self.audio_input = Some(receiver);
            self.state = RuntimeState::Receiving;
            return Ok(());
        }
        #[cfg(not(feature = "live-audio"))]
        {
            let _ = profile;
            Err(RuntimeError::AudioBackendUnavailable)
        }
    }

    pub fn stop(&mut self, radio_rx_readback: Option<bool>) -> &'static str {
        #[cfg(feature = "live-audio")]
        {
            self.audio_input = None;
        }
        self.stop_latched = true;
        self.generation = self.generation.saturating_add(1);
        if radio_rx_readback == Some(true) {
            self.state = RuntimeState::Stopped;
            "RX_VERIFIED"
        } else {
            self.state = RuntimeState::RxUnconfirmed;
            "RX_UNCONFIRMED"
        }
    }

    pub fn decode_pcm(
        &mut self,
        mode: DigiMode,
        samples: &[i16],
        fixture: bool,
    ) -> Result<Vec<DecodeEvent>, RuntimeError> {
        let output = FrameDecoder::process_capture_frame(mode, samples)?;
        let mut result = Vec::with_capacity(output.decodes.len());
        for row in output.decodes {
            self.sequence = self.sequence.saturating_add(1);
            let event = DecodeEvent {
                sequence: self.sequence,
                mode: mode.clone(),
                message: row.message,
                snr_db: row.snr_db,
                dt_seconds: row.dt_seconds,
                audio_hz: row.audio_hz,
                quality: row.quality,
                fixture,
                slot_start_millis: 0,
                exact_slot_timing: false,
                provenance: format!("NEXUS_NATIVE@{NEXUS_COMMIT}"),
            };
            if self.events.len() == MAX_EVENT_HISTORY {
                self.events.pop_front();
            }
            self.events.push_back(event.clone());
            result.push(event);
        }
        self.sequence = self.sequence.saturating_add(1);
        if self.waterfall.len() == 8 {
            self.waterfall.pop_front();
        }
        self.waterfall.push_back(WaterfallFrame {
            sequence: self.sequence,
            observed_unix_millis: now_unix_millis(),
            low_hz: 0,
            high_hz: 6000,
            bins: output.waterfall_bins,
        });
        Ok(result)
    }

    pub fn recent_events(&self, after_sequence: u64, maximum: usize) -> Vec<DecodeEvent> {
        self.events
            .iter()
            .filter(|e| e.sequence > after_sequence)
            .take(maximum.clamp(1, 200))
            .cloned()
            .collect()
    }

    pub fn recent_waterfall(&self) -> Vec<WaterfallFrame> {
        self.waterfall.iter().cloned().collect()
    }

    pub fn resample_capture(input_rate_hz: u32, chunks: &[&[f32]]) -> Vec<f32> {
        let mut resampler = CaptureResampler::new(input_rate_hz, 12_000);
        chunks
            .iter()
            .flat_map(|chunk| resampler.process(chunk))
            .collect()
    }

    #[cfg(feature = "live-audio")]
    pub fn enumerate_audio_devices() -> (Vec<AudioDeviceInfo>, Vec<AudioDeviceInfo>) {
        let (inputs, outputs) = tempo_audio::device::available_devices();
        let convert = |d: tempo_audio::audiodev::AudioDevice| AudioDeviceInfo {
            id: d.name,
            label: d.label,
        };
        (
            inputs.into_iter().map(convert).collect(),
            outputs.into_iter().map(convert).collect(),
        )
    }

    #[cfg(feature = "live-audio")]
    pub fn drain_live_capture(&mut self) -> Result<Vec<f32>, RuntimeError> {
        if self.audio_input.is_none() {
            return Err(RuntimeError::AudioBackendUnavailable);
        }
        self.poll_live_events();
        Ok(Vec::new())
    }

    #[cfg(feature = "live-audio")]
    fn poll_live_events(&mut self) {
        let Some(receiver) = self.audio_input.as_mut() else {
            return;
        };
        for event in receiver.drain_events() {
            match event {
                live_audio::WorkerEvent::Decode(row) => {
                    self.sequence = self.sequence.saturating_add(1);
                    let event = DecodeEvent {
                        sequence: self.sequence,
                        mode: row.mode,
                        message: row.message,
                        snr_db: row.snr_db,
                        dt_seconds: row.dt_seconds,
                        audio_hz: row.audio_hz,
                        quality: row.quality,
                        fixture: false,
                        slot_start_millis: row.slot_start_millis,
                        exact_slot_timing: row.exact_slot_timing,
                        provenance: format!("NEXUS_NATIVE@{NEXUS_COMMIT}"),
                    };
                    if self.events.len() == MAX_EVENT_HISTORY {
                        self.events.pop_front();
                    }
                    self.events.push_back(event);
                }
                live_audio::WorkerEvent::Waterfall(mut frame) => {
                    self.sequence = self.sequence.saturating_add(1);
                    frame.sequence = self.sequence;
                    if self.waterfall.len() == 8 {
                        self.waterfall.pop_front();
                    }
                    self.waterfall.push_back(frame);
                }
                live_audio::WorkerEvent::DeviceLost => {
                    self.stop_latched = true;
                    self.state = RuntimeState::RxUnconfirmed;
                }
            }
        }
    }

    pub fn encode_to_null(&self, mode: DigiMode, message: &str) -> Result<String, RuntimeError> {
        if TX_ENABLED {
            return Err(RuntimeError::TxLocked);
        }
        let samples = match mode {
            DigiMode::Ft8 => ft8::gen_wave(&ft8::encode(message), ft8::SAMPLE_RATE, 1500.0),
            DigiMode::Ft4 => ft4::gen_wave(&ft4::encode(message), ft4::SAMPLE_RATE, 1500.0),
        };
        if samples.is_empty() {
            return Err(RuntimeError::InvalidProfile);
        }
        let mut digest = Sha256::new();
        for sample in samples {
            digest.update(sample.to_le_bytes());
        }
        Ok(hex::encode(digest.finalize()))
    }

    pub fn process(&mut self, envelope: CommandEnvelope, launch_nonce: &str) -> CommandResult {
        #[cfg(feature = "live-audio")]
        self.poll_live_events();
        let id = envelope.command_id.clone();
        let fail = |code: &str, generation| CommandResult {
            version: CONTRACT_VERSION,
            command_id: id.clone(),
            generation,
            ok: false,
            code: code.into(),
            payload: Value::Null,
        };
        if envelope.version != CONTRACT_VERSION
            || envelope.command_id.is_empty()
            || envelope.command_id.len() > 128
            || envelope.launch_nonce != launch_nonce
        {
            return fail("IPC_ENVELOPE_REJECTED", self.generation);
        }
        if envelope.generation != self.generation
            && !matches!(
                envelope.command,
                RuntimeCommand::Identity
                    | RuntimeCommand::Snapshot
                    | RuntimeCommand::Presence
                    | RuntimeCommand::Stop { .. }
            )
        {
            return fail("STALE_GENERATION", self.generation);
        }
        let outcome: Result<(String, Value), RuntimeError> = match envelope.command {
            RuntimeCommand::Identity => Ok((
                "ENGINE_IDENTITY".into(),
                serde_json::to_value(EngineIdentity::default()).unwrap(),
            )),
            RuntimeCommand::Snapshot => Ok((
                "RUNTIME_SNAPSHOT".into(),
                serde_json::to_value(self.snapshot()).unwrap(),
            )),
            RuntimeCommand::Presence => {
                #[cfg(feature = "live-audio")]
                let (input_devices, output_devices) = Self::enumerate_audio_devices();
                #[cfg(not(feature = "live-audio"))]
                let (input_devices, output_devices) = (Vec::new(), Vec::new());
                Ok((
                    "RUNTIME_PRESENCE".into(),
                    serde_json::to_value(RuntimePresence {
                        snapshot: self.snapshot(),
                        decodes: self.recent_events(0, 200),
                        waterfall: self.recent_waterfall(),
                        input_devices,
                        output_devices,
                    })
                    .unwrap(),
                ))
            }
            RuntimeCommand::ConfigureRx(profile) => self.configure_rx(profile).map(|_| {
                (
                    "RX_CONFIGURED_INERT".into(),
                    serde_json::to_value(self.snapshot()).unwrap(),
                )
            }),
            RuntimeCommand::StartReceiving => self
                .start_receiving()
                .map(|_| ("RX_STARTED".into(), Value::Null)),
            RuntimeCommand::Stop {
                reason: _,
                radio_rx_readback,
            } => Ok((
                self.stop(radio_rx_readback).into(),
                serde_json::to_value(self.snapshot()).unwrap(),
            )),
            RuntimeCommand::QueueReviewedContact(contact) => self
                .queue
                .enqueue(contact)
                .map(|id| ("CONTACT_DURABLE_PENDING".into(), Value::String(id)))
                .map_err(RuntimeError::from),
            RuntimeCommand::PendingReviewedContacts => Ok((
                "CONTACT_PENDING_LIST".into(),
                serde_json::to_value(
                    self.queue
                        .pending()
                        .iter()
                        .take(8)
                        .cloned()
                        .collect::<Vec<_>>(),
                )
                .unwrap(),
            )),
            RuntimeCommand::AcknowledgeContact {
                event_id,
                durable_receipt,
            } => self
                .queue
                .acknowledge(&event_id, durable_receipt)
                .map(|_| ("CONTACT_RECEIPT_APPLIED".into(), Value::Null))
                .map_err(RuntimeError::from),
        };
        match outcome {
            Ok((code, payload)) => CommandResult {
                version: CONTRACT_VERSION,
                command_id: id,
                generation: self.generation,
                ok: true,
                code,
                payload,
            },
            Err(error) => fail(
                &error.to_string().to_uppercase().replace(' ', "_"),
                self.generation,
            ),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AudioDeviceInfo {
    pub id: String,
    pub label: String,
}

fn now_unix_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}
