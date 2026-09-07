# Sol-high Agent validation

Captured 2026-09-06T17:07:03Z against the corrected second-pass tree.

- Pinned Hamlib 4.7.2 provenance: PASS; commit
  `40f63488fe0bd751b147f48d62fd217bf53713a0`, 1,048 source files,
  37 configured backends, KX3 present, dedicated RGO ONE model absent.
- A fresh static Hamlib build completed and the required-Hamlib CMake build
  produced `ShackCQAgent`.
- The statically linked Agent enumerated 302 compiled models; KX3 was present
  and the unavailable fallback model was absent.
- All 12 local Qt/C++ test executables passed.
- Workflow YAML and the POSIX Hamlib build script parsed successfully.

No physical radio, RF, signing identity, Windows host, Linux host, or owner
credential was used. Platform package acceptance remains CI/owner-gated.
