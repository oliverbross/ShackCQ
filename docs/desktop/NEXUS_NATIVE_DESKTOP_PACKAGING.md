# Nexus-native desktop packaging

`desktop/shackcq-tauri` is the Tauri v2 package source for product version
`0.2.0`, identifier `online.shackcq.desktop`. It declares DMG, current-user NSIS,
Debian and AppImage targets. A complete candidate bundles the target-suffixed
`shackcq-nexus-runtime`, `shackcq-stationd` and `shackcq-hamlib-helper`
sidecars; users do not install Rust, Node, Qt, CMake, gfortran, FFTW, WSJT-X,
rigctld, Nexus or a second ShackCQ Agent separately.

The candidate build order is:

1. initialize and verify the pinned submodule;
2. run `scripts/check_nexus_native_desktop.py`;
3. build/test `desktop/nexus-runtime` headlessly;
4. build the release Nexus sidecar with `--features live-audio`, the canonical
   Agent in `--native-ingress-only` capable form, and the existing ShackCQ
   Hamlib helper, then copy all three with Tauri target-triple suffixes;
5. integrate only the reviewed deterministic shared UI snapshot and stamp its
   exact Web commit and content-tree SHA-256 in the manifest;
6. let the pre-build verifier prove its Web commit and content digest;
7. build the platform Tauri bundle and inspect native dependency closure.

macOS targets 13+ and declares microphone input/network-client entitlements.
Developer ID signing, notarization, stapling and a clean-Mac launch remain
owner/signing gates. Windows targets x64 current-user NSIS; Authenticode and a
clean Windows/WebView2 launch remain runner/signing gates. Linux declares an
Ubuntu 24.04/glibc 2.39 baseline matching selected Nexus guidance and names
WebKitGTK 4.1, ALSA and OpenSSL package dependencies; clean-target Deb/AppImage
launch remains a Linux-runner gate. No artifact is released by these scripts.

This candidate coexists with the installed Agent. It neither installs over,
launches, unpairs nor migrates the Agent. Hardware ownership handover remains an
explicit later UI/owner acceptance step; the old release is retained for
rollback.

`scripts/build_nexus_desktop_macos.sh` is the complete local macOS composition
entry point. At native commit `4c9ca9c213036cadcf896ac74007f8408101f70e`
and shared-Web commit `bf28fad4879aff8b310fe1712a026acd9c2e9927`, it
produced an arm64 DMG whose recursive 30-Mach-O loader audit found no Homebrew,
worktree or missing dependency, required all four executable roots, and enforced
macOS 13 minimum deployment. The packaged Agent passed an isolated
`--native-ingress-only` status and owner-token stop proof with hardware
autoconnect false. The DMG SHA-256 is
`117b5c2d6156f794e04454980420c0819482706d6a8a41e321d40fae80b87837`.
It is ad-hoc signed, unsigned by Developer ID, unnotarized, and was not launched
graphically or installed.
