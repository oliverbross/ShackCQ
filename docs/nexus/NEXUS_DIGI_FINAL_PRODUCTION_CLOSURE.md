# Nexus Digi v2 final production closure

## Scope

This source-only closure corrects the final Android production-adapter defects
without adding modes, providers or product families. No APK was installed and
no RF, PTT, TUNE, physical-audio or authenticated-service acceptance was run.

## Exact FT4 timing and schema v2

The previous production model stored slot starts as whole epoch seconds. FT4
slots at 7,500 ms and 22,500 ms therefore reconstructed as 7,000 ms and 22,000
ms and could select the wrong parity. `DigiDecodeEvent.slotStartMillis` now
retains exact epoch milliseconds through capture, identity, history, selection,
engine input, re-decode and WSJT-X milliseconds-since-midnight.

`shackcq-digi.sqlite` upgrades non-destructively from schema 1 to 2. It adds
`slot_start_millis`, `decode_source`, `timing_exact` and
`dial_frequency_hz`. Existing rows, sessions, drafts, gallery metadata and
history remain. Legacy rows are converted with
`period_start_epoch * 1000`, retain source `LIVE_CAPTURE`, and are explicitly
marked non-exact so they cannot start automatic S&P.

## Decode-source eligibility

Every decode is typed as `LIVE_CAPTURE`, `REDECODE_LIVE_SLOT`,
`REFERENCE_RECORDING` or `COMPANION`. Live capture must match the active mode,
dial frequency and session. A re-decode must additionally match the exact last
captured slot. Reference, companion and legacy timing remain display/enrichment
and manual-draft data only; they cannot key or advance the local sequencer.

## Scheduler and automatic/manual truth

UTC wall time identifies the slot. The runtime countdown and delay use the
planner's monotonic target. Wall time is sampled only for material-jump and
final parity/window validation; late start remains bounded to 120 ms.

`AUTO SEQUENCE` is now an explicit operator setting. Off means Call CQ/Call
Selected prepare one correct message/parity without a pending automatic engine
state. One-shot arm/SEND remains explicit. Auto-CQ is possible only when
automatic sequencing is enabled, and auto-log still requires a complete
automatic exchange with both reports.

## Radio completion and RX recovery

Elecraft success requires encoder success, DATA-mode acceptance, PTT and audio
completion, RX command, and `TQ` receive confirmation. Flex success occurs only
after `stopTransmit` and a bounded observation of `DISABLED`, `READY` or
`ARMED`; KEYING, TRANSMITTING, STOPPING, TUNING, FAULT or timeout cannot succeed.

An uncertain post-PTT state latches `RX_UNCONFIRMED`, disables TX/arm/automation,
blocks decoder restart and preserves the warning. `REQUEST RX & RECHECK` uses
the existing radio authority, never transmits, and clears the latch only after
the strongest available receive confirmation.

## Validation and artifacts

All three read-only watchers completed; Nexus and Wavelog reported no change.
Android host validation passed 422 unit tests with zero failures/errors,
compiled debug Android-test sources, and assembled the debug APK/AAB. Rust
passed 97 tests with 1 ignored. Debug shared-core CTest passed 2/2. Both package
audits passed the ITU/P.533 payload scan and remain within the 130 MB APK / 60 MB
AAB limits.

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 110,470,297 bytes,
  SHA-256 `5162fd99e6df83815c1493547e2402856da3bc1c0452f25b742d9d3f8e945ca9`.
- AAB: `android/app/build/outputs/bundle/debug/app-debug.aab`, 51,830,347 bytes,
  SHA-256 `5f43f811debedea9f0f44fafa352f09e318d1495fa6c4926d0624b474970beef`.

Physical acceptance remains in `NEXUS_DIGI_LIVE_ACCEPTANCE.md` and is
intentionally pending.
