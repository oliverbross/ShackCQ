# Intelligent Band Maps v1 validation evidence

This file records Task C evidence from the isolated feature worktree. Source, build, emulator/device, authenticated
service and RF evidence are intentionally separated.

## Immutable source gate

- Frozen integrated base: `98490b6d5234c3f12cc5d00bbea3163c8273c3dc`
- Task-start `origin/main`: `b4f12e17fa87df16d2094b518ae187553e370be5`
- Base equals `origin/main`: no
- Exact remote integration ref/object/type gate: PASS
- Keyer `ecba146f…`, Contest/N1MM `d3f2a3b…`, DX Chaser `b30ee05…` ancestry gates: PASS
- Required isolated branch/worktree: PASS

## Automated evidence

### Android and JVM

- `:app:compileDebugKotlin`: PASS.
- `:app:testDebugUnitTest`: PASS — 543 tests, 0 failures, 0 skipped across 69 XML result files.
- `:app:lintDebug`: PASS — `BUILD SUCCESSFUL` in 9m 29s. Earlier wrapper exit 143 was an explicitly discarded
  infrastructure interruption while removing a stale duplicate daemon, not a source result.
- `:app:assembleDebug`: PASS.
- `:app:assembleDebugAndroidTest`: PASS in 38s. The pre-existing Groups.io deprecation warning remains non-blocking.
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 111,833,056 bytes,
  SHA-256 `d791052e35d61ab0b565fef06f586c1e8d2cd51e758537cb0c996e884b86273a`.
- Android-test APK: `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, 1,172,239 bytes,
  SHA-256 `62dca8118143167841cbd6593b664fe1f55617478712ea785406c37e68c06126`.
- No emulator was attached, so the task's conditional focused instrumentation run was not applicable.

The first 20,000-observation scale run correctly exposed a linear coalescing search; frequency-bucket indexing replaced
it and the focused scale test then passed in the Gradle task's 11-second run. The final full JVM suite includes that
regression. `scripts/run_release_soak.py` also passed all five deterministic 100k-logbook, neural-compaction,
20k-digi, 30k-Groups.io and provider-lifecycle assertions.

### Shared core and release policy

- The shell's default `cmake` command was unavailable. The repository-compatible Android SDK CMake 3.22.1 binaries
  configured and built the unchanged shared core successfully at `/tmp/shackcq-bandmaps-core`.
- `ctest`: PASS — 2/2 tests, 0 failures, 1.08s.
- `scripts/check_release_candidate.py`: PASS — documentation, schemas, golden configuration and privacy contracts.
- `scripts/audit_android_package_size.py`: PASS (exit 0). Debug APK delta was +2,012,144 bytes versus the combined
  integration reference; archive size was 111,833,056 bytes.

### Ownership and upstream review

- Band Map package network socket/client and provider-URL scan: no matches.
- CAT/PTT/Keyer/Digi transmission-owner scan: no matches.
- Direct DX Chaser engine/database mutation and Keyer dispatch scan: no owner matches. Local `submit` matches are
  observation submission to `BandMapController`, not dispatch.
- Duplicate contest evaluator and full-log materialisation scan: no matches.
- No source delta exists under `ios`, `core` or `rust` relative to the frozen base.
- Wavelog watcher: NO CHANGE, exit 0.
- MSHV Auto DX Chaser watcher: no review required, exit 0.
- OpenHamClock watcher: REVIEW REQUIRED, exit 2. Stable branch, v26.5.0 release, package and licence pins are unchanged;
  preview moved from `36e5c126…` to `99913f2d…` with a security-category trigger.
- Nexus watcher: REVIEW REQUIRED, exit 2. Package 1.7.5 / `57d11fd5…` moved to 1.7.6 / `f0869a11…` with changes in
  changelog, engine, audio and roster paths.
- The two watcher movements are review-only and non-blocking under Task C because reviewed stable pins/licences remain
  unchanged and no upstream source was copied or absorbed.

Hosted exact-SHA workflow identity and job results are recorded in the final Task C execution report after the feature
commit is pushed, avoiding a circular evidence commit that would change the SHA under test.

## Evidence not performed

No APK was installed. App data was not cleared. No emulator was assumed. No physical tablet, touch, keyboard,
screen-reader, authenticated provider, live CAT, audio, RF or transmit test was performed. Apple and desktop parity were
not implemented or claimed.
