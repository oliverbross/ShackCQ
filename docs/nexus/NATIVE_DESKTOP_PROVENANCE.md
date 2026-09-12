# Nexus-native desktop provenance

ShackCQ Desktop pins the complete `kd9taw/Nexus` source as the auditable Git
submodule `third_party/nexus` at release `v1.10.3`, commit
`7618390658f8f92431dec0ac65979b84f2c0fb76`, tree
`5819ba0a27f34a8c0505ded93ebb7b0cee2d1d8b`. The machine-readable record is
`NATIVE_DESKTOP_UPSTREAM.json`; `scripts/check_nexus_native_desktop.py` verifies
the gitlink, release, tree, licence hashes, dependency paths and excluded
features.

`desktop/nexus-runtime` links the selected source's actual `ft8`, `ft4` and
`tempo-audio` crates. Those wrappers compile `libtempo`: Nexus's C ABI plus its
vendored WSJT-X-derived Fortran/C/C++ modem and FFTW. The adapter calls the real
decode and encode APIs; it contains no modem implementation and no legacy
fallback. Upstream FT8 and FT4 off-air WAV fixtures remain under upstream's
licence/notice and are referenced in place rather than copied.

The `tempo-audio/device` feature is enabled only by the candidate package's
`live-audio` feature. The owned input path uses Nexus's device inventory,
wait-free sample ring and stateful anti-aliased capture resampler. It opens one
explicitly selected input only after `Start receiving`; it never constructs
Nexus `Service`, `Rig`, `Transceiver`, rigctld, OmniRig, native CI-V, serial CAT,
Flex CAT or an output stream. Existing `shackcq-hamlib-helper` remains the sole
radio owner. Nexus cloud/logbook connectors, propagation/network clients and
updater are not dependencies of the Tauri shell.

WSPR is not declared, callable, or advertised. Nexus's unconditional internal
`modes` dependency means its WSPR crate is nevertheless present in the Rust
link closure when `tempo-audio/device` is compiled. Its vendored `fano.c` and
`jelinek.c` rely on the WSJT-X project-wide GPL grant rather than per-file
grants, so distribution remains an explicit owner licence-acceptance gate.
DeepCW is
unavailable because its AGPL model is deliberately absent. No AI-CW fallback is
advertised.
