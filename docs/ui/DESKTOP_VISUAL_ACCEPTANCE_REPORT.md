# Desktop Visual Acceptance Report

## Evidence layers

| Layer | Evidence | Current result |
|---|---|---|
| Tablet source | 41 new 2944×1840 unlocked captures, resolution/non-blank check | PASS as visual reference |
| Exact-base desktop | macOS and Windows base artifacts at `ce1b99f`; 114 frames/platform | PASS as before evidence |
| Source/UI contract | command registry, icon resources, native menus, freeform panel canvas, accessibility names, gallery completeness | PASS locally; 9/9 CTest groups |
| Local macOS deterministic gallery | 39 states at 1440×900, 1512×982, 1920×1080, 2560×1440 and effective 1280×720 at 150% | PASS; 195/195 frames |
| Local macOS lifecycle | 500 workspace changes, 100 native-menu command cycles, 100 Shack cycles, 100 Settings changes, 100 panel move/resize cycles, 50 full-screen cycles, 100 resizes and 100 command actions | PASS; QML objects 451 initial / 602 peak / 457 final |
| Local macOS pointing-device acceptance | Native Navigate and View menus; panel drag, corner resize, z-raise, navigation persistence, full quit/relaunch persistence and native layout reset | PASS in isolated private QA profile |
| Local macOS package | Unsigned `.app` bundle and ZIP with SHA-256 manifest | Recorded with local delivery evidence |
| Hosted Windows | Native menu source and contract coverage exist, but final canvas head build, visual run, installer and physical interaction acceptance | PENDING; GitHub Actions remain stopped at user request |
| Hosted macOS | Final hosted build/gallery/package | PENDING; GitHub Actions remain stopped at user request |

## Acceptance criteria

- Every workspace is reachable through the native Navigate menu, shortcuts, and the command palette without a persistent global side rail.
- macOS has no in-window Windows menu and the app/window identity is “ShackCQ”.
- Windows exposes a native File/Edit/View/Radio/Navigate/Tools/Window/Help menu in window chrome with Alt access.
- Every command icon resolves from the packaged original SVG family.
- Written status accompanies colour; focus borders and accessible names remain present.
- 1366×768 and an effective 1280×720 viewport at 150% retain every destination and Global Stop.
- Deterministic galleries remain isolated and do not read production credentials or operate hardware.

## Local final evidence

- `build/evidence/local-desktop-finish/ui-stress-final.json`
- `build/evidence/local-desktop-finish/ui-gallery-exact-final/` (five profiles, 39 PNG files each)
- Computer Use acceptance operated only the isolated `ShackCQLocalQA` copy with `SHACKCQ_DESKTOP_DEMO=1` and a private `SHACKCQ_DEMO_ROOT`.
- The QA sequence used native Navigate → Radio → Home and View → Reset Workspace Layout; it never selected Connect, transmit, keyer, tuning or rotator actions.
- Saved geometry restoration retains the intended logical-pixel rectangle while the startup canvas is still settling, preventing a transient zero-size clamp from replacing the user layout.

## Explicit non-claims

Visual acceptance does not prove authenticated Wavelog/Groups/providers, live audio, CAT/PTT/TUNE, Digi transmit, RF output, physical Windows rendering, multi-monitor use, screen-reader behavior on physical machines, signing/notarization, or rotator movement. Those layers retain their existing pending/blocked status.
