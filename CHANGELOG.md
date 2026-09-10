# Changelog

## macOS Agent 1.0.1

- Reworked the standalone Agent as a native macOS settings experience with a direct pairing-code handoff.
- Fixed automatic Hamlib model and USB serial-port discovery when Qt diagnostics are present.
- Bundled the macOS TLS backend required for secure ShackCQ pairing and monitoring.
- Kept the Agent supervisor running when the separate inbound Remote Station service is disabled.

## 0.1.0-rc.1

- Consolidated accepted Android, iPhone/iPad, macOS, Windows, Linux, and station-service lineages.
- Added adaptive Opus Remote Station RX, PCM16 fallback, and optional bounded raw I/Q.
- Added pinned native Remote Station clients for Android, SwiftUI, and Qt/QML.
- Added Android MIDI/Bluetooth MIDI/USB MIDI and HID control-surface mapping with immutable Global Stop.
- Added full Linux GUI, libsecret credential storage, TGZ/DEB packaging, and native arm64 stationd CI.
- Preserved fail-closed PTT/TUNE/RF/movement and unsigned release boundaries.
