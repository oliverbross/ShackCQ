# RC1 Source Distribution

`scripts/prepare_rc1_distribution.py` creates a source archive directly from the exact Git tree, a per-file `SOURCE_MANIFEST.json`, SPDX 2.3 `SBOM.spdx.json`, `BUILD_MANIFEST.json` and `SHA256SUMS.txt`. The archive is named `ShackCQ-source-<exact-sha>.tar.gz` and includes the complete tracked corresponding source, licences, notices, build scripts and vendored-source provenance.

The SBOM records ShackCQ plus the single pinned Hamlib 4.7.2 authority, SDRoxide provenance record, `mfsk-core`, `tempo-sstv`, SGP4, ITUHFProp, CTY and band-plan snapshots. `NOTICE` and the detailed third-party audit remain authoritative for licence texts, modifications and upstream locations.

The upstream `mfsk-core` external corpus under `embedded-poc/assets/golden` is not vendored and is not silently downloaded. RC validation runs the full vendored library catalogue plus ShackCQ's checked-in public-recording, FFI and golden contracts.

Binary release asset naming is versioned; each hosted artifact container and manifest records the exact source SHA:

- `ShackCQ-Android-arm64-v0.1.0-rc.1.apk`
- `ShackCQ-Android-four-ABI-v0.1.0-rc.1.aab`
- `ShackCQ-Windows-x64-portable-v0.1.0-rc.1.zip`
- `ShackCQ-Windows-x64-setup-v0.1.0-rc.1.exe`
- `ShackCQ-macOS-arm64-unsigned-v0.1.0-rc.1.zip`

Artifacts are unsigned RC evidence. Creation does not publish or distribute them.

Secure Remote Station v6 additionally produces exact-SHA unsigned stationd packages for Windows x64, macOS arm64 and Linux x64. The package gates reject private identities, PEM keys and private rigctld configuration. `scripts/prepare_rc1_distribution.py` remains the authority for the source archive, SPDX SBOM, manifests and digest ledger; package creation is not publication or deployment.
