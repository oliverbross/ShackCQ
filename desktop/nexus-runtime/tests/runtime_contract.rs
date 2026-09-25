// SPDX-License-Identifier: GPL-3.0-only
use serde_json::json;
use shackcq_nexus_runtime::*;

fn runtime() -> (tempfile::TempDir, StationRuntime) {
    let dir = tempfile::tempdir().unwrap();
    let runtime =
        StationRuntime::new(dir.path().join("queue.bin"), QueueKey::from_bytes([7; 32])).unwrap();
    (dir, runtime)
}

#[test]
fn identity_is_exact_and_has_no_legacy_fallback() {
    let (_dir, runtime) = runtime();
    let snapshot = runtime.snapshot();
    assert_eq!(snapshot.identity.upstream_commit, NEXUS_COMMIT);
    assert_eq!(
        snapshot.identity.compiled_modes,
        vec![
            DigiMode::Ft8,
            DigiMode::Ft4,
            DigiMode::Ft2,
            DigiMode::Fst4,
            DigiMode::Fst4w,
            DigiMode::Q65,
            DigiMode::Msk144,
            DigiMode::Jt65,
            DigiMode::Wspr,
        ]
    );
    assert!(!snapshot.identity.legacy_fallback);
    assert!(!snapshot.capabilities.tx_enabled);
    assert!(!snapshot.capabilities.audio_output);
    assert!(!snapshot.capabilities.external_loggers);
    assert_eq!(snapshot.capabilities.cat_owner, "SHACKCQ_HAMLIB_HELPER");
}

#[test]
fn configuration_is_inert_and_stop_fails_closed() {
    let (_dir, mut runtime) = runtime();
    runtime
        .configure_rx(RxProfile {
            device_id: "coreaudio:usb-codec:input".into(),
            output_device_id: Some("coreaudio:usb-codec:output".into()),
            channel: 1,
            input_rate_hz: 48_000,
            mode: DigiMode::Ft8,
            submode: None,
        })
        .unwrap();
    assert_eq!(runtime.snapshot().state, RuntimeState::Configured);
    assert!(matches!(
        runtime.start_receiving(),
        Err(RuntimeError::AudioBackendUnavailable)
    ));
    assert_eq!(runtime.stop(None), "RX_UNCONFIRMED");
    assert_eq!(runtime.snapshot().state, RuntimeState::RxUnconfirmed);
    assert!(runtime.snapshot().stop_latched);
    assert_eq!(runtime.stop(Some(true)), "RX_VERIFIED");
}

#[test]
fn every_compiled_encoder_can_only_be_observed_through_null_digest() {
    let (_dir, runtime) = runtime();
    let cases = [
        (DigiMode::Ft8, "CQ KD9TAW EN52"),
        (DigiMode::Ft4, "CQ KD9TAW EN52"),
        (DigiMode::Ft2, "CQ KD9TAW EN52"),
        (DigiMode::Fst4, "K1ABC W9XYZ EN37"),
        (DigiMode::Fst4w, "KD9TAW EN52 30"),
        (DigiMode::Q65, "K1ABC W9XYZ EN37"),
        (DigiMode::Msk144, "K1ABC W9XYZ EN37"),
        (DigiMode::Jt65, "K1ABC W9XYZ EN37"),
        (DigiMode::Wspr, "KD9TAW EN52 30"),
    ];

    for (mode, message) in cases {
        let digest = runtime
            .encode_to_null(mode, message)
            .unwrap_or_else(|error| panic!("{mode:?} null encode failed: {error}"));
        assert_eq!(digest.len(), 64, "{mode:?} digest must be SHA-256 hex");
    }
    assert!(!runtime.snapshot().capabilities.audio_output);
}

#[test]
fn every_qso_mode_completes_the_nexus_auto_sequence_on_the_null_sink() {
    let (_dir, mut runtime) = runtime();
    for mode in [
        DigiMode::Ft8,
        DigiMode::Ft4,
        DigiMode::Ft2,
        DigiMode::Fst4,
        DigiMode::Q65,
        DigiMode::Msk144,
        DigiMode::Jt65,
    ] {
        let result = runtime
            .run_emulated_sequence(EmulatedSequenceRequest {
                mode,
                local_callsign: "OM0RX".into(),
                local_grid: "JN88TQ".into(),
                remote_callsign: "K1ABC".into(),
                remote_grid: "FN31PR".into(),
                snr_db: -10,
                max_slots: 20,
            })
            .unwrap_or_else(|error| panic!("{mode:?} sequence failed: {error}"));
        assert!(result.qso_complete, "{mode:?}: {result:?}");
        assert!(!result.beacon_complete);
        assert_eq!(result.transport, "IN_MEMORY_NULL_SINK");
        assert!(result.steps.len() >= 6, "{mode:?}: {result:?}");
        assert!(result
            .steps
            .iter()
            .all(|step| step.waveform_sha256.len() == 64));
        let messages = result
            .steps
            .iter()
            .map(|step| step.message.as_str())
            .collect::<Vec<_>>();
        assert!(messages
            .first()
            .is_some_and(|message| message.starts_with("CQ OM0RX JN88")));
        assert!(messages.iter().any(|message| message.contains(" R-10")));
        assert!(messages.iter().any(|message| message.ends_with(" RR73")));
        assert!(messages
            .last()
            .is_some_and(|message| message.ends_with(" 73")));
    }
    let snapshot = runtime.snapshot();
    assert!(!snapshot.capabilities.tx_enabled);
    assert!(!snapshot.capabilities.audio_output);
}

#[test]
fn beacon_modes_encode_one_bounded_frame_and_never_claim_a_qso() {
    let (_dir, mut runtime) = runtime();
    for mode in [DigiMode::Fst4w, DigiMode::Wspr] {
        let result = runtime
            .run_emulated_sequence(EmulatedSequenceRequest {
                mode,
                local_callsign: "OM0RX".into(),
                local_grid: "JN88TQ".into(),
                remote_callsign: "K1ABC".into(),
                remote_grid: "FN31PR".into(),
                snr_db: -10,
                max_slots: 20,
            })
            .unwrap_or_else(|error| panic!("{mode:?} beacon failed: {error}"));
        assert!(result.beacon_complete);
        assert!(!result.qso_complete);
        assert_eq!(result.steps.len(), 1);
        assert_eq!(result.steps[0].direction, "LOCAL_TO_NULL_SINK");
        assert_eq!(result.steps[0].waveform_sha256.len(), 64);
    }
}

#[test]
fn emulated_sequence_round_trips_through_the_supervised_runtime_contract() {
    let (_dir, mut runtime) = runtime();
    let result = runtime.process(
        CommandEnvelope {
            version: CONTRACT_VERSION,
            command_id: "emulation-1".into(),
            generation: 1,
            launch_nonce: "nonce".into(),
            command: RuntimeCommand::RunEmulatedSequence(EmulatedSequenceRequest {
                mode: DigiMode::Ft2,
                local_callsign: "OM0RX".into(),
                local_grid: "JN88TQ".into(),
                remote_callsign: "K1ABC".into(),
                remote_grid: "FN31PR".into(),
                snr_db: -10,
                max_slots: 20,
            }),
        },
        "nonce",
    );
    assert!(result.ok, "{result:?}");
    assert_eq!(result.code, "EMULATED_SEQUENCE_COMPLETE");
    let presence = runtime.process(
        CommandEnvelope {
            version: CONTRACT_VERSION,
            command_id: "presence-1".into(),
            generation: 1,
            launch_nonce: "nonce".into(),
            command: RuntimeCommand::Presence,
        },
        "nonce",
    );
    let presence: RuntimePresence = serde_json::from_value(presence.payload).unwrap();
    assert!(presence.snapshot.emulation.unwrap().qso_complete);
    assert!(!presence.snapshot.capabilities.tx_enabled);
}

#[test]
fn encrypted_review_queue_is_partitioned_deduplicated_and_receipt_gated() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("queue.bin");
    let key = QueueKey::from_bytes([9; 32]);
    let mut queue = ContactQueue::open(&path, key.clone()).unwrap();
    let contact = ReviewedContact {
        event_id: "fixture-completed-1".into(),
        operation_identity: "fixture-completed-1".into(),
        profile_id: "native-profile".into(),
        source_revision: 1,
        account_id: "test-account".into(),
        station_profile_id: "test-station".into(),
        destination_authority: "WEB_LOCAL".into(),
        authority_revision: 3,
        mapping_revision: None,
        captured_utc: "2026-09-12T00:00:00Z".into(),
        provenance: "NEXUS_NATIVE".into(),
        fixture: true,
        contact: json!({"callsign":"W1AW","mode":"FT8"}),
    };
    let id = queue.enqueue(contact.clone()).unwrap();
    assert_eq!(id, queue.enqueue(contact.clone()).unwrap());
    assert_eq!(queue.len(), 1);
    let mut migrated = contact;
    migrated.profile_id = "native-profile-2".into();
    migrated.authority_revision = 4;
    let migrated_id = queue.enqueue(migrated).unwrap();
    assert_ne!(id, migrated_id);
    assert_eq!(queue.len(), 2);
    let raw = std::fs::read_to_string(&path).unwrap();
    assert!(!raw.contains("W1AW"));
    assert!(matches!(
        queue.acknowledge(&id, false),
        Err(QueueError::ReceiptRequired)
    ));
    drop(queue);
    let mut reopened = ContactQueue::open(&path, key).unwrap();
    assert_eq!(reopened.len(), 2);
    reopened.acknowledge(&id, true).unwrap();
    reopened.acknowledge(&migrated_id, true).unwrap();
    assert_eq!(reopened.len(), 0);
}

#[test]
fn generation_and_launch_nonce_are_enforced_but_stop_is_never_stale() {
    let (_dir, mut runtime) = runtime();
    let configure = CommandEnvelope {
        version: CONTRACT_VERSION,
        command_id: "c1".into(),
        generation: 1,
        launch_nonce: "nonce".into(),
        command: RuntimeCommand::ConfigureRx(RxProfile {
            device_id: "fixture".into(),
            output_device_id: None,
            channel: 0,
            input_rate_hz: 12_000,
            mode: DigiMode::Ft4,
            submode: None,
        }),
    };
    assert!(runtime.process(configure, "nonce").ok);
    let stale = CommandEnvelope {
        version: CONTRACT_VERSION,
        command_id: "c2".into(),
        generation: 1,
        launch_nonce: "nonce".into(),
        command: RuntimeCommand::StartReceiving,
    };
    assert_eq!(runtime.process(stale, "nonce").code, "STALE_GENERATION");
    let stop = CommandEnvelope {
        version: CONTRACT_VERSION,
        command_id: "c3".into(),
        generation: 1,
        launch_nonce: "nonce".into(),
        command: RuntimeCommand::Stop {
            reason: "operator".into(),
            radio_rx_readback: None,
        },
    };
    assert_eq!(runtime.process(stop, "nonce").code, "RX_UNCONFIRMED");
}

#[test]
fn utc_slot_alignment_discards_prefix_and_resets_on_clock_step() {
    let mut aligner = shackcq_nexus_runtime::SlotFrameAligner::new(DigiMode::Ft4);
    assert!(aligner.push_chunk(7_000.0, &vec![1i16; 6_000]).is_empty());
    let frames = aligner.push_chunk(7_500.0, &vec![2i16; 90_000]);
    assert_eq!(frames.len(), 1);
    assert_eq!(frames[0].slot_start_millis, 7_500);
    assert!(!frames[0].exact_slot_timing);
    assert_eq!(frames[0].samples.len(), ft4::NMAX);
    assert!(aligner.push_chunk(30_000.0, &[0; 120]).is_empty());
    assert_eq!(aligner.clock_resets(), 1);
}
