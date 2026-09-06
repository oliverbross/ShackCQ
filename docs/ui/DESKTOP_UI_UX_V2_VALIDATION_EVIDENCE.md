# Desktop UI/UX v2 Validation Evidence

This record separates source/build evidence from hosted Windows, physical,
authenticated-service, audio, CAT/PTT/TUNE, RF, rotator and release evidence.
All commands ran in the isolated
`feature/desktop-ui-ux-deep-convergence-v2` worktree. The accepted v1 source
remains `9c0e3d6f70013e95b39107f782dee0cb4b463e7c`.

## Desktop source and interaction gates

- Release Qt/macOS configure and build: PASS.
- Desktop CTest: 9/9 PASS, covering data, network, TCI, platform safety, RF
  observation, parity, UI contracts, scale soak and QML.
- Deterministic galleries: 58/58 unique PNGs in each of 1440×900, 1512×982,
  1920×1080, 2560×1440 and 150% effective 1280×720; zero warning/error lines.
- Gallery composition: 39 operating frames plus 19 explicit Edit Layout frames
  per profile.
- Lifecycle stress: PASS with 1,000 workspace changes, 250 sidebar
  collapse/expand cycles, 200 Edit Layout enter/exit cycles, 200 official-layout
  resets plus final recovery reset, 500 focus/raise cycles, 500 move/resize
  cycles, 100 Shack cycles, 100 Settings changes, 100 full-screen cycles, 200
  resize cycles and 100 command cycles. Process exit was zero and the log had
  no warning/error line.
- `git diff --check`: PASS before commit.

## Fresh-eyes finish review

The Impeccable finish review accepted the sidebar hierarchy, explicit Edit
Layout boundaries, screen-specific cockpit composition and written fail-closed
state. Its four findings were resolved before the final galleries:

1. Digi mode/review routes now remain visible but disabled until the owning
   workspace is source-complete; state is written as REVIEW ONLY/EVIDENCE ONLY.
2. EQ uses an authored graphite/amber disabled-slider treatment rather than
   native white slabs.
3. Header and narrow panel labels were shortened or given fixed layout bounds;
   the 1440×900 final frames show no broken primary header.
4. Logbook now presents a written zero-QSO explanation and import/Fast Entry/
   clear-filter recovery path.

No additional functionality claim follows from this visual disposition.

## Shared and mobile regressions

- Shared native CTest: normal 5/5 PASS, ASan 5/5 PASS and UBSan 5/5 PASS.
- Rust ShackCQ Flex: 98 passed, 0 failed, 1 ignored.
- Rust Tempo SSTV: 160 passed, 0 failed.
- Vendored MFSK all-features library gate: 407 passed, 0 failed, 28 ignored.
  Its default all-target command exposes an upstream example that requires the
  `uvpacket` feature, so the feature-complete library gate is the recorded
  regression result.
- Android combined gate: PASS for 713 JVM tests with zero failures/errors/
  skips, lint with zero errors, debug APK, debug AAB and Android-test source
  compilation. No emulator/device execution or install occurred.
- Apple: unsigned generic-device and generic-simulator builds PASS for both
  `ShackCQ` and `ShackCQHardware`.
- Android, iOS, shared-core and Rust source are unchanged by this desktop-only
  programme.

## Packaging and evidence boundaries

- The unsigned macOS app is deployed with Qt, retains only the SQLite SQL
  driver, and stays below the 150 MB ZIP ceiling. Exact final size/hash are
  written to `build/artifacts/macos-v2/SHA256SUMS.txt` after the branch-final
  build.
- Extracted-package Cocoa launch/gallery smoke: 58 frames. The production
  bundle intentionally contains the Cocoa platform plugin, not the development
  offscreen plugin.
- Comparison evidence is generated as one printable page per workspace at
  `build/evidence/v2-comparisons/index.html`. Tablet and accepted v1 evidence
  stay private/ignored; Windows v2 columns are explicitly PENDING rather than
  fabricated.
- Exact-SHA Windows build, four-profile gallery, interaction stress, ZIP/NSIS
  package and physical review remain hosted/external evidence. This makes the
  branch verdict PARTIAL until that gate completes.

No signing, notarisation, publishing, deployment, protected-tablet change,
authenticated provider mutation, physical audio/radio, transmit, tune, RF or
rotator movement is authorised or claimed.
