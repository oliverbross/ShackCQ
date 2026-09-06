# SDRoxide Android Final Parity Audit v4

The final read-only review uses `dividebysandwich/sdroxide` stable release `v1.5.3`, commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`, GPL-3.0 licence SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`. `git ls-remote --tags --refs` was rechecked on 2026-08-27; no later stable tag existed.

The audit covered upstream TCI/session handling, receiver/audio routing, spectrum/waterfall, history, I/Q recording/replay, measurement, tracking, survey, scanner, monitoring, memories, calibration, demodulation, skimmers/digital modes, map/spots, logging, server/browser, MIDI, hardware backends, WSPR/TX, RF Paint and model-assisted features. The final classifications are in `SDROXIDE_ANDROID_FINAL_PARITY_MATRIX_V4.md`.

V4 closes every selected Android operator gap through existing ShackCQ owners. Deliberately deferred or excluded product families are not parity defects: they have no approved ownership/safety/protocol proposition in this programme. The final count is:

| Classification | Count |
|---|---:|
| `SHACKCQ_STRONGER` | 10 |
| `PARITY` | 12 |
| `DEFER_PRODUCT_DECISION` | 2 |
| `EXCLUDED` | 8 |
| `PLATFORM_NOT_APPLICABLE` | 3 |
| `IMPLEMENT_V4` | 0 |

No SDRoxide source, UI, asset, theme, shader, screenshot, recording, I/Q fixture, model, dependency or runtime payload was copied or packaged. Physical/live acceptance is not inferred from this audit.
