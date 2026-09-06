# Android hardened + Windows Alpha integration v1

This candidate starts at hardened Android `aea57b1ea3cf06d78089e37015b283dc7b068ac5` and preserves the 27-commit Windows Alpha history at `f6ce7b3adf1e9582c74d61d6ffd0a16d5db38aa3` through merge commit `7ba507e`. It does not update either source branch or local/remote `main`.

The integration reconciles the optional shared-core test build, host sanitizer gates and MSVC warning policy; retains one Hamlib 4.7.2 source pin; hardens Qt Wavelog and service shutdown; adds shared schema/configuration/Wavelog fixtures; and converges existing hosted workflows behind `shackcq-multiplatform-candidate.yml`.

Android production source and package identity are unchanged by the Windows merge. The added Android test reads repository-owned cross-platform fixtures only. A protected-tablet reinstall is therefore not part of this candidate task.

Evidence is layered: source review, local/hosted tests, builds and packages are recorded separately from physical Windows installation, authenticated Wavelog, live cluster, hardware, audio, CAT/PTT/TUNE, RF and rotator evidence.
