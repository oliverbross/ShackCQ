# SDRoxide Remote Station Audit v6

Audit date: 2026-08-28. Audited immutable upstream: SDRoxide `v1.5.3`, commit `a680935b10f33768a499435e8bd37f779fa640ae`, GPL-3.0. The repository and release were reviewed for architecture and protocol ideas only.

| Surface | Upstream observation | ShackCQ decision |
|---|---|---|
| Server/native remote client | SDR-oriented network operation exists | Independent ShackCQ protocol and native clients |
| Radio roster and multiple sessions | Useful architectural precedent | Typed station roster; bounded to 8 authenticated sessions |
| Audio/spectrum/waterfall | Useful streaming precedent | Independent bounded binary framing; PCM16 and derived 8-bit spectrum in v1 |
| Remote TX lifecycle | Not accepted as ShackCQ evidence | Existing ShackCQ owner/interlock chain remains authoritative |
| TCI/rigctld | Protocol concepts reviewed separately | Independent parsers from official TCI and Hamlib documents |
| Reconnect/arbitration | General precedent only | Generation-bound sessions and exclusive short leases |

No SDRoxide code, UI, assets, screenshots, recordings, account system, or server shell were copied. `core/src/remote.cpp`, station service code, Android code, tests, and documentation are original GPL-3.0-only ShackCQ work.

Primary source: https://github.com/dividebysandwich/sdroxide/releases/tag/v1.5.3
