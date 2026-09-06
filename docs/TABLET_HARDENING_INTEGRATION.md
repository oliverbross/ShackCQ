# Tablet hardening integration

## Scope and frozen baseline

- Frozen validated main: `bfa8129478d7975523af25b6b44d28a35d348c7e`.
- Original tablet worktree branch/HEAD: `fix/android-tablet-full-qa` at `bfa8129478d7975523af25b6b44d28a35d348c7e`.
- Original ahead/behind versus `origin/main`: `0/0`.
- Original work was physically exercised only through the pre-integration debug candidate. No APK was installed, no app data was cleared, and no device instrumentation ran during this integration.
- The work remained narrow to capture, reconciliation, validation, and main integration; no upstream feature import or unrelated refactor was added.

## External recovery capture

The complete external source capture is in the ShackCQ container at `evidence/tablet-hardening-source-capture-20260821`.

- `unstaged.patch`: binary-safe, 395,480 bytes.
- `staged.patch`: empty, matching the original absence of staged changes.
- `untracked-source.tar.gz`: 1,271,504 bytes, 42 meaningful source/test/resource entries.
- `SHA256SUMS.txt`: 12 verified artifact entries; manifest SHA-256 `27f15daa5c051c73c9f33c6e66c965dbf050697ec4b99b12cb967911a8a50804`.
- Pre/post capture status was byte-identical.
- The installed candidate APK and screenshot evidence were not overwritten or added to Git.

## Branches and commits

- Recovery branch: `recovery/tablet-hardening-capture-v1`.
- Recovery commit: `0261f92a1c7a6d22d6db90a8523433182fac6383` — `feat(tablet): capture physically tested hardening candidate`.
- Integration branch: `integration/tablet-hardening-final-v1`, created exactly from frozen `origin/main`.
- Merge commit: `114de36341f364c49e1203a265d17011bff449b8` — `merge: integrate physically tested tablet hardening`.
- Semantic repair commit: `b99c67463ffd251de2b8a57386c421f81f84f18f` — `fix(tablet): reconcile database browser and bounded-query contracts`.
- The recovery branch was directly descended from the frozen main, so the authorised `--no-ff` merge path was used. Cherry-picking was unnecessary.

## Complete changed-file classification

### PRODUCTION_SOURCE

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/app/shackcq/mobile/AppController.kt`
- `android/app/src/main/java/app/shackcq/mobile/BoundedIo.kt`
- `android/app/src/main/java/app/shackcq/mobile/CtyController.kt`
- `android/app/src/main/java/app/shackcq/mobile/EqAudioController.kt`
- `android/app/src/main/java/app/shackcq/mobile/FeatureController.kt`
- `android/app/src/main/java/app/shackcq/mobile/HamClockHomeMap.kt`
- `android/app/src/main/java/app/shackcq/mobile/HamClockHomeScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/HamClockPropagationRepository.kt`
- `android/app/src/main/java/app/shackcq/mobile/InAppBrowser.kt`
- `android/app/src/main/java/app/shackcq/mobile/LogIntelligenceRepository.kt`
- `android/app/src/main/java/app/shackcq/mobile/LogbookController.kt`
- `android/app/src/main/java/app/shackcq/mobile/LogbookFilters.kt`
- `android/app/src/main/java/app/shackcq/mobile/MainActivity.kt`
- `android/app/src/main/java/app/shackcq/mobile/NeuralDxController.kt`
- `android/app/src/main/java/app/shackcq/mobile/NeuralDxScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/OperationsScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/PortableChaseScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/PortableRepository.kt`
- `android/app/src/main/java/app/shackcq/mobile/PortableWorkspaceScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/PotaActivateScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/PotaChaseScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/ProgressController.kt`
- `android/app/src/main/java/app/shackcq/mobile/ProgressModels.kt`
- `android/app/src/main/java/app/shackcq/mobile/ProgressScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/QsoDatabase.kt`
- `android/app/src/main/java/app/shackcq/mobile/SatelliteOperationsScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/SatelliteProviders.kt`
- `android/app/src/main/java/app/shackcq/mobile/SmartLink.kt`
- `android/app/src/main/java/app/shackcq/mobile/SpotFilters.kt`
- `android/app/src/main/java/app/shackcq/mobile/SyncHubScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/VoiceMacroAudioController.kt`
- `android/app/src/main/java/app/shackcq/mobile/groupsio/GroupsIoFeature.kt`
- `android/app/src/main/java/app/shackcq/mobile/groupsio/GroupsIoPhase2.kt`
- `android/app/src/main/java/app/shackcq/mobile/groupsio/GroupsIoPhase2Ui.kt`
- `android/app/src/main/java/app/shackcq/mobile/hamclock/HamClockProviderSupport.kt`
- `android/app/src/main/java/app/shackcq/mobile/hamclock/HamClockRegistries.kt`
- `android/app/src/main/java/app/shackcq/mobile/hamclock/HamClockRfEvidence.kt`
- `android/app/src/main/java/app/shackcq/mobile/hamclock/finishline/HamClockSolarImageRepository.kt`
- `ios/ShackCQ.xcodeproj/project.pbxproj`
- `ios/ShackCQ/ContentView.swift`

### TEST

- `android/app/src/androidTest/java/app/shackcq/mobile/NeuralDxStoreInstrumentedTest.kt`
- `android/app/src/androidTest/java/app/shackcq/mobile/groupsio/GroupsIoDatabaseInstrumentedTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/BoundedIoTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/HamClockHomeFoundationTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/InAppBrowserTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/OvernightScaleSatelliteTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/ProgressChartDataTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/ProgressModelsTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/SpotFiltersTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/UnifiedConsolidationTest.kt`
- `android/app/src/test/java/app/shackcq/mobile/groupsio/GroupsIoContractTest.kt`

### RESOURCE

- `android/app/src/main/res/drawable-nodpi/shackcq_logo_mark.png`
- `android/app/src/main/res/drawable/shackcq_icon_background.xml`
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `android/app/src/main/res/mipmap-hdpi/ic_launcher.png`
- `android/app/src/main/res/mipmap-hdpi/ic_launcher_round.png`
- `android/app/src/main/res/mipmap-mdpi/ic_launcher.png`
- `android/app/src/main/res/mipmap-mdpi/ic_launcher_round.png`
- `android/app/src/main/res/mipmap-xhdpi/ic_launcher.png`
- `android/app/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
- `android/app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- `android/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
- `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`
- `assets/branding/shackcq-app-icon-1024.png`
- `assets/branding/shackcq-mark-1024.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-20.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-20x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-20x3.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-29.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-29x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-29x3.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-40.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-40x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-40x3.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-60x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-60x3.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-76.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-76x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/AppIcon-83_5x2.png`
- `ios/ShackCQ/Assets.xcassets/AppIcon.appiconset/Contents.json`
- `ios/ShackCQ/Assets.xcassets/ShackCQLogo.imageset/Contents.json`
- `ios/ShackCQ/Assets.xcassets/ShackCQLogo.imageset/ShackCQLogo-1x.png`
- `ios/ShackCQ/Assets.xcassets/ShackCQLogo.imageset/ShackCQLogo-2x.png`
- `ios/ShackCQ/Assets.xcassets/ShackCQLogo.imageset/ShackCQLogo-3x.png`

### DOCUMENTATION

- `docs/TABLET_HARDENING_INTEGRATION.md`

### GENERATED

Excluded from Git:

- `rust/tempo-sstv/target/` from the original worktree; moved to Trash after capture and reproducible from source.
- `scripts/__pycache__/` created by integration watchers; moved to Trash.
- Gradle, CMake, Rust target, Xcode DerivedData, APK, AAB, lint, and watcher report outputs remain generated/ignored.

### LOCAL_EVIDENCE

Excluded from Git:

- `evidence/tablet-ui-20260821/screenshots-fixed`: 153 inherited physical-candidate files.
- Original installed candidate `shackcq-mobile-tablet-hardening/android/app/build/outputs/apk/debug/app-debug.apk`: 115,219,109 bytes, SHA-256 `74ea3108a786d42058a8dc379edfaaf6b005f9f63f671bc7b14e0cbcb6cee211`.

### PRIVATE_OR_RUNTIME_DATA

None included. No credential value, private QSO export, local app database, Groups.io account data, Wavelog export, token, or tablet-private data was committed.

### UNRELATED

None identified or committed.

## Conflict and semantic decisions

The Git merge had no textual conflicts and used no whole-file `ours` or `theirs` resolution. Post-merge review made these explicit semantic corrections:

1. Restored QSO database schema 13 and projection contract v2. The physically tested candidate's schema 16/projection v5 bump remains preserved on recovery but is not in the integrated source.
2. Retained a single shared `QsoDatabase` instance and the existing `QsoMutationCoordinator`/Wavelog outbox ownership.
3. Changed archived-DX search from prefix range matching to exact indexed callsign equality, removed acceptance-only `N0AN` UI/test fixtures, and kept the query off-main with latest-generation result ownership.
4. Hardened the shared application browser to HTTPS-only in-app navigation with JavaScript/DOM storage/file/content/mixed-content access disabled, visible title/domain/back/close/open-externally controls, confirmed external-scheme/download handoff, and lifecycle destruction.
5. Added strict whole-response bounded reads for remote images while retaining framed exact-read behavior needed by stream protocols.
6. Reduced the captured 19 added test cases to the authorised 12 focused integration cases.
7. Preserved all final Digi v2 files and contracts from main; no Digi or shared native source file was changed.

## Functional results

### QO-100 / Es'hail-2

One authoritative `QO-100` surface lives inside existing Satellite Operations. It uses station-grid authority and native Maidenhead geometry, fixed 25.9 degrees east pointing, narrowband/wideband uplink/downlink plans, operating guidance, official AMSAT-DL HTTPS references through the secure browser, and no automatic tune/PTT/TUNE/TX action. The JN88TQ regression expects about 169.0 degrees azimuth and 33.5 degrees elevation with a 0.6-degree tolerance.

### Secure browser

One shared application browser handles tablet links. Normal in-app navigation is HTTPS-only; JavaScript and bridges are absent; file/content/universal-file access and mixed content are disabled. HTTP, mail, telephone, and download handoffs require an explicit external confirmation. The existing SmartLink authentication-specific WebView remains a separate reviewed authentication surface and was not duplicated by tablet links.

### Logbook and Progress

The app now shares the QSO database owner across surfaces, suppresses redundant Logbook query generations, publishes a compact fast projection snapshot before detailed Progress aggregates, performs detailed aggregation on `Dispatchers.IO`, and retains bounded projection SQL rather than full-log materialisation. QSO schema 13, projection v2, keyset paging, streamed ADIF, exact station binding, Advanced Logbook, and Fast Entry remain authoritative.

### Historical DX

Historical search uses the existing `neural-dx.sqlite` spot journal and `spot_call_ts_idx`, exact callsign equality, one bounded aggregated result, source observation dates/counts, off-main execution, and latest-generation cancellation semantics. No duplicate archive database or acceptance callsign constant was added.

### Groups.io, Portable, Operations, Satellite, HamClock, DX

Tablet layouts and corrections were integrated without moving Groups.io data from `shackcq-groupsio.sqlite`, and without creating duplicate portable, Operations, satellite, Neural DX, or HamClock provider/controller authorities. Stable provider truth, RF Evidence, Band Health, P.533 `LICENSE_BLOCKED`, and the prohibition on WSPR.live requests remain intact.

### Android compatibility, permissions, streams, and queries

The manifest change only wires launcher icons; it adds no permission. Existing runtime boundaries remain responsible for notification, location, media, USB, network, FileProvider, and WebView availability. Network/file bodies are capped, streams close through `use`, oversized whole-response media fails closed, SQL stays bounded/indexed, expensive work runs off-main, and sanitized/last-good behavior remains in provider owners.

### Digi v2

Exact FT4 millisecond slot identity, Digi schema 2, decode-source isolation, monotonic parity scheduling, typed Elecraft/Flex completion, the RX-unconfirmed fail-closed latch, deterministic FT8/FT4 sequencing, native waterfall/spectrum, SSTV gallery, WSJT-X UDP, and QSO mutation/Wavelog integration remain present. No final Digi source was edited by tablet integration.

## Validation

- Android JVM: `:app:testDebugUnitTest` — PASS, `BUILD SUCCESSFUL`.
- Android package graph: `:app:assembleDebug :app:bundleDebug :app:compileDebugAndroidTestSources :app:lintDebug` — PASS in 15m 29s.
- Lint: PASS, 0 errors; 154 warnings and 34 hints are non-blocking.
- Android instrumentation: sources compiled; not executed because no disposable device/emulator was authorised and the operator tablet is protected.
- iOS: unsigned generic `xcodebuild` — PASS, `BUILD SUCCEEDED`; signing unchanged.
- Rust/shared core: no Rust or shared-core source changed, so standalone Rust/core suites were not rerun solely for ceremony. The Android package graph did compile the Rust release libraries and Android CMake targets.
- Wavelog watcher: NO CHANGE at release 3.1.0 / `af3256140bd05403b7c4a421746c2ea653a4f04f`.
- OpenHamClock watcher: `REVIEW_REQUIRED` only because preview/Staging moved; stable, v26.5.0 release, package, and licence are unchanged. This is the programme's documented non-blocking preview-only case.
- Nexus watcher: package remains 1.7.5; upstream main moved in manual-notch/compressor and WinKeyer behavior outside the imported Digi workflow. The reviewed pin remains frozen; no unrelated upstream import was made.
- Repository scans: no conflict markers, credentials, local absolute paths in changed source, private database, `QsoDatabase.all()` call, duplicate application browser, or automatic QO-100 transmit action.

## Final Android artifacts

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`; 111,029,380 bytes; SHA-256 `b44c4dad9595dd4709f7b04b4e7301c77f18c3e559970837338758dddebb6f7d`.
- AAB: `android/app/build/outputs/bundle/debug/app-debug.aab`; 52,370,069 bytes; SHA-256 `84fa497a541de7833d1e61072cc428fd4948ad2fcd98dc91e85ee73e1f43406b`.
- Package limits: APK <= 130 MB and AAB <= 60 MB — PASS.
- ITU/P.533 payload scan — PASS for APK and AAB.
- Embedded screenshot/test-database/private-data scan — PASS.

## Evidence boundary and remaining acceptance

The 153 screenshots and original APK prove only the pre-integration tablet candidate. They do not prove the final integrated APK's physical layout, startup, authenticated services, live providers, radio control, RF reception, audio, PTT, TUNE, or TX behavior. No final APK was installed in this task. Final physical-tablet, live-service, RF, and transmit-safety acceptance remains pending and must use preservation-safe procedures in a separately authorised task.
