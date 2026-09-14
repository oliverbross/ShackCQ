// SPDX-License-Identifier: GPL-3.0-only
use sha2::{Digest, Sha256};
use shackcq_nexus_runtime::{
    CommandEnvelope, DigiMode, FrameDecoder, QueueKey, RuntimeCommand, StationRuntime,
    CONTRACT_VERSION,
};

fn read_wav(path: &std::path::Path) -> Vec<i16> {
    let bytes = std::fs::read(path).unwrap();
    let mut offset = 12usize;
    while offset + 8 <= bytes.len() {
        let size = u32::from_le_bytes(bytes[offset + 4..offset + 8].try_into().unwrap()) as usize;
        let body = offset + 8;
        if &bytes[offset..offset + 4] == b"data" {
            return bytes[body..(body + size).min(bytes.len())]
                .chunks_exact(2)
                .map(|c| i16::from_le_bytes([c[0], c[1]]))
                .collect();
        }
        offset = body + size + (size & 1);
    }
    panic!("WAV data chunk absent")
}

#[test]
fn licensed_upstream_ft8_and_ft4_recordings_cross_shackcq_adapter() {
    let root =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../third_party/nexus/crates");
    let ft8_path = root.join("ft8/tests/fixtures/ft8_sample.wav");
    assert_eq!(
        hex::encode(Sha256::digest(std::fs::read(&ft8_path).unwrap())),
        "9feb99c275770a6618538026da7decc6b09eb6cf63121e5168fa86dcdf00c2f5"
    );
    let mut ft8 = read_wav(&ft8_path);
    ft8.resize(ft8::NMAX, 0);
    let d8 = FrameDecoder::process_capture_frame(DigiMode::Ft8, &ft8).unwrap();
    assert!(d8.decodes.iter().any(|d| d.message == "CQ F5RXL IN94"));
    assert_eq!(d8.waterfall_bins.len(), 1024);
    let ft4_path = root.join("ft4/tests/fixtures/ft4_sample.wav");
    assert_eq!(
        hex::encode(Sha256::digest(std::fs::read(&ft4_path).unwrap())),
        "d9e91fa04ba138a7b9f41b4103823c77ca1c3a9775101f6b14d60935bcd3813b"
    );
    let mut ft4 = read_wav(&ft4_path);
    ft4.resize(ft4::NMAX, 0);
    let d4 = FrameDecoder::process_capture_frame(DigiMode::Ft4, &ft4).unwrap();
    assert!(d4.decodes.iter().any(|d| d.message == "CQ RU N9OY EN43"));
    assert_eq!(d4.waterfall_bins.len(), 1024);
}

#[test]
fn nexus_capture_resampler_is_stateful_across_chunks() {
    let input: Vec<f32> = (0..48_000).map(|n| ((n as f32) * 0.01).sin()).collect();
    let once = shackcq_nexus_runtime::StationRuntime::resample_capture(48_000, &[&input]);
    let chunked = shackcq_nexus_runtime::StationRuntime::resample_capture(
        48_000,
        &[&input[..17_321], &input[17_321..]],
    );
    assert_eq!(once, chunked);
    // Nexus documents a fixed FIR tail latency of about 16 output samples.
    assert!((once.len() as isize - 12_000).abs() <= 20);
}

#[test]
fn runtime_file_command_decodes_the_off_air_recording_as_reference_only() {
    let root =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../third_party/nexus/crates");
    let fixture = root.join("ft8/tests/fixtures/ft8_sample.wav");
    assert_eq!(read_wav(&fixture).len(), ft8::NMAX);
    let digest = hex::encode(Sha256::digest(std::fs::read(&fixture).unwrap()));
    let dir = tempfile::tempdir().unwrap();
    let mut runtime =
        StationRuntime::new(dir.path().join("queue"), QueueKey::from_bytes([7; 32])).unwrap();
    let pending_before = runtime.snapshot().pending_contacts;
    let result = runtime.process(
        CommandEnvelope {
            version: CONTRACT_VERSION,
            command_id: "reference-file-1".into(),
            generation: 1,
            launch_nonce: "test-nonce".into(),
            command: RuntimeCommand::DecodeRecordingFile {
                path: fixture.to_string_lossy().into_owned(),
                sha256: digest,
                mode: DigiMode::Ft8,
            },
        },
        "test-nonce",
    );
    assert!(result.ok, "{}", result.code);
    assert_eq!(result.code, "REFERENCE_RECORDING_DECODED");
    assert!(runtime.recent_events(0, 200).iter().any(|event| {
        event.fixture && !event.exact_slot_timing && event.message == "CQ F5RXL IN94"
    }));
    assert_eq!(runtime.recent_waterfall().last().unwrap().bins.len(), 1024);
    assert_eq!(runtime.snapshot().pending_contacts, pending_before);

    let wrong_digest = runtime.process(
        CommandEnvelope {
            version: CONTRACT_VERSION,
            command_id: "reference-file-wrong-digest".into(),
            generation: 1,
            launch_nonce: "test-nonce".into(),
            command: RuntimeCommand::DecodeRecordingFile {
                path: fixture.to_string_lossy().into_owned(),
                sha256: "0".repeat(64),
                mode: DigiMode::Ft8,
            },
        },
        "test-nonce",
    );
    assert!(!wrong_digest.ok);
    assert_eq!(wrong_digest.code, "INVALID_REFERENCE_RECORDING");
}
