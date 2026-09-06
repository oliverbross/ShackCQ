# TCI TX Protocol Matrix v5

| Surface | Classification | Android v5 handling |
|---|---|---|
| `trx` RX/TX state | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | `trx:true,tci` only through authority; authoritative readback required; false on every stop path |
| Tune | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | separate acceptance, capped tune drive, finite watchdog, RX recovery |
| TX enable permission | `SUPPORTED_READBACK_ONLY` | parsed as server permission; never treated as operator acceptance |
| TX frequency | `SUPPORTED_READBACK_ONLY` | represented separately from RX; never guessed |
| VFO A/B and split | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | separate truth; split changes blocked during TX by default |
| XIT enable/offset | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | bounded command builders and readback; distinct from RX frequency |
| TX filter | `UNAVAILABLE_PROTOCOL` | null/labelled unavailable; RX filter is not relabelled TX filter |
| Drive | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | bounded by profile maximum and read back |
| Tune drive | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | capped at 25%, conservative default 10% |
| Forward RMS power | `SUPPORTED_READBACK_ONLY` | official `tx_sensors` field |
| Peak power | `SUPPORTED_READBACK_ONLY` | official `tx_sensors` field; not called reflected power |
| Reflected power | `UNAVAILABLE_PROTOCOL` | null; no derivation from SWR |
| SWR | `SUPPORTED_READBACK_ONLY` | interlock threshold |
| ALC | `UNAVAILABLE_PROTOCOL` | null on baseline; abort applies only to audited dialect data |
| TX audio | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | 64-byte header, FLOAT32, stereo, exact bounded length, audited rates |
| TX chrono | `SUPPORTED_VERIFIED` | validates even value count 1..16384 and paces exact response |
| Monitor enable | `SUPPORTED_WRITE_WITH_ACCEPTANCE` | explicit profile setting/readback; local monitor remains labelled TX AUDIO |
| Mute/RX audio | `SUPPORTED_VERIFIED` | existing receive path; not reused as TX authority |
| Reconnect during TX | `EXCLUDED` | never restores/replays TX; forces stop and fresh RX-safe preflight |
| Native CW keyer | `DIALECT_SPECIFIC` | excluded from baseline; reviewed audio-keyed CW is used |
| Per-mode TX level | `SUPPORTED_VERIFIED` | ShackCQ-owned bounded map, applied exactly once before limiter |
