# ShackCQ Rotator Platform Core v1

This package-local Android core is based on frozen ShackCQ SHA `4cd3aa3b401a9f7bc9838b94217444d5b4cae3bc`. It owns no central navigation, radio, USB permission, operating-context, configuration, satellite-provider, QSO, or System Health implementation.

The core provides typed device profiles and capabilities, one active backend per physical identity, common native protocols, persistent bounded TCP, a serial transport port, remote `rotctld`, an embedded Hamlib port, deterministic bearing/path planning, session-only automation, explicit satellite tracking, diagnostics, settings import/export, and a responsive Compose workspace.

Safety defaults are intentionally conservative: no motion on launch/import/recomposition/provider refresh; unknown capability is unusable; movement is never blindly retried; STOP is prominent but remains only “requested” until telemetry confirms it; connection loss never implies the antenna stopped; TCP requires LAN opt-in; background clears automation and tracking without parking.

Physical ARCO connection, movement, satellite hardware tracking, central wiring, Apple parity, and installation were not performed in this branch.

## Recorded validation

- Full JVM suite: 593 tests, 0 failures, 0 errors, 0 skipped; 35 Rotator-specific JVM tests.
- Android instrumentation source: compiled successfully; the single workspace instrumentation test was not run because no device was used.
- Packaging gate: `assembleDebug`, `bundleDebug`, and `lintDebug` passed together; lint reported 0 issues.
- Debug APK: 112,225,931 bytes, SHA-256 `1954a6b736115886a0aaf246ca6092a26841c01c181416829cbd8a60e248bfd7`; 5,699,792 bytes smaller than the frozen-base APK.
- Debug AAB: 53,457,809 bytes, SHA-256 `893700740621fd697e856f67473b469968cbef586bcb257191c0b276c031f593`.
- Package audit: ITU/P.533 payload scan passed.
- Upstream watch: microHAM ARCO and Hamlib were unchanged; the pinned private `radio-station-pro` source was audited through authenticated Git, while the unauthenticated watcher correctly reported that source unavailable.
