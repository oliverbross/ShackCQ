# HamClock Finish Line — Broad Phase 1 completion record

## Verdict

`PARTIAL — BROAD MILESTONES COMPLETE WITH EXPLICIT BLOCKER`

Milestones 4–9 are source-complete on Android. Milestones 0–3 reached a truthful production
boundary, but a working local P.533 engine, its scientific reference comparison, the 24-hour
matrix, coverage heatmap, and MUF layer remain blocked because the reviewed ITU source/data does
not carry an unambiguous redistribution grant. No prediction is fabricated.

## Repository and commits

- Branch: `feature/hamclock-finishline-v1`
- Start SHA: `4a692a1b1d5653b55f21552bcc67a35fdc2b0172`
- Validated implementation SHA: `71e865e29ba70d5a195a22dea33b85c73c63d194`
- Final SHA: the completion-record commit containing this file, resolved and reported at handoff
- Worktree: `/Users/oliver/Documents/Projects/ShackCQ/shackcq-mobile-hamclock-finishline-v1`

Commits before this completion record:

1. `76ee4fe43808d1da868edd5477470d0a56b94091` — pin P.533 provenance and finish-line ownership
2. `c6dcfd24c5ade9b698460264a5222f5e66560391` — add truthful native P.533 adapter boundary
3. `61ac86ab953400f78b730f759bafdd0dbfdbabfb` — add settings and official providers
4. `52101804eb144d841af5928dc7e0bfad00623cda` — add satellite tracks and contest-QSO layers
5. `cbef762f7d97029e4c60442afad591be13f526f4` — add ID reminder and shack display
6. `c860af69bb79fee168aeacf99965dad65bdc6eba` — close provider and display safety gaps
7. `71e865e29ba70d5a195a22dea33b85c73c63d194` — align the satellite truth audit

## Upstream watchers

- Wavelog: no change; reviewed 3.1.0 commit
  `af3256140bd05403b7c4a421746c2ea653a4f04f`; reviewed paths remained reachable; exit 0.
- OpenHamClock stable: unchanged at `d4a50eaaa61d3432a1de5f80cbe61790739930a5`.
- OpenHamClock release: unchanged at `v26.5.0`, commit
  `cc2415e70cce5f9a583fa32efaf1c66792d030df`.
- OpenHamClock preview: `REVIEW_REQUIRED`, exit 2. Staging advanced two commits from
  `36e5c1262dfde2057b2b4e6483be8c2215c70ad4` to
  `99913f2df574b8588ddaff703581b8f341f46761`; changes were limited to satellite telemetry
  derivation/tests. They were not absorbed into this pinned programme.

## Ownership audit

The protected Neural DX files and `MainActivity.kt` have a zero-path diff from the start SHA.
The dedicated merge contract is in `NEURAL_DX_LATER_MERGE_CONTRACT.md`.

## ITU-R P.533 source, licence, and packaging

- Source: `https://github.com/ITU-R-Study-Group-3/ITU-R-HF`
- Tag/commit/tree: `v14.3` / `cd172be56dc04b154e5d2fa91cbaa6ecf5284305` /
  `b4f8f1ed9b31f1e3adc64793bfde831afaefecd6`
- Source inventory SHA-256: `11cf23fdab4463b13578ea5acda87216ac32dff8746d6818db9273ed78073bfc`
- Data inventory SHA-256: `696d68edf43976aff555caa284a01f4798f543027dc0c2eb7f9915f94e4f914f`
- Candidate installed size: 564,856,257 bytes; simple gzip size: 383,264,044 bytes.
- Manifest: `core/third_party/iturhfprop/SOURCE_MANIFEST.json`; `vendored_files` is empty.

The repository has no `LICENSE`, `COPYING`, or `NOTICE`. An implementation notice permits use
without copyright assertions, while source headers reserve all rights and prohibit reproduction
without written ITU permission. This is not an unambiguous redistribution grant. No ITU source,
coefficient, or data file was copied. Written ITU redistribution permission is the unblocker.
Even if cleared, the monolithic candidate pack cannot meet the 180 MB debug APK ceiling; a future
integration needs immutable, hash-verified, data-only month packs in an app-private last-good cache.

## Native propagation boundary and product result

The independent C++ API defines validated station coordinates, UTC date/hour, SSN, RF assumptions,
frequencies, noise, reliability, SNR, bandwidth, modulation, and path type. Its result records
availability, explicit status, model/data-pack identity, errors/warnings, and elapsed microseconds.
JNI and Kotlin wrappers are wired into Android. With no licensed engine/data, evaluation returns
`LICENSE_BLOCKED`; it is not represented as P.533 output.

Consequently there is no scientific reference comparison and no performance claim for P.533
calculation. The native validation/boundary tests pass. The existing bounded OpenHamClock REST
current-band adapter remains the disclosed fallback. The chart, 24-hour matrix, P.533 coverage,
and MUF layers remain truthfully partial/unavailable rather than reusing the heuristic or issuing
per-cell network calls.

## Completed Android outcomes

- Solar/space weather/aurora: NASA SDO AIA 171/193/304, HMI continuum and magnetogram images use
  MIME/size/dimension validation, a 4 MB response bound, 2048 px decode bound, cadence/cooldown,
  low-data disablement, and last-good state. NOAA official summary wind/Bz, GOES protons/X-ray,
  alerts, and bounded OVATION coordinates are source-labelled. Aurora renders capped provider
  coordinates; no oval is fabricated. All five SDO URLs and the NOAA contracts were live-probed.
- Satellites: Home reuses `SatelliteOperationsController` and its native SGP4 owner. Saved selection,
  tracks, and footprints are consumed. Rendering is capped at four tracks and four radio-horizon
  footprints, with dateline-safe geometry. No second orbital engine exists.
- Contest QSOs: a bounded indexed `qso_projection` query returns at most 200 filtered records;
  geometry is emitted only for valid grids, and typed selection opens the exact QSO.
- ID timer: persisted reminder-only timer with allowed intervals, manual start/reset/pause,
  `ID SENT`, optional first verified-TX start, clock-change reset, and permission-gated notification.
  It never transmits or dispatches a radio command.
- Shack display: explicit full-screen Compose dialog, persistent Exit, lifecycle system-bar restore,
  optional keep-awake, safe insets, standard dark/amber/red profiles, reduced motion, and optional
  touch-paused module rotation.
- Settings: schema 5 to 6 migration adds bounded propagation assumptions, ID-reminder state, and
  shack-display preferences. Profile import/export/reset and normalization include the new fields.

## Validation and package evidence

- Focused `HamClockFinishLineTest`: passed.
- Full Android `testDebugUnitTest`: 328 tests, 0 failures, 0 errors, 0 skipped.
- Android `assembleDebug`: passed.
- Android `compileDebugAndroidTestSources`: passed.
- Native CMake build and CTest: 2 tests passed, 0 failed.
- `git diff --check`: passed.
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- APK size: 110,106,490 bytes (104.99 MiB), below the 180 MB ceiling.
- Baseline size: 109,820,912 bytes; delta: 285,578 bytes.
- APK SHA-256: `8ade6b52ff398c48a368b62c6fbc932e914b1880739729794284bb0dbab03bb6`
- APK archive audit found no ITU/P533 source or data payload.

No APK was installed. Tablet UI, notification delivery, lifecycle/rotation behavior, live long-run
provider degradation, RF behavior, and authenticated-service acceptance remain external evidence.
Build output is not presented as physical UI, RF, or service proof.

## Parity result and ranked backlog

- Panels before: `NATIVE 19 / PARTIAL 3 / MISSING 8 / DELEGATED 5 / EXCLUDED 2`
- Panels after: `NATIVE 23 / PARTIAL 2 / MISSING 6 / DELEGATED 5 / EXCLUDED 2`
- Maps before: `NATIVE 7 / PARTIAL 3 / MISSING 12 / EXCLUDED 2`
- Maps after: `NATIVE 10 / PARTIAL 1 / MISSING 11 / EXCLUDED 2`

Remaining backlog, in rank order:

1. `NEXT CORE` — APRS.
2. `NEXT CORE` — Winlink gateways.
3. `PROVIDER_BLOCKED` — weather radar, clouds, and hazard layers.
4. `PROVIDER_BLOCKED` — WWBOTA, pending a stable provider contract.
5. `PLATFORM_PARITY` — iPad/iPhone native HamClock parity.
6. `PLATFORM_PARITY` — desktop shell parity.
7. `OPTIONAL` — Meshtastic/MeshCom.
8. `OPTIONAL` — external bridges.

At handoff the completion-record commit is pushed to `origin/feature/hamclock-finishline-v1`, local
HEAD equals the remote-tracking SHA, and the worktree is clean. This statement is a required final
gate and must be verified after the record commit is created.
