# ShackCQ native QMX/QMX+ radio core v1

This branch provides the isolated Android QMX/QMX+ profile core. It is intentionally not wired into the central application in this branch.

## Source architecture

- `QmxModels.kt`: immutable radio/capability/USB/settings/diagnostic models and typed ports.
- `QmxProtocol.kt`: bounded parser, exact command builder, coalescing queue and CW split workaround.
- `QmxConnectionController.kt`: exact-device generation, startup handshake, capability probes, bounded polling, route loss and idempotent close.
- `QmxPanadapter.kt`: exact UAC profile, +12 kHz axis/passband mapping and adaptive I/Q correction.
- `QmxToneTxBackend.kt`: already-encoded FT8/FT4 plan execution with absolute monotonic deadlines and fail-safe cleanup.
- `QmxMenuTerminal.kt`: explicit-open extra-CDC controller and bounded 80×24 ANSI model.
- `QmxRadioSurface.kt`: embeddable compact, standard and wide-tablet Compose surface emitting typed actions only.

## Protocol truth

The decoder bounds every response buffer and accepts only terminated ASCII frames. AF gain retains 0.25 dB native steps while RF gain remains plain dB. RIT writes clear first. Q9/Q3 write echoes are never accepted as readiness. Unsupported `?;` readbacks gate their feature unavailable.

The connection core probes once, then polls only successful readbacks in fast (200 ms), medium (1 s) and slow (10 s) groups. No unsupported command is placed into recurring polling.

## Safety boundary

No component selects a callsign, encodes messages, chooses parity, sequences a QSO, arms Digi, logs a contact, calls Wavelog or owns the USB device directly. The surface cannot issue CAT. Transmit/tune controls emit confirmation-classified actions for the later central adapter.

The tone backend requires capability proof, exact plan digest authorization, Digi TX enabled/armed/operator-initiated evidence, exact context generation, exact device digest, confirmed TX, bounded symbol slip, cached nonblocking SWR sampling and confirmed RX cleanup.

## Evidence boundary

Source review, unit/instrumentation compilation and APK build are software evidence. This branch does not install an APK and makes no physical QMX/QMX+, UAC audio, spectrum, RF, transmit, SWR or menu-terminal acceptance claim.

## Validation record — 2026-08-23

- Focused QMX JVM tests: 27 passed, 0 failed.
- Full debug JVM suite: 585 passed, 0 failed, 0 errors, 0 skipped.
- `assembleDebug`: passed, including the release Rust/native build and all four Android ABIs.
- Debug Android-test sources: compiled; the QMX Compose test was not executed on a device.
- `lintDebug`: passed; report generated at `android/app/build/reports/lint-results-debug.html`.
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 112,193,376 bytes (106.996 MiB), SHA-256 `961f4692590e3363d7d7f123f78738ecb073ad1cc46f05cc2d5893dea8ddf914`.
- Exact-base debug APK baseline: 117,144,602 bytes; this branch is 4,951,226 bytes (4.722 MiB) smaller.
- QMX watcher offline fixture and live check: `NO CHANGE` at commit `30c61f6142153d61d3160689aab1edbf95de810d`, tree `aed9dcdec704d13d380d6dc85c7c28a233a74244`, v1.9.2.
- Wavelog regression watcher: `NO CHANGE` at release 3.1.0 / commit `af3256140bd05403b7c4a421746c2ea653a4f04f`.
- Unrelated Nexus regression watcher: `REVIEW REQUIRED` at commit `8329d29b0f3eab3f03b43ab37686567e26b24830`, package `1.7.7-test2`; the watcher is read-only and no Nexus source or pin is changed here.

The only physical/device evidence remains the pending checklist in `LIVE_ACCEPTANCE.md`.
