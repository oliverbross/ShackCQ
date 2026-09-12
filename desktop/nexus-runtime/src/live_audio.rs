// SPDX-License-Identifier: GPL-3.0-only
//! Feature-gated receive-only capture. The callback only fills Nexus's wait-free ring;
//! resampling and modem work run on bounded workers. No output/CAT/logger is constructible.

use crate::{DigiMode, FrameDecoder, RuntimeError, RxProfile, SlotFrameAligner, WaterfallFrame};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, StreamConfig};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tempo_audio::capture_resample::CaptureResampler;
use tempo_audio::monitor::SpscRing;

pub struct WorkerDecode {
    pub mode: DigiMode,
    pub message: String,
    pub snr_db: i32,
    pub dt_seconds: f32,
    pub audio_hz: f32,
    pub quality: f32,
    pub slot_start_millis: u64,
    pub exact_slot_timing: bool,
}
pub enum WorkerEvent {
    Decode(WorkerDecode),
    Waterfall(WaterfallFrame),
    DeviceLost,
}

pub struct LiveReceiver {
    cancel: Arc<AtomicBool>,
    events: mpsc::Receiver<WorkerEvent>,
    opened_sample_rate: u32,
}

impl LiveReceiver {
    pub fn start(profile: RxProfile) -> Result<Self, RuntimeError> {
        let input = LiveInput::open(&profile)?;
        let opened_sample_rate = input.opened_sample_rate;
        let cancel = Arc::new(AtomicBool::new(false));
        let (frame_tx, frame_rx) = mpsc::sync_channel::<(u64, bool, Vec<i16>)>(2);
        let (event_tx, events) = mpsc::sync_channel::<WorkerEvent>(32);
        let capture_cancel = cancel.clone();
        let decode_events = event_tx.clone();
        let mode = profile.mode;
        std::thread::Builder::new()
            .name("shackcq-rx-capture".into())
            .spawn(move || {
                let mut input = input;
                let mut aligner = SlotFrameAligner::new(mode);
                while !capture_cancel.load(Ordering::Acquire) {
                    if input.failed.load(Ordering::Relaxed) {
                        let _ = event_tx.try_send(WorkerEvent::DeviceLost);
                        break;
                    }
                    let drained = input.drain();
                    let pcm = drained
                        .iter()
                        .map(|sample| (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)
                        .collect::<Vec<_>>();
                    let end = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs_f64()
                        * 1000.0;
                    let start = end - pcm.len() as f64 / 12.0;
                    for frame in aligner.push_chunk(start, &pcm) {
                        let _ = frame_tx.try_send((
                            frame.slot_start_millis,
                            frame.exact_slot_timing,
                            frame.samples,
                        ));
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                // Stream is dropped here independently of any stuck decoder.
            })
            .map_err(|_| RuntimeError::AudioBackendUnavailable)?;
        std::thread::Builder::new()
            .name("shackcq-nexus-decode".into())
            .spawn(move || {
                while let Ok((slot_start, exact, frame)) = frame_rx.recv() {
                    if let Ok(output) = FrameDecoder::process_capture_frame(mode, &frame) {
                        let _ = decode_events.try_send(WorkerEvent::Waterfall(WaterfallFrame {
                            sequence: 0,
                            observed_unix_millis: slot_start,
                            low_hz: 0,
                            high_hz: 6000,
                            bins: output.waterfall_bins,
                        }));
                        for d in output.decodes {
                            let _ = decode_events.try_send(WorkerEvent::Decode(WorkerDecode {
                                mode,
                                message: d.message,
                                snr_db: d.snr_db,
                                dt_seconds: d.dt_seconds,
                                audio_hz: d.audio_hz,
                                quality: d.quality,
                                slot_start_millis: slot_start,
                                exact_slot_timing: exact,
                            }));
                        }
                    }
                }
            })
            .map_err(|_| RuntimeError::AudioBackendUnavailable)?;
        Ok(Self {
            cancel,
            events,
            opened_sample_rate,
        })
    }
    pub fn drain_events(&mut self) -> Vec<WorkerEvent> {
        self.events.try_iter().take(64).collect()
    }
    pub fn opened_sample_rate(&self) -> u32 {
        self.opened_sample_rate
    }
}
impl Drop for LiveReceiver {
    fn drop(&mut self) {
        self.cancel.store(true, Ordering::Release);
    }
}

struct LiveInput {
    _stream: Stream,
    ring: Arc<SpscRing>,
    resampler: CaptureResampler,
    failed: Arc<AtomicBool>,
    opened_sample_rate: u32,
}
impl LiveInput {
    fn open(profile: &RxProfile) -> Result<Self, RuntimeError> {
        let host = cpal::default_host();
        let mut ordinal = 1usize;
        let mut wanted = profile.device_id.as_str();
        if let Some((base, suffix)) = profile.device_id.rsplit_once(" #") {
            if let Ok(n) = suffix.parse() {
                wanted = base;
                ordinal = n;
            }
        }
        let mut seen = 0usize;
        let device = host
            .input_devices()
            .map_err(|_| RuntimeError::AudioBackendUnavailable)?
            .find(|d| {
                let name = d.description().ok().map(|x| x.name().to_string());
                if name.as_deref() == Some(wanted) {
                    seen += 1;
                    seen == ordinal
                } else {
                    false
                }
            })
            .ok_or(RuntimeError::AudioBackendUnavailable)?;
        let supported = device
            .default_input_config()
            .map_err(|_| RuntimeError::AudioBackendUnavailable)?;
        let channels = supported.channels() as usize;
        let selected = profile.channel as usize;
        if selected >= channels {
            return Err(RuntimeError::InvalidProfile);
        }
        let rate = supported.sample_rate();
        let config: StreamConfig = supported.clone().into();
        let ring = Arc::new(SpscRing::new((rate as usize).saturating_mul(4).max(4096)));
        let failed = Arc::new(AtomicBool::new(false));
        let stream = match supported.sample_format() {
            SampleFormat::F32 => build_input::<f32, _>(
                &device,
                &config,
                channels,
                selected,
                ring.clone(),
                |v| v,
                failed.clone(),
            ),
            SampleFormat::I16 => build_input::<i16, _>(
                &device,
                &config,
                channels,
                selected,
                ring.clone(),
                |v| v as f32 / i16::MAX as f32,
                failed.clone(),
            ),
            SampleFormat::U16 => build_input::<u16, _>(
                &device,
                &config,
                channels,
                selected,
                ring.clone(),
                |v| (v as f32 / u16::MAX as f32) * 2.0 - 1.0,
                failed.clone(),
            ),
            _ => Err(RuntimeError::AudioBackendUnavailable),
        }?;
        stream
            .play()
            .map_err(|_| RuntimeError::AudioBackendUnavailable)?;
        Ok(Self {
            _stream: stream,
            ring,
            resampler: CaptureResampler::new(rate, 12_000),
            failed,
            opened_sample_rate: rate,
        })
    }
    fn drain(&mut self) -> Vec<f32> {
        let mut native = Vec::with_capacity(self.ring.len().min(self.ring.capacity()));
        while let Some(v) = self.ring.pop() {
            native.push(v)
        }
        self.resampler.process(&native)
    }
}
fn build_input<T, F>(
    device: &cpal::Device,
    config: &StreamConfig,
    channels: usize,
    selected: usize,
    ring: Arc<SpscRing>,
    convert: F,
    failed: Arc<AtomicBool>,
) -> Result<Stream, RuntimeError>
where
    T: cpal::SizedSample + Send + 'static,
    F: Fn(T) -> f32 + Send + 'static,
{
    device
        .build_input_stream(
            config.clone(),
            move |data: &[T], _| {
                for frame in data.chunks(channels) {
                    if let Some(&v) = frame.get(selected) {
                        let _ = ring.push(convert(v));
                    }
                }
            },
            move |_| failed.store(true, Ordering::Relaxed),
            None,
        )
        .map_err(|_| RuntimeError::AudioBackendUnavailable)
}
