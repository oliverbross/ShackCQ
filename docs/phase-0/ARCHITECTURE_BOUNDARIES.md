# Architecture boundaries

## Current layers

### Shared C++17 core and C ABI

core/include/shackcq/core.h is the stable client boundary. core/src owns C-ABI contexts; core/portable owns protocol, CAT parsing, ADIF, CTY, spot/DX analysis, operator intelligence, panadapter DSP, sync policy, and bounded WSJT-X parsing.

The mixed core/portable/include/kx3 and core/portable/src layout contains both KX3-specific protocol/state and radio-neutral modules. The path is historical. Phase 0 does not move it.

### Apple

- Swift/SwiftUI: application state and UI in ios/ShackCQ.
- Objective-C++ and DriverKit: KXUSB user-client/transport in ios/ShackCQ/KXUSBDriverClient.mm and ios/CP210xDriver.
- Platform-owned responsibilities: AVAudioSession, Keychain, UserDefaults, document-directory SQLite/ADIF, URLSession, lifecycle, signing/entitlements, navigation and accessibility.
- Current Xcode target: iPad device family, not proven iPhone.

### Android

- Kotlin/Compose: application state/UI under android/app/src/main/java/app/shackcq/mobile.
- JNI/CMake: shared core binding through NativeCore and android/app/src/main/cpp.
- Platform-owned responsibilities: Android USB Host/usb-serial, AudioRecord/playback, Keystore-backed credential encryption, SQLite, app-private files, network orchestration, lifecycle, MapLibre presentation and accessibility.

## Future rules

1. Preserve the working KX3/KX2 path.
2. Add C ABI entry points additively where practical; do not break Swift/JNI bindings without a migration.
3. Move radio-neutral code only by touch when a real second implementation creates pressure. Do not perform a repository-wide namespace cleanup.
4. UI, secure storage, device discovery, audio/serial APIs, document picking, network orchestration, and lifecycle remain platform-specific.
5. Share domain logic, parsing, DSP, ranking, provider-neutral policy, and retry semantics when this removes real duplication.
6. Every cross-platform feature names implemented clients and evidence separately.
7. Local logging and radio control cannot depend on cloud availability.
8. Wavelog authority and local-log authority remain distinct; connector fan-out must be exactly-once from the chosen authority.
9. Transmit-capable commands remain classified, deliberate, bounded, abortable, and non-blindly-retried.
10. Rust/Cargo is introduced only for a selected audited Nexus component whose value exceeds the C ABI/toolchain cost. Tauri/React does not become a ShackCQ client stack.
11. Qt 6/QML/CMake is the planned single desktop UI stack after the Flex phases.
12. No interface is added merely to reserve a future abstraction.

## Distribution boundary

Every distributed binary maps to an immutable source commit/tag with complete corresponding source, reproducible build instructions, dependency resolution data, patches, and notices. Release-specific artefacts—not this architecture file—must record exact binary/source identity.
