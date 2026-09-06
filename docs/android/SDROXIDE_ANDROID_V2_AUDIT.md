# SDRoxide Android v2 Audit

Audited upstream: SDRoxide `v1.5.3`, commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`, released 2026-08-26. Licence: GPL-3.0; audited licence SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`.

Selected concepts were TCI receiver addressing/readback, dual receive audio, digit-oriented tuning, time review, candidate markers, scan banks/priority, and per-mode audio-level configuration. ShackCQ implements these independently in its existing Kotlin/Compose, JNI, and C++ owners.

The audit found stable TCI contracts for VFO, IF, mode, RX enable/mute, global volume, split, IQ/audio streams and rates, drive/tune-drive telemetry, and TX sensor telemetry. It found no stable spot exchange contract and no coherent-sampling/diversity capability contract, so both features fail closed as `UNAVAILABLE_PROTOCOL`.

No upstream source, assets, shader, recording, test IQ, model, submodule, or runtime payload is incorporated.
