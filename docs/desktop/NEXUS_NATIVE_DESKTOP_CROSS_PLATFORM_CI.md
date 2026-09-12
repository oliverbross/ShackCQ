# Nexus desktop cross-platform candidate CI

`.github/workflows/nexus-desktop-cross-platform-candidate.yml` is a read-only
candidate workflow for the committed ShackCQ Tauri/Nexus desktop. It runs on
the two isolated feature branches named in the workflow or by manual dispatch.
It never publishes a release, signs, notarizes, deploys, opens audio or radio
hardware, or enables Digi transmission.

Both jobs verify the exact checkout, native base
`68cebdc2991cf9754477e29ac82228de6f8b8107`, Nexus pin
`7618390658f8f92431dec0ac65979b84f2c0fb76`, and immutable shared-Web stamp.
They use Rust 1.91.0, Node 24, and the exact npm Tauri CLI 2.11.4. Both build
the Tauri application, live-audio Nexus sidecar, canonical `shackcq-stationd`
Agent, and fixed ShackCQ Hamlib helper. Lockfile drift fails the build.

## Windows x64

The Windows 2025 job builds natively under MSYS2 for
`x86_64-pc-windows-gnu`. MinGW GCC, G++, gfortran, and a checksum-verified
static FFTW3f build satisfy the selected Nexus WSJT-X-derived modem crates.
Dynamic GNU/Fortran/FFTW runtime imports are rejected. It produces a
current-user NSIS candidate and verifies all three sidecars are present.

The Agent uses the published official Qt 6.10.2 `win64_mingw` archive. Qt
6.11.2 has no Windows repository metadata, while Linux and macOS remain on Qt
6.11.2; CMake requires the exact selected platform version. This platform
difference is explicit and must be retained in candidate evidence.

Before packaging, the job starts the freshly built Agent with an isolated
ephemeral root, ephemeral credentials, and `--native-ingress-only`. Through the
production Qt local server and Rust Windows named-pipe client it verifies status,
wrong-owner-token refusal, and correct-owner-token graceful stop. It does not
attempt cloud pairing. `QLocalServer::UserAccessOption` limits the pipe to the
current user. Authenticode and clean Windows/WebView2 launch remain later gates.

## Linux x86_64

The Linux job builds natively on Ubuntu 24.04 using Qt 6.11.2 and the declared
glibc 2.39 baseline. It provides the documented Nexus gfortran/FFTW/Boost,
Tauri WebKitGTK, Secret Service, ALSA, and Agent dependencies. It produces DEB
and AppImage candidates, records four ELF dependency graphs, rejects unresolved
or worktree-bound libraries, requires the modem runtime packages in Debian
metadata, and verifies the Nexus runtime, Agent, and Hamlib helper in each
package. Clean-target graphical launch and physical codec acceptance remain
later gates.

## Evidence and boundaries

Artifacts are workflow-only and named `UNSIGNED-UNNOTARIZED`.
`CANDIDATE_STATUS.txt` records source/component identities and safety state;
`SHA256SUMS.txt` covers every staged file. A candidate is cross-platform
packaging evidence only after both clean runner jobs pass. Local checks are not
a substitute for those jobs.

DeepCW, upstream Nexus radio ownership, external loggers, updater identity,
rigctld, and the upstream Nexus UI remain absent. ShackCQ Agent supervision is
connect-existing-or-own-child only: a legacy socket owner is never killed or
replaced. An owned child starts with hardware autoconnect disabled and can be
stopped only by its per-launch owner token. Production Digi TX remains disabled.
