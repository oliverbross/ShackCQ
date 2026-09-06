# Final Feature Matrix — ShackCQ 0.1.0 RC1

| Platform/domain | Source status | Build/hosted status | Physical/live boundary |
|---|---|---|---|
| Android arm64/four ABI | COMPLETE | Required exact-SHA gate | Protected tablet in-place gate required |
| iPhone/iPad SwiftUI | COMPLETE | Simulator and unsigned XCArchive required | No signed IPA/device claim |
| macOS Qt/QML | COMPLETE | Unsigned arm64 package required | No signing/notarisation |
| Windows Qt/QML | COMPLETE | Portable and NSIS package required | Unsigned/SmartScreen boundary |
| Linux x86_64 GUI/stationd | COMPLETE | TGZ/DEB/Xvfb gate required | Secret Service session required for credential writes |
| Linux arm64 stationd | COMPLETE | Native ARM64 hosted package required | No target-host service deployment |
| Remote Opus RX / PCM fallback | COMPLETE | Protocol/media tests required | WAN/audio hardware acceptance pending |
| Optional raw I/Q | COMPLETE, host-disabled and one-client bounded | Protocol tests required | Source hardware validation pending |
| Qt remote client | COMPLETE | Desktop tests/package required | WAN/PTT/TUNE/movement pending |
| SwiftUI remote client | COMPLETE | Simulator/XCArchive required | Signed device/WAN acceptance pending |
| Android MIDI/HID | COMPLETE | JVM/lint/build required | Individual controllers require physical acceptance |

FOUNDATION_WIRED=0. MISSING=0. Complete means source-owned and testable; it does not convert the stated physical, authenticated-service, signing, or RF boundaries into passes.

