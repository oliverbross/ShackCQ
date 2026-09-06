# Multiplatform candidate readiness

The integration candidate preserves hardened Android, Windows Alpha, macOS Qt source compatibility and native SwiftUI iOS as distinct clients at one exact SHA. Hosted orchestration is defined in `.github/workflows/shackcq-multiplatform-candidate.yml` and reuses the existing mobile/core, Windows and macOS workflows.

PASS requires successful exact-SHA hosted jobs, artifact audits and a clean pushed integration ref. Local source/build results do not substitute for hosted Windows packaging. Physical installation, authenticated services, live cluster, hardware/audio/CAT/PTT/TUNE/RF and rotator acceptance remain separately pending.

Windows is an Alpha foundation, not Android feature parity. macOS proof is an unsigned Qt build/package from the desktop source. iOS proof remains the unsigned native SwiftUI builds. Linux desktop acceptance is not claimed.
