# HamClock Finish Line ownership

- Baseline branch: `integration/wavelog-openhamclock-groupsio-v1`
- Baseline SHA: `4a692a1b1d5653b55f21552bcc67a35fdc2b0172`
- Work branch: `feature/hamclock-finishline-v1`
- Worktree: `shackcq-mobile-hamclock-finishline-v1`
- Concurrent owner branch observed at start: `fix/neural-dx-provider-freshness-cache` at `45eec41bc3e12abeecf87c9c59cde6012743b342`

The finish-line work owns new files under `hamclock/finishline`, propagation-only native files,
HamClock Home/map files, the satellite Operations controller, the bounded QSO projection query,
and the named HamClock documentation files.

The following concurrent Neural DX files are protected and must remain byte-identical to the
baseline: `FeatureController.kt`, `NeuralDxController.kt`, `NeuralDxScreen.kt`, `NeuralDxMap.kt`,
`core/portable/include/kx3/dx_analysis.hpp`, `core/portable/src/dx_analysis.cpp`,
`core/src/features.cpp`, `docs/NEURAL_DX_WATCHER_INTEGRATION.md`, and
`docs/neural-dx-watcher-parity.md`. `MainActivity.kt` is also avoided.

No Apple source, device state, credentials, main branch, release, or deployment is in scope.

