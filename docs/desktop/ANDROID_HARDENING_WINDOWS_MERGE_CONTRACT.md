# Android hardening to Windows later-merge contract

Date: 2026-08-25

## Frozen inputs

- Hardened Android branch: `fix/android-native-lifecycle-hardening-v1`
- Hardened implementation anchor: `25bc191b868b75facc27bc086ba1e8bd42003d8a`
- Windows branch: `feature/windows-desktop-alpha-v1`
- Windows SHA: `f6ce7b3adf1e9582c74d61d6ffd0a16d5db38aa3`
- Merge base: `d1e956d2c21eefc905a5ecab086a8f467b7a03c4`
- Frozen remote main: `fb04d52df0c9ccc305125449bb188ef8e3f0185e`

## Shared-file conflict surface

Both lines change:

```text
android/app/src/main/java/app/shackcq/mobile/AndroidRotatorIntegration.kt
android/app/src/main/java/app/shackcq/mobile/FeatureController.kt
android/app/src/main/java/app/shackcq/mobile/MainActivity.kt
android/app/src/main/java/app/shackcq/mobile/OperationsScreen.kt
android/app/src/main/java/app/shackcq/mobile/PortableChaseScreen.kt
android/app/src/main/java/app/shackcq/mobile/ProgressScreen.kt
core/CMakeLists.txt
```

Windows also changes desktop CMake/presets, Qt/QML application code, desktop data/radio/rotator/panadapter services, packaging, desktop workflows and shared documentation. Android hardening changes Android JNI/Kotlin owners, lifecycle tests, sanitizer workflow and lifecycle documentation. The highest-risk semantic conflict is `core/CMakeLists.txt`: retain desktop targets while preserving opt-in ASan/UBSan flags and `shackcq_lifecycle_stress_tests`.

## Required later sequence

1. Create a new integration branch from the final hardened Android SHA.
2. Merge `feature/windows-desktop-alpha-v1` exactly once with `--no-ff`.
3. Resolve shared Android files semantically: preserve checked handle ownership, stale-generation rejection, bounded non-blocking close and disconnected restore while retaining Windows reachability/documentation additions.
4. Resolve shared CMake/docs/CI semantically; preserve Windows Qt/package history and Android sanitizer/lifecycle gates.
5. Run Android, Windows, macOS and iOS gates at the merge result.
6. Leave `main` unchanged until physical Windows review.

This task does not merge Windows and does not modify `desktop/**` other than this contract.
