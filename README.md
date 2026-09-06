# ShackCQ

ShackCQ is a radio-native portable operating cockpit that connects discovery, tuning, operating, logging, synchronisation, and progress without requiring fabricated state or permanent network access.

**Your station. One app.** Product home: [shackcq.com](https://shackcq.com). Support: [support@shackcq.com](mailto:support@shackcq.com).

The RC1 integration repository contains native SwiftUI iPhone/iPad and Jetpack Compose Android clients over a shared C++17 core, plus one Qt/QML desktop application for macOS, Windows, and Linux and a standalone station service. Source parity and packaging do not imply physical radio, WAN, signing, or store acceptance. Elecraft KX3/KX2 remains the established mobile radio family; desktop Hamlib operation stays explicit and fail-closed.

Candidate integration scope and evidence boundaries are recorded in `docs/ANDROID_WINDOWS_INTEGRATION_V1.md` and `docs/MULTIPLATFORM_CANDIDATE_READINESS.md`.

## Current implementation

| Layer | Current truth | Main paths |
|---|---|---|
| Shared core | KX3/KX2 CAT parsing and safety classes, ADIF, CTY, spot/DX analysis, operator intelligence, panadapter DSP, Wavelog retry policy, and bounded WSJT-X parsing behind a C ABI | core/include, core/portable, core/src |
| Apple | Adaptive iPhone/iPad SwiftUI app, Objective-C++ bridge, base USBDriverKit KXUSB transport, local SQLite/ADIF, callbook, Wavelog, cluster/DX, physical-I/Q panadapter, and pinned Remote Station client | ios/ShackCQ, ios/CP210xDriver |
| Android | Compose app, JNI bridge, USB serial, local SQLite/ADIF, callbook, Wavelog, CW and SSB voice macros, hardware-backed KX3 EQ Studio, DX/Neural DX surfaces, MapLibre maps, audio monitoring, and a dedicated KX3 stereo-I/Q panadapter behind one exclusive audio-owner contract | android/app/src/main |
| Desktop/Linux | Qt/QML app and stationd with singular functional owners, secure Remote Station host/client, system credential vaults, and bounded packages | desktop |

The Apple Xcode target supports device families 1 and 2 (iPhone and iPad) at deployment target iOS 17. Simulator builds establish adaptive source/UI coverage; signed physical-device acceptance remains separate.

## Build

Shared core:

~~~sh
cmake -S core -B core/build-phase0 -DCMAKE_BUILD_TYPE=Debug
cmake --build core/build-phase0 --parallel
ctest --test-dir core/build-phase0 --output-on-failure
~~~

Android:

~~~sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
~~~

The Android SDK path must be configured through ANDROID_HOME, ANDROID_SDK_ROOT, or an untracked local.properties. Do not accept SDK licences merely to turn an unavailable environment into a claimed pass.

Apple:

~~~sh
xcodebuild -project ios/ShackCQ.xcodeproj -list
xcodebuild \
  -project ios/ShackCQ.xcodeproj \
  -scheme ShackCQ \
  -configuration Debug \
  -destination 'generic/platform=iOS' \
  -derivedDataPath ios/DerivedDataPhase0 \
  build
~~~

Do not change signing identities, profiles, entitlements, DriverKit identifiers, or bundle IDs to manufacture a build.

## Evidence status

| Evidence | Phase 0 result | What it proves |
|---|---|---|
| Shared core Debug build and CTest | PASS, 1/1 on 2026-08-17 UTC | Host compilation and focused shared-core tests |
| Android unit tests and Debug APK | PASS on 2026-08-17 UTC | Unit suite and all configured Android ABIs assemble |
| Generic iOS Debug build | PASS on 2026-08-17 UTC using existing profiles | Apple app and DriverKit targets compile, link, embed, and sign |
| Physical iPad, KXUSB, KX3, and I/Q | Historical repository evidence, not repeated in Phase 0 | See docs/COMPLETION_REPORT.md and docs/PANADAPTER_DESIGN.md |
| Physical Android KX3 EQ and USB audio | PASS WITH NOTES on 2026-08-17 UTC | Lenovo TB373FU, KXUSB, KX3 firmware 03.02, exact RX/TX write-readback-restore, and real 48 kHz mono capture/A-B; test input was too quiet for a credible acoustic recommendation |
| Wavelog authenticated workflow | UNVERIFIED | No usable API key/station-profile proof was available in the recorded pass |
| FlexRadio, desktop, QMX, portable-programme operation | ABSENT/PLANNED | No current implementation or physical proof |

A successful build is not proof of USB enumeration, DriverKit activation, CAT semantics, audio orientation, remote authentication, service terms, or physical-radio operation.

## Documentation map

- [Product contract](PRODUCT.md)
- [Design contract](DESIGN.md)
- [Roadmap](docs/ROADMAP.md)
- [Architecture boundaries](docs/phase-0/ARCHITECTURE_BOUNDARIES.md)
- [Actual feature inventory](docs/phase-0/ACTUAL_FEATURE_INVENTORY.md)
- [Licensing and Nexus assessment](docs/phase-0/LICENSING_AND_NEXUS_ASSESSMENT.md)
- [Phase 0 audit](docs/phase-0/PHASE0_AUDIT.md)
- [Next authorised phase gate](docs/phase-0/NEXT_AUTHORISED_PHASE.md)
- [Android Phase 1 integration closure](docs/phase-1/ANDROID_STUDIO_INTEGRATION_CLOSURE.md)
- [Next Phase 1 candidate](docs/phase-1/NEXT_AUTHORISED_PHASE.md)

## Licence

ShackCQ is licensed under GPL-3.0-only. See [COPYING](COPYING) for the complete licence and [NOTICE](NOTICE) for the notice policy.

GPLv3 permits charging for binaries, support, and services; it does not require zero-price distribution. Every distributed covered binary must map to an immutable source commit/tag and be accompanied by, or provide equivalent access to, complete corresponding source, build instructions, dependency manifests/lock data, applicable patches, and notices.

Nexus was inspected as an external GPLv3 upstream/reference during Phase 0. No Nexus source, binary, dependency, submodule, or derived implementation is included by this work.

## Android implementation details

Install the built debug APK on an emulator or connected Android device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.shackcq.mobile/.MainActivity
```

Use the replacement install above for development deployments so app-private station, cluster, Wavelog, QRZ, CAT, audio, and log data remain intact. Never use `adb uninstall`, `pm clear`, or `connectedDebugAndroidTest` on an operator tablet when its saved data must be preserved; the connected-test lifecycle may remove the target package. Run instrumentation on a disposable emulator or dedicated test device instead.

An emulator can validate navigation and local logging, but cannot prove USB serial or radio operation. The Android app uses `usb-serial-for-android` for PL2303, CP210x, FTDI, CH34x, and CDC-ACM adapters where supported by that library. It opens the selected adapter at 38,400 baud and exposes frequency, mode, and raw CAT controls without simulation.

The Android source also binds the full portable feature engine through JNI and supplies the KX3-style live CAT deck, local ADIF journal/import/export, Wavelog discovery/upload/full-log cache, six graphical DX views, real TCP DX-cluster, NOAA, and a first-class production panadapter. The panadapter selects and proves an external stereo route, uses its own native DSP context and capture lifecycle, follows fresh CAT frequency truth, and fails closed rather than converting mono audio into a spectrum claim. Android SDK licences were accepted with the owner's explicit approval and NDK `28.2.13676358` is installed under the SDK used by Gradle.

Android also includes six SSB voice-macro slots for exact USB/LSB operation. Recordings remain in private tablet storage, preview is verified against the built-in speaker, and transmission uses an explicitly selected DigiRig USB output with left-channel speech/right-channel silence. Voice TX must acquire the shared `VOICE_TX` audio lease before route preparation or CAT PTT; failed ownership produces zero `TX;` commands. PTT is then owned only by verified Elecraft CAT (`TQ0 -> TX -> TQ1 -> audio -> RX -> TQ0`); RTS/DTR are kept inactive. Multiple CAT or USB-audio candidates require explicit selection. See [`docs/VOICE_MACROS_ANDROID.md`](docs/VOICE_MACROS_ANDROID.md) for setup, privacy limits, calibration, and the operator-controlled dummy-load checklist.

Android EQ Studio is a dedicated responsive KX3 calibration workspace. It reads exact RX/TX menu values, separates verified radio state from local drafts and profiles, records one transient 10–15 second physical-input clip, supports raw-reference or hardware-baseline delta preview, and applies only after `TQ0`, menu/context, conflict, and exact readback checks. Expanded layouts expose EQ in the rail; compact layouts retain the six destinations and open it from Radio or Settings → Audio. See [`docs/EQ_STUDIO.md`](docs/EQ_STUDIO.md).

## Panadapter architecture

The mobile panadapter consumes physical stereo I/Q only. Its shared FFT path now uses independent DC removal, coherent-gain-normalized complex magnitudes, Blackman-Harris windowing, FFT shift, and asymmetric dB-domain attack/release smoothing. It does not infer an I/Q calibration transform from each live frame.

Both native clients provide spectrum/waterfall instrumentation, while implementation and evidence remain platform-specific. Android adds resizable/single-pane layouts, a circular bitmap waterfall, honest optional visual center masking, true decimation zoom, explicit marker QSY/confirmed undo, route diagnostics, bounded recording/replay, and device/rate-bound I/Q calibration. See [`docs/PANADAPTER_DESIGN.md`](docs/PANADAPTER_DESIGN.md) and [`docs/panadapter/PANADAPTER_OPERATOR_GUIDE.md`](docs/panadapter/PANADAPTER_OPERATOR_GUIDE.md).

Android software/device integration is fail-closed. New or missing panadapter settings default to 48 kHz while explicit saved 48/96 kHz choices remain unchanged. Physical KX3 quadrature-I/Q RF acceptance remains deferred; the retained mirror-dominant evidence is not a successful RF claim.

## Current physical status

- Physical iPad: iPad Pro 11-inch (4th generation), `iPad14,4`, iPadOS 26.6.1, Developer Mode enabled and connected through CoreDevice.
- Signed build 38 and its embedded PL2303GC/KXUSB DriverKit extension build, install, launch, and verify on the physical iPad.
- Portable feature-core tests and the signed iPad device build pass with the cluster, DX, Wavelog, callbook, and panadapter code included.
- Physical CAT passes through the enabled DriverKit extension and real Elecraft KXUSB (`067B:23A3`): build 38 opened the PL2303GC at 38,400 8N1, identified the connected radio as KX3, and read VFO A at 21.1366 MHz. A physical UI test connected in Settings, navigated through Home and Radio, returned to Settings, and verified that CAT and frequency stayed live.
- Physical ICUSBAUDIO2D stereo I/Q passes at 48 kHz: more than 1.2 million live frames reached the shared 1,024-bin spectrum pipeline during the build-30 soak. The DSP now matches Tab5's proven analogue I/Q imbalance correction and complex-tone image-rejection test; every PCM frame is processed while SwiftUI presentation is bounded to about 9 FPS.
- The saved `OM0RX-6` profile was exercised against `cluster.om0rx.com:7300` on the physical iPad: login, `sh/dx 50`, parsing, shared-core snapshot generation, and populated Spots UI passed with real records. Wavelog authentication still requires a configured API key and station profile.
- Android voice-macro JVM tests and debug APK build are software evidence only. Connected-tablet launch and receive-only checks, plus every operator-controlled dummy-load/RF acceptance item, must be reported separately and must not be inferred from the build.
- Android KX3 EQ Studio was physically exercised on a Lenovo `TB373FU` with real KXUSB and KX3 MCU firmware `03.02`: RX CW and TX normal-SSB curves were changed by one dB, read back exactly, and restored exactly without any transmission-capable command. Real `USB Advanced Audio Device` captures and A/B playback ran at 48 kHz mono, but the source was too quiet for a credible acoustic recommendation. Split/ESSB hardware buckets, physical conflict injection, compact-phone validation, and KX2 writes remain unverified or deferred. See [`docs/EQ_STUDIO.md`](docs/EQ_STUDIO.md).

Public Apple distribution presents an unresolved GPLv3/platform risk. No App Store submission is authorised; qualified legal review and/or suitable additional permission is required before that distribution path is claimed compatible.

## Windows full-parity branch

`feature/windows-desktop-full-parity-v1` provides the complete Windows navigation shell, bounded provider/data platform, deterministic gallery, scale gates and same-source macOS packaging proof. Its audited verdict is PARTIAL: 14 of 31 rows are source-complete and 17 remain wired foundations without Android-equivalent production controllers. See [`docs/desktop/WINDOWS_FULL_PARITY_V1.md`](docs/desktop/WINDOWS_FULL_PARITY_V1.md) and the [parity matrix](docs/desktop/WINDOWS_FULL_PARITY_MATRIX.md). No physical, authenticated-service, transmit, RF, movement, signing or deployment claim is implied.

## Desktop Flightline UI convergence

`feature/desktop-flightline-ui-convergence-v1` established native macOS and Win32 system menus, a canonical command/shortcut palette, responsive/high-DPI galleries and the accepted v1 evidence set. `feature/desktop-ui-ux-deep-convergence-v2` adds the grouped collapsible sidebar, locked official workspace layouts, explicit Edit Layout mode and dedicated Home/Radio/Digi/EQ/Panadapter cockpit hierarchy. Start with [`docs/desktop/DESKTOP_UI_UX_DEEP_CONVERGENCE_V2.md`](docs/desktop/DESKTOP_UI_UX_DEEP_CONVERGENCE_V2.md) and the [`docs/ui`](docs/ui) reports. The 40 SVG icons and packaged `.ico`/`.icns` are original ShackCQ work and carry no additional dependency. All existing physical/live/release boundaries remain unchanged.

`feature/desktop-functional-parity-closure-v1` supplies production desktop owners for the 17 former foundations while preserving that UI baseline. Start with [`docs/desktop/DESKTOP_FUNCTIONAL_PARITY_MATRIX.md`](docs/desktop/DESKTOP_FUNCTIONAL_PARITY_MATRIX.md), then use the hardware/provider acceptance documents before any live operation. External TX/movement/authentication remains fail-closed.

## Multiplatform RC1

`integration/shackcq-v0.1.0-rc1-final` converges the accepted Android, iPhone/iPad, macOS, Windows, Linux, and station-service source on one exact-SHA release contract. Start with [`docs/release/SHACKCQ_V0_1_0_RC1.md`](docs/release/SHACKCQ_V0_1_0_RC1.md). Whole-repository ancestry, singular owners, schema/configuration safety, source/SBOM distribution and package digests are executable gates. Physical, authenticated, signing and RF acceptance remain separate.
