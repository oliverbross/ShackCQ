# SDRoxide Local Receiver Audit v3

## Immutable upstream

- Repository: `https://github.com/dividebysandwich/sdroxide`
- Stable release reverified: `v1.5.3`, published `2026-08-26T20:02:33Z`
- Commit: `a680935b10f33768a499435e8bd37f779fa640ae`
- Tree: `4697195080495da4a727b14234b85af89c10ecda`
- Licence: GPL-3.0; audited licence SHA-256 `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`

The immutable release was reviewed read-only for receiver selection, demodulation, passband/AGC/squelch behavior, NFM/WFM, tone/data metadata, recording and scanner integration. No upstream source, UI, icon, theme, screenshot, recording, model, I/Q fixture or binary entered ShackCQ.

## Findings

| Area | Classification | ShackCQ result |
|---|---|---|
| TCI and I/Q transport | ALREADY_IN_SHACKCQ | Existing v1/v2 owners retained. |
| Local NCO and two virtual receivers | CLEAN_ROOM_IMPLEMENT | Shared bounded C++ engine and one Android owner. |
| USB/LSB/CW/DIGU/DIGL/DSB | CLEAN_ROOM_IMPLEMENT | Complex FIR sideband selection with golden rejection vectors. |
| AM/SAM | CLEAN_ROOM_IMPLEMENT | Envelope AM plus PLL truth and fallback. |
| NFM/CTCSS/DCS | CLEAN_ROOM_IMPLEMENT | Receive-only discriminator and confidence-gated decoders. |
| WFM stereo/RDS | CLEAN_ROOM_IMPLEMENT | Bandwidth gate, pilot/stereo blend and CRC-valid group assembly. |
| Receiver recording | ENHANCE_EXISTING | Existing secure/app-private file authority and time-shift owner extended. |
| Scanner audio hit | ENHANCE_EXISTING | Existing scanner policy consumes explicit local audio. |
| Upstream UI/assets/test IQ | EXCLUDED | Nothing copied or packaged. |

No production repin was required because no newer stable release exists.
