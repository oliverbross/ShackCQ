# Android Studio integration closure

## Verdict

`PASS WITH NOTES`

Android software/device integration is fail-closed. Physical KX3 quadrature-I/Q RF acceptance remains deferred. The unrelated Android lint baseline remains non-green.

## Exact integration state

- Starting `origin/main`: `5d512a91e58f4a7746193aec44fbc71f0efdfa96` (`merge: integrate Phase 0 product and licensing truth`).
- Approved source branch: `fix/android-kx3-panadapter-rf-correctness`.
- Approved source commits: `fb86c523905bb4c1323bc878ab68380a333c90e7`, `b5d73060e8b2fd40c808da1703f39d2f4e4b74aa`, and `563b788c59dccab0b9e83206a21bb0778671a830`.
- Integration branch: `integration/android-phase1-studio-closure`, branched from approved tip `563b788c59dccab0b9e83206a21bb0778671a830` with merge base `5d512a91e58f4a7746193aec44fbc71f0efdfa96`.
- Audio-ownership implementation: `d8df0a197aa728ac2dbb1bd417846babdb7e41fa`.
- The primary checkout's unrelated iOS edits were not included; work occurred in `/Users/oliver/Documents/Projects/ShackCQ/shackcq-mobile-phase1-android-closure`.

## Integrated behavior

The approved Android panadapter, KX3 EQ Studio, SSB voice macros, and receive monitor share one exclusive contract. The only owners are `MONITOR`, `PANADAPTER`, `EQ`, `VOICE`, and `VOICE_TX`. A non-monitor requester may pause `MONITOR` only when its call explicitly permits it. The central `AudioMonitorController` records whether the monitor was running and restores it at most once on safe release. No non-monitor owner may be preempted or nested.

Voice TX acquires `VOICE_TX` inside the tested transmit sequence before fresh TQ preflight, `AudioTrack` creation, route-verification silence, or `TX;`. Acquisition failure returns an operator-visible owner status and sends zero PTT commands. Structured cleanup halts audio, performs the existing bounded RX confirmation, releases track/focus, and releases the lease on normal, refusal, exception, Stop, route/focus loss, backgrounding, and close paths. Manual `monitorWasRunning` restoration was removed.

Client shutdown order is voice TX, voice record/preview, EQ audio, panadapter, then the shared monitor/coordinator. App stop disarms voice operations and stops voice/EQ audio. Changing the selected RX route stops the active panadapter before replacing the selection.

## Ownership matrix

| Current owner | Request | Result |
|---|---|---|
| none | monitor or one non-monitor operation | starts |
| monitor | panadapter, EQ, voice, or voice TX with pause policy | monitor pauses; requester owns; coordinator may restore once |
| monitor | requester without pause policy | rejected |
| panadapter | EQ, voice, voice TX, monitor, or another panadapter lease | rejected before new audio opens |
| EQ | panadapter, voice, voice TX, monitor, or another EQ lease | rejected |
| voice record/import/preview | panadapter, EQ, voice TX, monitor, or another voice lease | rejected |
| voice TX | every other audio request, including another voice TX | rejected |

## Panadapter truth and defaults

`PanadapterSettings()` and missing `rate` values now request 48 kHz. Explicit saved `rate=48000` and `rate=96000` decode and re-encode unchanged; both controls remain visible. A stale CAT center resolves to zero, leaving `RF STALE` and `CAT OFFLINE · RELATIVE OFFSETS ONLY` visible, suppressing absolute frequency labels, and blocking QSY.

Retained evidence reports approximately 0.10 dB median mirror rejection, approximately 7.72 dB channel imbalance, and no observed spectrum movement during the recorded manual VFO diagnostic topology. The application retains `MIRROR IMAGES DOMINANT`. Android-reported direct 96 kHz exposed only about 48 kHz useful central response; 48 kHz is the honest routine default. These were not remeasured in this closure.

## Automated validation

- Shared core: Android SDK CMake 3.22.1 configure and build plus CTest passed, 1/1.
- Android focused ownership/voice/panadapter JVM gate passed, 30 tests, zero failures/errors/skips.
- Full `:app:testDebugUnitTest :app:assembleDebug` passed; the recorded full JVM result was 89 tests, zero failures/errors/skips.
- `:app:connectedDebugAndroidTest` passed 7/7 on Lenovo `TB373FU` (Android API 36); the suite contains database and native panadapter context/configure/push/snapshot tests and no transmit command.
- `:app:lintDebug` retained the unrelated baseline: 9 errors, 51 warnings, and 20 hints. Error count remains the documented nine; no new error is at a changed Phase 1 line or in either added ownership file. No baseline or suppression was added.

## Device and install evidence

- Device: Lenovo `TB373FU`; ADB serial `adb-HA248BS3-Vsaw6A (2)._adb-tls-connect._tcp`.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 85,792,153 bytes, SHA-256 `b3a8cde9275596de872862b832ba4ddf3d137d3cba7116c1c03be61a808f59ca`, version `0.1.0` (code 1).
- `adb install -r` succeeded. A second stopped-app replacement preserved the observable app-private file count at 12 before and 12 after; no uninstall, storage clear, schema migration, package-ID change, or preference rewrite was used.
- Cold launch succeeded without app fatal/ANR evidence. Settings showed the saved StarTech RX input. Monitor acquisition ran at 48 kHz; `PAUSE AND USE FOR EQ` paused it for a finite real-input capture, and the central coordinator restored it afterward. The final monitor switch was off after background/resume; a zero-length USB read was reported precisely rather than leaving an owner live.
- EQ Studio and Voice's six private slots opened. The panadapter opened with `48K` selected, `96K` available, `RF STALE`, and `CAT OFFLINE · RELATIVE OFFSETS ONLY`; START refused until a live KX3 was identified.
- Evidence captures are under `/Users/oliver/Documents/Projects/ShackCQ/artifacts/android-phase1-closure-20260818`.

No RF transmission was performed. CAT was offline during this closure's UI smoke; Arm & Send, `TX;`, TUNE, and dummy-load actions were not invoked.

## Known limitations

- Accepted deferred hardware work: a valid independent quadrature source, orientation, calibrated image rejection, and VFO-responsive RF display remain unaccepted on Android.
- Lint remains non-green only under the recorded pre-existing project baseline; the current tool run reports one additional warning and three additional hints versus the earlier evidence snapshot, with no new error introduced by this closure.
- CAT-live voice-TX conflict was not exercised physically because transmission was prohibited; deterministic tests prove failed lease and route preflight produce zero `TX;`.
- iPadOS does not yet have EQ Studio or SSB voice-macro parity.

## Next candidate

iPadOS KX3 Studio parity — EQ Studio and SSB voice macros.

Phase 2 is not authorised by this closure.
