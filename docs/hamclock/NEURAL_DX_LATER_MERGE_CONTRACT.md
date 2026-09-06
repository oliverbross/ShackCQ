# Neural DX later merge contract

## Protected ownership result

The HamClock finish-line branch was compared to baseline
`4a692a1b1d5653b55f21552bcc67a35fdc2b0172`. These Neural DX-owned files are untouched:

- `android/app/src/main/java/app/shackcq/mobile/FeatureController.kt`
- `android/app/src/main/java/app/shackcq/mobile/NeuralDxController.kt`
- `android/app/src/main/java/app/shackcq/mobile/NeuralDxScreen.kt`
- `android/app/src/main/java/app/shackcq/mobile/NeuralDxMap.kt`
- `core/portable/include/kx3/dx_analysis.hpp`
- `core/portable/src/dx_analysis.cpp`
- `core/src/features.cpp`
- `docs/NEURAL_DX_WATCHER_INTEGRATION.md`
- `docs/neural-dx-watcher-parity.md`

`android/app/src/main/java/app/shackcq/mobile/MainActivity.kt` is also untouched. No bridge
exception was used.

## Expected overlap and semantic ownership

No protected file overlap exists. The concurrent Neural DX provider-cache branch may overlap
`HamClockHomeScreen.kt` or adjacent Home integration state when it is merged later. Resolve such
overlap semantically:

- Neural DX owns provider freshness/cache behavior, opportunity ranking, and its controller state.
- HamClock finish-line owns Home composition, official solar/space-weather lifecycles, contest and
  satellite map presentation, the ID reminder, shack display, and their saved settings.
- Existing shared controller instances remain authoritative; do not create a second Neural DX,
  satellite, radio, or provider lifecycle to avoid a textual conflict.
- Preserve both bounded caches and both source-truth/degraded-state contracts.
- Preserve receive-only safety and the rule that no background feature dispatches radio commands.

Do not resolve any overlapping file with a blanket `ours` or `theirs` choice. Review the base,
both branch versions, and the owning tests, then combine the behaviors explicitly and rerun the
full Android unit suite, APK assembly, and protected-file audit.
