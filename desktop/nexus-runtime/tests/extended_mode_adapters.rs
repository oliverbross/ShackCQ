// SPDX-License-Identifier: GPL-3.0-only
//! Production capture-frame proofs for Nexus modes without distributable off-air fixtures.
//! These are explicitly synthetic Nexus encoder-to-decoder recordings, not reception claims.

use shackcq_nexus_runtime::{DigiMode, FrameDecoder};

const MESSAGE: &str = "K1ABC W9XYZ EN37";

fn contains(output: shackcq_nexus_runtime::CaptureFrameOutput) -> bool {
    output
        .decodes
        .iter()
        .any(|row| row.message.trim() == MESSAGE)
}

#[test]
fn ft2_fst4_q65_and_msk144_use_the_production_capture_adapter() {
    let ft2_wave = ft2::gen_wave(&ft2::encode(MESSAGE).unwrap(), 1200.0).unwrap();
    let mut ft2_frame = vec![0i16; ft2::NMAX];
    let ft2_start = (0.5 * ft2::SAMPLE_RATE) as usize;
    for (index, sample) in ft2_wave.iter().enumerate() {
        ft2_frame[ft2_start + index] = (sample * 8000.0) as i16;
    }
    assert!(contains(
        FrameDecoder::process_configured_capture_frame(DigiMode::Ft2, None, &ft2_frame, 3_750,)
            .unwrap()
    ));

    let fst4_wave = fst4::gen_wave(
        &fst4::encode(MESSAGE, false).unwrap(),
        15,
        1,
        fst4::SAMPLE_RATE,
        1500.0,
    )
    .unwrap();
    let mut fst4_frame = vec![0i16; fst4::nmax(15)];
    let fst4_start = (fst4::lead_in_secs(15) * fst4::SAMPLE_RATE) as usize;
    for (index, sample) in fst4_wave.iter().enumerate() {
        if fst4_start + index < fst4_frame.len() {
            fst4_frame[fst4_start + index] = (sample * 8000.0) as i16;
        }
    }
    assert!(contains(
        FrameDecoder::process_configured_capture_frame(
            DigiMode::Fst4,
            Some("15"),
            &fst4_frame,
            15_000,
        )
        .unwrap()
    ));

    let q65_wave = q65::gen_wave(
        &q65::encode(MESSAGE).unwrap(),
        30,
        0,
        q65::SAMPLE_RATE,
        1500.0,
    )
    .unwrap();
    let mut q65_frame = vec![0i16; q65::nmax(30)];
    let q65_start = (q65::lead_in_secs(30) * q65::SAMPLE_RATE) as usize;
    for (index, sample) in q65_wave.iter().enumerate() {
        if q65_start + index < q65_frame.len() {
            q65_frame[q65_start + index] = (sample * 8000.0) as i16;
        }
    }
    assert!(contains(
        FrameDecoder::process_configured_capture_frame(
            DigiMode::Q65,
            Some("30A"),
            &q65_frame,
            30_000,
        )
        .unwrap()
    ));

    let msk_wave = msk144::gen_wave(
        &msk144::encode(MESSAGE).unwrap(),
        15,
        msk144::SAMPLE_RATE,
        msk144::TX_CENTRE_HZ,
    )
    .unwrap();
    let mut msk_frame = vec![0i16; msk144::nmax(15)];
    for (index, sample) in msk_wave.iter().enumerate() {
        if index < msk_frame.len() {
            msk_frame[index] = (sample * 8000.0) as i16;
        }
    }
    assert!(contains(
        FrameDecoder::process_configured_capture_frame(
            DigiMode::Msk144,
            Some("15"),
            &msk_frame,
            45_000,
        )
        .unwrap()
    ));
}

#[test]
fn jt65a_uses_the_production_capture_adapter_with_a_realistic_noise_floor() {
    let wave = jt65::gen_wave(
        &jt65::encode(MESSAGE).unwrap(),
        0,
        jt65::SAMPLE_RATE,
        1500.0,
    )
    .unwrap();
    let mut frame = vec![0i16; jt65::NMAX];
    let mut seed: u32 = 0xA5A5;
    for sample in &mut frame {
        seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
        *sample = (((seed >> 16) % 347) as i16) - 173;
    }
    let start = (jt65::LEAD_IN_SECS * jt65::SAMPLE_RATE) as usize;
    for (index, sample) in wave.iter().enumerate() {
        if start + index < frame.len() {
            frame[start + index] = frame[start + index].saturating_add((sample * 160.0) as i16);
        }
    }
    assert!(contains(
        FrameDecoder::process_configured_capture_frame(DigiMode::Jt65, Some("A"), &frame, 60_000,)
            .unwrap()
    ));
}

#[test]
fn invalid_mode_periods_fail_closed_before_decoder_entry() {
    assert!(
        FrameDecoder::process_configured_capture_frame(DigiMode::Q65, Some("45Z"), &[], 0,)
            .is_err()
    );
    assert!(
        FrameDecoder::process_configured_capture_frame(DigiMode::Msk144, Some("60"), &[], 0,)
            .is_err()
    );
    assert!(
        FrameDecoder::process_configured_capture_frame(DigiMode::Fst4, Some("bogus"), &[], 0,)
            .is_err()
    );
    assert!(
        FrameDecoder::process_configured_capture_frame(DigiMode::Q65, Some("💥A"), &[], 0,)
            .is_err()
    );
    assert!(
        FrameDecoder::process_configured_capture_frame(DigiMode::Jt65, Some("AA"), &[], 0,)
            .is_err()
    );
}
