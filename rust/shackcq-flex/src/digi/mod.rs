// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 KD9TAW <kd9taw@protonmail.com>
// Copyright (C) 2026 Oliver Bross
//
// Digital-mode DSP is derived from Nexus commit
// 6ec4a7925f1550cc364c7fd95967ce38c696ad3f. See ../../UPSTREAM.md.

pub mod cw;
pub mod cw_decode;
pub mod rtty {
    pub mod afsk;
    pub mod baudot;
    pub mod demod;
}
pub mod spectrum;
pub mod wsjt;
pub mod psk31;

use rtty::afsk::{afsk_char_samples, AfskConfig};
use rtty::baudot::{code_bits, BaudotEncoder};
use rtty::demod::{RttyConfig, RttyDemod, RttyDemodulator};

pub use cw::{morse_duration_ms, morse_samples};
pub use cw_decode::CwStreamDecoder;
pub use tempo_sstv;

pub fn rtty_samples(text: &str, sample_rate: u32, reverse: bool) -> Vec<f32> {
    let codes = BaudotEncoder::new(true).encode(text);
    let bits = code_bits(&codes);
    afsk_char_samples(
        &bits,
        &AfskConfig {
            sample_rate,
            reverse,
            ..AfskConfig::default()
        },
    )
}

pub struct RttyStreamDecoder {
    candidates: Vec<RttyCandidate>,
    best: usize,
}

struct RttyCandidate {
    inner: RttyDemodulator,
    transcript: String,
    score: f32,
}

impl RttyStreamDecoder {
    pub fn new(reverse: bool) -> Self {
        // Sound-card software does not use one universal audio centre. The
        // standard 2125/2295-Hz pair is common, while waterfalls and published
        // reference recordings frequently centre the same 170-Hz shift at
        // 1000, 1500, 1700 or 2500 Hz. Run a small, bounded decoder bank and
        // select by accumulated soft confidence; each member retains its own
        // bit clock and AFC state across audio blocks.
        let centres = [2210.0, 1000.0, 1500.0, 1700.0, 2500.0];
        let candidates = centres.into_iter().map(|centre| {
            let low = centre - 85.0;
            let high = centre + 85.0;
            let (mark_hz, space_hz) = if reverse { (high, low) } else { (low, high) };
            RttyCandidate {
                inner: RttyDemodulator::new(RttyConfig {
                    mark_hz,
                    space_hz,
                    ..RttyConfig::default()
                }),
                transcript: String::new(),
                score: 0.0,
            }
        }).collect();
        Self {
            candidates,
            best: 0,
        }
    }

    pub fn new_at(reverse: bool, centre: f32) -> Self {
        let centre = centre.clamp(300.0, 3_500.0);
        let low = centre - 85.0;
        let high = centre + 85.0;
        let (mark_hz, space_hz) = if reverse { (high, low) } else { (low, high) };
        Self {
            candidates: vec![RttyCandidate {
                inner: RttyDemodulator::new(RttyConfig { mark_hz, space_hz, ..RttyConfig::default() }),
                transcript: String::new(), score: 0.0,
            }],
            best: 0,
        }
    }

    pub fn push(&mut self, samples: &[f32]) -> &str {
        for candidate in &mut self.candidates {
            for decoded in candidate.inner.feed(samples) {
                if decoded.confidence >= 0.12 {
                    candidate.transcript.push(decoded.ch);
                    candidate.score += decoded.confidence;
                }
            }
            if candidate.transcript.len() > 4096 {
                let keep_from = candidate.transcript.len() - 4096;
                candidate.transcript.drain(..keep_from);
            }
        }
        self.best = self.candidates.iter().enumerate()
            .max_by(|(_, a), (_, b)| a.score.total_cmp(&b.score))
            .map(|(index, _)| index)
            .unwrap_or(0);
        &self.candidates[self.best].transcript
    }

    pub fn afc_offset_hz(&self) -> f32 { self.candidates[self.best].inner.afc_offset_hz() }
    pub fn afc_locked(&self) -> bool { self.candidates[self.best].inner.afc_locked() }
    pub fn clear(&mut self) {
        for candidate in &mut self.candidates {
            candidate.transcript.clear();
            candidate.score = 0.0;
            candidate.inner.reset();
        }
        self.best = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn samples_at(text: &str, centre_hz: f32) -> Vec<f32> {
        let codes = BaudotEncoder::new(true).encode(text);
        let bits = code_bits(&codes);
        afsk_char_samples(&bits, &AfskConfig {
            mark_hz: centre_hz - 85.0,
            space_hz: centre_hz + 85.0,
            ..AfskConfig::default()
        })
    }

    #[test]
    fn rtty_stream_acquires_standard_and_waterfall_centres() {
        for centre in [1000.0, 1500.0, 1700.0, 2210.0, 2500.0] {
            let mut decoder = RttyStreamDecoder::new(false);
            let audio = samples_at("THE QUICK BROWN FOX 123", centre);
            let mut text = String::new();
            for chunk in audio.chunks(479) {
                text = decoder.push(chunk).to_owned();
            }
            assert!(text.contains("QUICK BROWN FOX"), "centre {centre}: {text:?}");
        }
    }
}
