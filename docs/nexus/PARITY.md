# Nexus Digi v2 parity ledger

| Product behaviour | Status | ShackCQ authority / boundary |
|---|---|---|
| Audio setup and health | NATIVE | Typed USB/Flex health over the existing single audio owner |
| Waterfall/scope | NATIVE | Bounded Rust spectrum ABI and exact click-to-net cockpit |
| Decode history | NATIVE | Separate bounded `shackcq-digi.sqlite` store |
| FT8/FT4 sequencing | NATIVE | Operator-started, station-locked sequencer |
| Decode intelligence/roster | DELEGATED_TO_SHACKCQ | CTY, QSO projection, Needs and watchlist authorities |
| Logging | DELEGATED_TO_SHACKCQ | `QsoMutationCoordinator` and the one Wavelog outbox |
| WSJT-X UDP | NATIVE | Disabled-by-default bounded socket owner; loopback default |
| CW workflow | NATIVE | Native modem, pitch/WPM and explicit one-shot TX |
| RTTY workflow | PARTIAL | Exact-centre manual transcript; no fabricated continuous confidence |
| PSK31 workflow | PARTIAL | BPSK31 bounded-window/reacquire; QPSK31 excluded |
| SSTV workflow/gallery | NATIVE | Native RX/TX, atomic private PNG gallery and ISS RX boundary |
| Settings/recovery | NATIVE | One versioned, non-arming settings contract |
| Keyboard/accessibility | PARTIAL | Native Compose semantics and bounded keyboard TX in this programme |
| Diagnostics | NATIVE | Sanitized bounded journal and typed health |
| Radio safety | DELEGATED_TO_SHACKCQ | Existing explicit arm, confirmed PTT, STOP and RX cleanup interlocks |
| Upstream maintainability | NATIVE | `UPSTREAM.json` plus read-only weekly watcher |
| Desktop audio/radio/window architecture | DESKTOP_NOT_APPLICABLE | Android-native architecture retained |
| Excluded Nexus modes/product domains | EXCLUDED_MODE | Frozen inventory and exclusions in `UPSTREAM.json` |

RTTY and PSK31 limits are deliberate and visible. No source/build result
upgrades pending physical USB, RF, authenticated Wavelog, live UDP peer, or
PTT/TUNE acceptance evidence.
