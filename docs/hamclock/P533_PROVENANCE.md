# ITU-R P.533 provenance and redistribution decision

## Reviewed source

- Repository: `https://github.com/ITU-R-Study-Group-3/ITU-R-HF`
- Tag: `v14.3`
- Commit: `cd172be56dc04b154e5d2fa91cbaa6ecf5284305`
- Tree: `b4f8f1ed9b31f1e3adc64793bfde831afaefecd6`
- Source inventory digest: `11cf23fdab4463b13578ea5acda87216ac32dff8746d6818db9273ed78073bfc`
- Data inventory digest: `696d68edf43976aff555caa284a01f4798f543027dc0c2eb7f9915f94e4f914f`

The review found no repository `LICENSE`, `COPYING`, or `NOTICE` file. The P533 implementation
notice says the software may be used by implementers free from copyright assertions. However,
`P533/Src/P533/Common.h` says all rights are reserved and that no part may be reproduced without
written ITU permission. Equivalent restrictive text is present in P372 and ITURHFProp headers.

Those statements do not establish an unambiguous permission to redistribute the source or data
inside a GPL-3.0-only Android application. ShackCQ therefore does not vendor, adapt, or ship any
v14.3 source or coefficient data in this phase. Written clarification from ITU is the precise
legal unblocker.

## Packaging audit

The reviewed candidate source/data inventory is 564,856,257 bytes installed and 383,264,044 bytes
as a simple gzip archive. The consolidated debug APK baseline is 109,820,912 bytes. Bundling the
candidate inventory would exceed the 180 MB debug APK ceiling even if redistribution were cleared.
A future licensed integration must use immutable, SHA-256-verified data-only month packs, with no
downloaded executable code and an app-private last-good cache.

## Implemented boundary

ShackCQ includes only an independently written input-validation and JNI adapter boundary. It
returns explicit `LICENSE_BLOCKED` unavailability and never fabricates P.533 values. The existing
bounded OpenHamClock REST adapter remains the truthful fallback for its current-band response.

