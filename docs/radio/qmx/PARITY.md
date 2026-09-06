# QMX/QMX+ parity ledger

Statuses are `NATIVE_QMX`, `DELEGATED_TO_SHACKCQ`, `PLATFORM_NOT_APPLICABLE`, `EXCLUDED`, and `MISSING`. Unknown hardware evidence remains unknown at runtime even when a contract exists.

| Capability | Status | Result/boundary |
|---|---|---|
| USB composite discovery | NATIVE_QMX | Exact descriptor model and stable hashed device identity. |
| CDC CAT | NATIVE_QMX | Primary interface 0 contract; one serialized transaction owner. |
| UAC stereo I/Q | NATIVE_QMX | Exact-device 48 kHz stereo route; no microphone fallback. |
| Firmware/version detection | NATIVE_QMX | Strict `VN` parsing and numeric comparison. |
| Frequency | NATIVE_QMX | `FA`/`FB`, exact 11-digit bounds. |
| Mode | NATIVE_QMX | QMX digits 1–9; gated AM/Tune. |
| Filter/passband | NATIVE_QMX | `FW` read/write and QMX passband contract. |
| Band handling | NATIVE_QMX | HF bounds and native band presentation. |
| AF gain | NATIVE_QMX | `AG0nnn`, 0.25 dB native steps, readback-gated. |
| RF gain | NATIVE_QMX | `RGnnn`, plain dB, readback-gated. |
| RIT | NATIVE_QMX | Always `RC` before `RU`/`RD`, then explicit `RT`. |
| CW offset | NATIVE_QMX | `MMCW|CW offset;`, bounded 500–1000 Hz readback. |
| Split/XIT workaround | NATIVE_QMX | QMX has no XIT; typed VFO-B plus split plan. |
| Power | NATIVE_QMX | `PC`, tenths of a watt. |
| SWR | NATIVE_QMX | `SW`, hundredths; bare receive value remains unavailable. |
| S-meter | NATIVE_QMX | Bounded `SM` response model. |
| Preamp/attenuator | EXCLUDED | No reliable reviewed QMX command/capability proof; remains absent. |
| IQ mode confirmation | NATIVE_QMX | Q9 write echo is ignored; explicit delayed query must prove ON. |
| VOX safety | NATIVE_QMX | Q3 disable plus readback must prove disabled. |
| GPS source | NATIVE_QMX | Exact MM path; only `QMX+ Internal` becomes internal GPS. |
| Extra CDC/menu terminal | NATIVE_QMX | Extra interface required; no write before explicit operator open. |
| QMX/QMX+ differences | NATIVE_QMX | Product evidence only; otherwise `UNKNOWN_QMX`. |
| SWR protection/fault | NATIVE_QMX | Sticky operator-cleared fault and fail-closed outcome. |
| Antenna tune | NATIVE_QMX | Firmware/readback-gated typed confirmation request; central safety executes later. |
| Panadapter | NATIVE_QMX | QMX adapter contract, IF axis, mode direction and passband. |
| Waterfall | DELEGATED_TO_SHACKCQ | Central Panadapter renders and owns history. |
| I/Q correction | NATIVE_QMX | Bounded adaptive DC/amplitude/quadrature correction. |
| Flat-spectrum mode | DELEGATED_TO_SHACKCQ | QMX setting contract; central DSP applies it. |
| Zoom/pan/tap-to-tune | NATIVE_QMX | QMX frequency mapping contract; central surface owns gesture state. |
| Band-plan strip | DELEGATED_TO_SHACKCQ | Existing Band Map/Panadapter authority. |
| Digi FT8/FT4 | DELEGATED_TO_SHACKCQ | Existing decoder/exchange engine remains authoritative. |
| Direct CAT-tone transmit | NATIVE_QMX | Optional immutable-plan backend; no sequencing or callsign selection. |
| QSO logging | DELEGATED_TO_SHACKCQ | Existing canonical mutation path. |
| Wavelog delivery | DELEGATED_TO_SHACKCQ | Existing Wavelog controller/outbox. |
| Presets/memories | DELEGATED_TO_SHACKCQ | Safe adapter fields only; no second authority. |
| Diagnostics | NATIVE_QMX | Sanitized identity-free health metadata. |
| Configuration | NATIVE_QMX | Package-local safe settings; no runtime TX/arm/session state. |
| Web UI | PLATFORM_NOT_APPLICABLE | ESP32 web application is not ported. |
| OTA | PLATFORM_NOT_APPLICABLE | Radio/app firmware update is outside this Android core. |
| Wi-Fi | PLATFORM_NOT_APPLICABLE | ShackCQ owns networking; QMX core adds none. |
| microSD | PLATFORM_NOT_APPLICABLE | No Android QMX microSD authority. |

Counts: **29 NATIVE_QMX**, **7 DELEGATED_TO_SHACKCQ**, **4 PLATFORM_NOT_APPLICABLE**, **1 EXCLUDED**, **0 MISSING** (41 total).
