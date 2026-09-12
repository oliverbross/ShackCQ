# Nexus-native desktop packaging

`desktop/shackcq-tauri` is the Tauri v2 package source for product version
`0.2.0`, identifier `online.shackcq.desktop`. It declares DMG, current-user NSIS,
Debian and AppImage targets. The package bundles the target-suffixed
`shackcq-nexus-runtime` sidecar; users do not install Rust, Node, CMake,
gfortran, FFTW, WSJT-X, rigctld or Nexus separately.

The candidate build order is:

1. initialize and verify the pinned submodule;
2. run `scripts/check_nexus_native_desktop.py`;
3. build/test `desktop/nexus-runtime` headlessly;
4. build the release sidecar with `--features live-audio` and copy it with the
   Tauri target-triple suffix;
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
