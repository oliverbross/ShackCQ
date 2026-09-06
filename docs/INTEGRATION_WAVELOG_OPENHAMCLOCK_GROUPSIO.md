# Wavelog, OpenHamClock, Groups.io integration record

## Integration identity

- Branch: `integration/wavelog-openhamclock-groupsio-v1`
- Main parent: `76e1e40e8471f142c9349a193c0a62687c79b218`
- Feature parent: `565f727a432e3fd0d11ac8223c790691f05530aa`
- Merge base: `c45fb567f2c6db6b986f95cf14d35964511ea26b`
- Non-fast-forward merge commit: `dcd25aec21bbbec3206cec8a4797d5eea15c6d91`
- Final source-integration commit: `a501e0942c9927f3e5d67b290d47279880fa42ec`
- The published branch SHA is reported in the final handoff because the documentation commit cannot contain its own SHA.

Both parent histories are retained. The integration branch was created in the isolated
`shackcq-mobile-integration-v1` worktree; `main` and the existing feature worktrees were not modified.

## Commits created

1. `dcd25ae` — history-preserving semantic merge.
2. `a501e09` — focused RF-evidence and attribution corrections.
3. One documentation commit containing this record.

## Conflict resolutions

Nine conflicted paths were resolved from the pinned base, main, and feature versions:

- `AndroidManifest.xml`: retained the current backup/data-extraction policies and the single FileProvider.
- `file_paths.xml`: retained both Groups.io private-file sharing and Operations export cache paths.
- `FeatureController.kt`: combined main worked-log/CTY truth with typed RBN, foreground lifecycle,
  sunspot handling, and exact maintenance-job generation ownership.
- `HamClockHomeScreen.kt`: retained truthful current-opportunity wording rather than probability claims.
- `MainActivity.kt`: produced one navigation/controller graph containing Home, Radio, Digi,
  Panadapter, EQ, Logbook, Progress, Sync, Presets, DX, Portable, Operations, Groups.io, and Settings.
- `NeuralDxScreen.kt`: retained current main opportunity/history truth and feature RF-evidence interaction.
- `QsoDatabase.kt`: retained main worked-log scope alongside the version-2 indexed projection and repair paths.
- `QSOStore.swift`: retained worked-log notifications and Fast Entry revision/batch semantics.
- `project.pbxproj`: retained Groups.io, Wavelog Native v2, Fast Entry, and Fast Entry view membership.

No blanket ours/theirs resolution was used for a substantive production conflict.

## Ownership and storage

The Android application creates one live authority for each QSO database, mutation coordinator,
Wavelog controller/native controller, HamClock settings/public providers, Feature controller,
Neural DX controller, Progress controller, Operations controller, Portable controller, and Groups.io controller.
Satellite operations are owned by the single Operations controller hierarchy.

The canonical QSO database remains `shackcq.sqlite`, database version 13. All upgrade paths are
monotonic through versions 2–13. Projection contract version 2 retains transactional dual writes,
backfill, verification, and repair without a destructive fallback. Main-only worked/history scope is retained.

Groups.io remains outside QSO storage in `shackcq-groupsio.sqlite`, with its own cache, credentials
preferences, attachment files, backup/data-extraction exclusions, controller, tests, and Apple source.

## Integration corrections

- A cancelled RBN maintenance job can no longer clear a newly started job reference.
- Exact PSK Reporter rows use their actual report mode for worked-status identity; WSPR remains WSPR.
- RBN WATCHLIST and `watchlistOnly` use the shared normalized amateur-call identity, including prefix/portable forms.
- Band Health cluster evidence uses the same cluster presentation policy as DX, while contributor taps resolve
  the accepted source ID against the exact live feed so a filtered presentation cannot create a dead action.
- NOTICE now records compatible Wavelog, SGP4, SatNOGS, MapLibre/OpenFreeMap/OpenStreetMap, and Groups.io provenance.

## Native bridge and core

The JNI/C++ boundary retains both current CTY/feature functions and the satellite inspect, propagate,
passes, samples, and Doppler functions. Native CMake configuration and build completed, and CTest passed
`1/1` tests with zero failures.

## Android validation

- `testDebugUnitTest`: passed, 45 suites / 322 tests / 0 failures / 0 errors / 0 skipped.
- `assembleDebug`: passed with Android SDK paths, rustup Cargo/rustc 1.97.1, cargo-ndk 4.1.2,
  and all four Android Rust ABI targets selected explicitly.
- `compileDebugAndroidTestSources`: passed.
- `git diff --check`: passed.
- Wavelog upstream watcher: `NO CHANGE` at Wavelog 3.1.0 / `af3256140bd05403b7c4a421746c2ea653a4f04f`.
- OpenHamClock watcher: stable and release `v26.5.0` unchanged; preview advanced to
  `99913f2df574b8588ddaff703581b8f341f46761` and returned `REVIEW_REQUIRED` for a sensitive-area preview change.
  No unrequested preview work was absorbed into this consolidation.

Debug APK:

- Path: `android/app/build/outputs/apk/debug/app-debug.apk`
- Size: `109820912` bytes
- SHA-256: `690b4c76da81d34fb09f19606b61627290257aaa8f1b752b6011da2d3453947f`

The APK was not installed.

## Apple and physical boundaries

Apple source and project membership contain both Groups.io and Wavelog/OpenHamClock-era feature families,
including `GroupsIoFeature.swift`, `WavelogNativeV2.swift`, `FastEntry.swift`, and `FastEntryView.swift`.
No Apple build was required by the consolidation validation programme and none is claimed here.

No device, authenticated Wavelog account, live provider, RF, CAT, audio, PTT, TUNE, or transmit acceptance
was exercised. Physical tablet layout, map interaction, provider behaviour, receive-review actions, radio/RF
behaviour, and Apple runtime acceptance remain explicit external acceptance items.

## Publication proof

After the documentation commit, the integration branch is pushed non-force to the branch of the same name.
The final handoff records the published SHA and verifies a clean worktree and equality between local HEAD and
`origin/integration/wavelog-openhamclock-groupsio-v1`. The branch is not merged into `main` and is not deployed or released.
