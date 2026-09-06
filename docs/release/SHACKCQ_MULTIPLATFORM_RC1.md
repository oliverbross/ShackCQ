# ShackCQ Multiplatform RC1

This branch is the whole-repository release candidate assembled from canonical source `bf297ae5d13708c16fe3ed621f29b2f649c36110`. It contains accepted UI lineage `de32c8ac908c7979f39bfdfc41ca050378901e75` and the separately audited semantic integration at `5ee25b51d979d319bdc2bc9410c5af3599b87887`.

The RC covers Android, iOS, Windows and macOS source; shared C++ and Rust engines; deterministic fake protocols; schema/configuration contracts; privacy/provenance; exact-SHA packaging; and source/SBOM distribution. It does not authorize promotion, signing, distribution, deployment, hardware transmission, RF, rotator motion, authenticated-service mutation, or release publication.

Release identity is `channel=RC`, exact Git SHA, UTC build timestamp, schema set `QSO 16 / Neural 5 / Contest 2 / Digi 2 / Groups.io 2 / DX Chaser 1`, and target platform. Marketing versions, package identifiers and signing identities are unchanged.

The authoritative entry point is `.github/workflows/shackcq-multiplatform-candidate.yml`; `scripts/check_rc1_convergence.py` is the repository contract gate; `scripts/prepare_rc1_distribution.py` creates the exact-SHA source archive, SPDX SBOM, source/build manifests and SHA-256 ledger.

Physical and authenticated acceptance is intentionally separated in `RC1_LIVE_ACCEPTANCE_CHECKLIST.md`. A passing build is not evidence for audio, visual, CAT/PTT/TUNE, RF, rotator, provider authentication, signing or distribution.
