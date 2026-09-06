# RC1 Reproducible Build

Every job checks `git rev-parse HEAD == github.sha` before building. Metadata embeds channel `RC`, exact SHA, UTC build time, schema versions and platform without changing package identifiers, marketing versions or signing identities.

Pinned inputs include Qt 6.11.2, Hamlib 4.7.2, MinGW-w64 13.1, NSIS 3.11, Android SDK/NDK workflow setup, Rust lockfiles and checked-in third-party manifests. The same desktop source tree builds on Windows and macOS; the same shared C++/Rust engines feed mobile and desktop packages.

Reproduction sequence:

1. fetch the immutable SHA and verify the lineage contract;
2. run `scripts/check_rc1_convergence.py`;
3. run normal, ASan and UBSan C++ suites plus locked release-critical Rust suites (`mfsk-core --lib --all-features` activates its complete vendored protocol catalogue; ShackCQ FFI/public-recording goldens run in `shackcq-flex`; `tempo-sstv --lib` is the bounded hosted gate);
4. build Android JVM/lint/instrumentation-source/APK/AAB gates;
5. build generic Apple device and simulator targets;
6. build and test Windows/macOS desktop with deterministic galleries;
7. generate source/SBOM/manifests and copy exact-SHA packages with `prepare_rc1_distribution.py`;
8. verify `SHA256SUMS.txt` in a clean directory.

UTC timestamps are recorded rather than claimed byte-reproducible across signing/packaging tools. Source tree and content hashes are reproducible; signing is excluded.

The complete `tempo-sstv` integration catalogue is an extended local quality gate rather than the bounded hosted release job. Its full-picture cases can require hours; they must retain real exit evidence and must never be represented by a wrapper timeout.
