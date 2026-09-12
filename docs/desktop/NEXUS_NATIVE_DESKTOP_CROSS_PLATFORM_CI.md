# Nexus desktop cross-platform candidate CI

`.github/workflows/nexus-desktop-cross-platform-candidate.yml` is a manual,
read-only candidate workflow for the committed ShackCQ Tauri/Nexus desktop. It
does not run on pushes, publish a GitHub Release, sign, notarize, deploy, launch
the app, open audio devices, contact a radio, or enable Digi transmission.

The Windows x64 job runs on Ubuntu 24.04 and targets
`x86_64-pc-windows-gnu`. This is required by the pinned Nexus modem: its
vendored WSJT-X-derived code contains Fortran and links FFTW3f. The job uses the
documented MinGW-w64 GCC, G++, gfortran and binutils packages. It builds FFTW
3.3.10 as a static single-precision MinGW library after verifying SHA-256
`56c932549852cddcfafdab3820b0200c7742675be92179e59e6215b340e26467`.
The pinned Nexus `tempo-fast-sys` build then uses
`libtempo/mingw-w64.cmake` and statically links the matching GNU Fortran,
quadmath, C++ and FFTW runtimes. MSVC is deliberately not mixed with these
objects. The job records the main executable and sidecar PE imports and rejects
dynamic GNU/Fortran/FFTW runtime DLLs, which must remain statically linked. The
output is a current-user NSIS candidate; Authenticode and a clean Windows/WebView2
launch remain later gates.

The Linux job builds natively on Ubuntu 24.04 x86_64 with the documented Nexus
gfortran/FFTW/Boost toolchain, Tauri WebKitGTK/GTK/AppIndicator packages,
Secret Service support required by ShackCQ credential storage, and ALSA headers
required by the live-audio sidecar. It records `readelf` and `ldd` closure for
the main executable and sidecar, rejects unresolved or worktree-bound libraries,
and requires the Debian package to declare `libgfortran5`, `libfftw3-single3`,
`libstdc++6` and `libgcc-s1`. It produces Debian and AppImage candidates for the
declared Ubuntu 24.04/glibc 2.39 baseline. Clean-target launch and physical codec
acceptance remain later gates.

Both jobs recursively initialize the Nexus submodule, verify the exact checkout,
required native base `68cebdc2991cf9754477e29ac82228de6f8b8107`, Nexus pin and
vendored shared-Digi Web stamp, use Rust 1.91.0, Node 24 and the
exact npm Tauri CLI package `@tauri-apps/cli@2.11.4`, compile the locked runtime,
verify that neither Cargo lockfile changed, and include the target-suffixed
live-audio sidecar. Uploaded workflow artifacts,
package filenames and sidecar filenames are explicitly
labelled `UNSIGNED-UNNOTARIZED`; `CANDIDATE_STATUS.txt` repeats the source and
Nexus SHAs, signing status, workflow-only distribution boundary, pending
physical acceptance, unauthorized RF acceptance and disabled production Digi
TX. `SHA256SUMS.txt` covers every staged file.

The workflow proves packaging only after both clean GitHub runner jobs pass.
Local YAML/script validation and host Rust tests are not Windows installer or
Linux package evidence. DeepCW, upstream Nexus Hamlib resources and the upstream
Nexus UI are intentionally absent because this ShackCQ runtime links only the
selected FT8/FT4/tempo-audio adapter and retains the existing bounded
ShackCQ radio-helper ownership.

This Tauri package contains `shackcq-desktop` and the Nexus runtime sidecar. It
does not bundle `shackcq-stationd` or the canonical logging Agent, so its precise
integration state is
`CANONICAL_LOGBOOK_AGENT=NOT_BUNDLED_REQUIRES_SEPARATE_SHACKCQ_AGENT`.
These artifacts are not complete native logging candidates, and this bounded CI
slice does not expand into Qt, stationd or separate ShackCQ Agent packaging.
