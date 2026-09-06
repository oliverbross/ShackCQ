# Unified Neural DX, HamClock, Wavelog, and Groups.io consolidation

## Frozen refs and ancestry

| Ref | Frozen SHA |
|---|---|
| `origin/main` | `45eec41bc3e12abeecf87c9c59cde6012743b342` |
| `origin/fix/neural-dx-provider-freshness-cache` | `9039fe39d78ac98cbce3da767f65c7cbc41df36b` |
| `origin/integration/wavelog-openhamclock-groupsio-v1` | `4a692a1b1d5653b55f21552bcc67a35fdc2b0172` |
| `origin/feature/hamclock-finishline-v1` | `3d20627dfc4a4987802e612c3caa4f39f69958e6` |
| `origin/feature/openhamclock-parity-v1` | `565f727a432e3fd0d11ac8223c790691f05530aa` |

All required ancestry checks returned exit 0. Task 4 was one commit ahead of the frozen main. Finish-Line was eight commits ahead of the combined integration branch. OpenHamClock parity was already contained by the combined integration branch. The contained branches were not merged separately.

## Merge order

1. Task 4 with `--no-ff`: `merge: add neural dx provider freshness and cache integrity`.
2. Finish-Line with `--no-ff`: `merge: unify hamclock wavelog groupsio and neural dx`.

## Conflict ledger

| File | Conflict type | Main and Task 4 retained | Finish-Line retained | Superseded duplicate removed | Coverage |
|---|---|---|---|---|---|
| `MainActivity.kt` | controller graph and construction | initial station/Wavelog QTH passed into Neural | application-scoped HamClock providers, Wavelog Native coordinator, Operations owner | duplicate Neural and Wavelog construction | Android compile, lifecycle/source audit |
| `NeuralDxController.kt` | provider models, caches, lifecycle, transport ownership | current opportunities, v3 journal, QTH-scoped weather cache, provider status/age, bounded reads, atomic last-good, lightning identity/idempotent close, tune guard | shared DX News and PSK/WSPR repositories, rich RF evidence, foreground jobs | direct Neural PSK and WSPR.live requests; legacy satellite download/ticker production path | Task 4 cache tests, RF evidence tests, consolidation ownership tests |
| `NeuralDxScreen.kt` | spot actions and shared evidence UI | 16-band display, QO-100 annotation, observation-only guard above 54 MHz, provider state/source/age | exact PSK rows, history/watch actions, receive-review flow, DX News model | older direct-tune dialog and duplicate briefing card | Android JVM tests and source reread |
| `QsoDatabase.kt` | band mapping adjacent to v13 schema | exact 16-band contract and unsupported-gap rejection | schema v13, projection v2, Wavelog sync/outbox migration | Finish-Line-only `1.25m` and `33cm` mappings outside the contract | migration instrumentation compile and JVM database tests |
| `core/test/core_tests.cpp` | include set | Neural/KX3 band analysis tests | pinned SGP4/native satellite tests | neither side; includes combined | native CTest |

No whole-file ours/theirs resolution was used.

## Final provider ownership

| Source | Network/compute owner | Neural consumption |
|---|---|---|
| DX cluster and RBN | `FeatureController` and configured retail-cluster infrastructure | normalized immutable live spots/evidence |
| DX News | HamClock `DxNewsRepository` | shared snapshot adapter |
| PSK Reporter and personal WSPR | HamClock `PskReporterRepository` / `HamClockWsprRepository` | shared exact-mode reports and source state |
| Regional WSPR.live | none; `UNAVAILABLE_POLICY` | explanatory state only; zero request path |
| Satellites | `SatelliteOperationsController`, `SatelliteProviderRepository`, `NativeSatellite` | shared catalogue/pass/position/transponder snapshot; no Neural ticker |
| Lightning | `NeuralDxController` | HamClock consumes Neural snapshot |
| Terrestrial weather | Task 4 QTH-scoped Open-Meteo cache in `NeuralDxController` | authoritative Neural weather snapshot |
| Solar, space weather, aurora | HamClock Finish-Line repositories/controllers | no Neural duplicate |
| IBP | HamClock schedule/evidence model | shared schedule/evidence; separate VHF beacon reference is not the IBP set |

`HamClockFeedState` maps to focused `NeuralProviderStatus` as LIVE→LIVE, CACHED→CACHED, STALE/DEGRADED→STALE, and UNAVAILABLE→UNAVAILABLE.

## Controller, database, and cache ownership

| Concern | Authority |
|---|---|
| App/controller lifecycle | one graph in `MainActivity`; `OperationsController` owns and closes satellites; Neural close is idempotent |
| QSO writes | canonical `qso` plus `QsoMutationCoordinator`, projection v2, one Wavelog outbox |
| Neural observations | separate `neural-dx.sqlite`, schema 3, no worked/QSL authority |
| Groups.io | separate `shackcq-groupsio.sqlite`; no QSO tables |
| Provider caches | focused `NeuralProviderCache`, HamClock last-good support, satellite provider cache, Groups.io database/cache |

## P.533 and package boundary

The native boundary remains `LICENSE_BLOCKED`. No ITU-R-HF source, coefficient data, or fabricated local prediction is shipped. The metadata-only provenance directory is not an implementation payload.

## Validation ledger

### Commits

- Task 4 merge: `edced179903921b1f5ac68728f330e44b71b59e0`.
- Finish-Line merge: `934182b73e1eb1b1c82f160ad8b43aeaa756f821`.
- Semantic provider reconciliation: `87caa97`.
- Shared-satellite test reconciliation: `99b29a2`.
- Package audit/policy: `f1dbdf1`.

### Automated validation

| Gate | Result |
|---|---|
| Android JVM | PASS, 355 tests, 0 failures/errors/skips, including exactly 12 new consolidation-specific cases |
| Android debug APK | PASS |
| Android debug AAB | PASS |
| Android debug androidTest APK | PASS (compiled only; not installed or run) |
| Shared core | PASS, Debug configure/build and 2/2 CTest |
| iOS Simulator | PASS, unsigned generic simulator build for arm64 and x86_64 |
| Wavelog watcher | PASS, stable 3.1.0 unchanged at `af3256140bd05403b7c4a421746c2ea653a4f04f` |
| OpenHamClock watcher | NOTE, stable/release/package/licence unchanged; preview-only satellite telemetry change to `99913f2df574b8588ddaff703581b8f341f46761` requires review but was not absorbed |
| Ownership/conflict scans | PASS: one PSK transport, no WSPR.live request, one satellite provider, one lightning socket, one Neural weather request, no conflict markers, no `QsoDatabase.all()` |
| ITU/P.533 packaged payload | PASS, absent from APK and AAB |

### Package audit and modularity decision

| Artifact | Archive bytes | ZIP uncompressed | SHA-256 |
|---|---:|---:|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | 110,139,162 | 161,440,267 | `fd16e7bfe8a4f4d6ef6c53d78bcafc01626b64e63a66d6a16d7f17aeb3c3f368` |
| `android/app/build/outputs/bundle/debug/app-debug.aab` | 51,540,301 | 161,556,128 | `5d32880151ffa66febc5a3530004c0c0d4a88706956df45fd75777272cd7b85f` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,132,203 | — | `4407ead48c0f29d24bcb82f1bce6bebd9be1933ea7afd0cd2d898870d0a90308` |

The universal APK is 318,250 bytes above the combined-integration reference and 32,672 bytes above the Finish-Line reference. It remains below the 130 MB internal gate. The AAB is 51,503,095 ZIP-compressed bytes, below the 150 MB target. Uncompressed totals are dex 74,912,092 bytes, resources 269,239/287,281 bytes, and native libraries 85,599,896 bytes. Native totals by ABI are arm64-v8a 22,203,984, armeabi-v7a 15,574,716, x86 23,184,804, and x86_64 24,636,392 bytes. The audit reported no prohibited ITU/P.533 payload. Dynamic feature modules or separate apps are not justified.

### Behavioural reread

- Navigation has one destination and production route for Home, Radio, Digi, Panadapter, EQ, Logbook, Progress, Sync, Presets, DX, Portable, Operations, Groups.io, and Settings.
- Neural retains its eight enum entries representing the seven-page workspace plus Weather, truthful current opportunities, provider state/source/age, RF Evidence, 16-band display, and the direct-tune guard above 54 MHz.
- Home retains application-scoped settings, module/map registries, Band Health/RF Evidence, Finish-Line solar/aurora/satellite/contest/ID/shack functions, and the truthful P.533 blocked boundary.
- Logbook uses bounded repositories and one mutation coordinator/outbox; QSO schema 13 and projection v2 migrations remain monotonic and transactional.
- Groups.io remains separately stored, navigable, enable/disable-aware, and isolated from QSO tables.
- Reviewed tune flows are receive-review or explicit direct operator actions. No automatic PTT, TUNE, TX-frequency change, macro, or spotting path was introduced.

### Evidence limitations and main decision

No APK was installed. No application data, credentials, or protected settings were inspected or changed. No physical device, authenticated Wavelog/Groups.io/PSK/DX News provider, CAT, audio, RF, PTT, TUNE, or store/release acceptance test was performed. These remain separate external evidence layers.

All mandatory source, Android, native, iOS, watcher-stability, ownership, size, package, and safety gates pass. The authorized decision is to push the unified branch, re-fetch the frozen refs, and fast-forward `main` only if every remote ref remains frozen. Final remote equality and clean status are recorded after that operation.
