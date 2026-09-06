# N1MM protocol coverage

Reference: `s53zo/n1mm-network-protocol@2decc5adbdffedf1138fd4b75c65f811f6a21064` (MIT, unofficial).

## Multi-user commands

All commands have a typed enum, field schema, bounded parse and encode path. Runtime status is policy-specific.

| Commands | Coverage |
|---|---|
| `ECHO`, `ECHOREQ`, `IAM`, `STOPIAM`, `MASTER`, `CONTESTNAME`, `STATUS`, `FREQ`, `CQFREQ`, `PASSFREQ`, `REQCONTESTNAME`, `REQCQFREQ`, `REQPASSFREQ`, `WHOAREU`, `LASTQAT`, `DISCONNECT_ME` | `FULL_RUNTIME` for bounded station state/heartbeat/lifecycle; frequency is descriptive only |
| `ADDBLACKLISTCALL`, `ADDSPOT`, `DELETESPOT`, `REMOVECALLSTACKCALLSIGN`, `STACKANOTHERCALL`, `SKED`, `SKEDD`, `SKEDSYNC`, `TALK` | `MONITOR_RUNTIME` bounded network state |
| `QSO`, `REEDITQSO`, `RESYNCQSO`, `QSODELETE`, `DELETEQS`, `CHECKSUM`, `CONFIRMED` | `TRUSTED_REVIEW_RUNTIME`; only separately trusted unambiguous `QSO` add can be `FULL_RUNTIME` through the canonical coordinator |
| `QSONRS`, `RESERVENR`, `REJECTNR`, `RESETQSONRS` | `TRUSTED_REVIEW_RUNTIME` against the matching session serial authority |
| `FREQMODE`, `FUNCTIONKEY`, `XMIT`, `CLOSEPORT`, `PACKETSTRING`, `TIME` | `CODEC_ONLY` + `BLOCKED_BY_SAFETY`; no CAT/TX/keyer/clock action |
| `FILE`, `PACKET` | `CODEC_ONLY` + `BLOCKED_BY_SAFETY`; metadata only, never written/executed |
| `STACKCALL` | `CODEC_ONLY`; 43-field contact body parses, no call-stack owner in this branch |

The 43-field contact body preserves exact field order, blanks and unknown trailing fields. Numeric/boolean/ID fields reject malformed coercions.

## XML roots

| Roots | Coverage |
|---|---|
| `RadioInfo`, `AppInfo`, `contactinfo`, `contactreplace`, `contactdelete`, `lookupinfo`, `scoreinfo`/`score`, `spot` | `CODEC_ONLY`; outbound destinations default to loopback and streams are disabled |
| `CWControlString`, `CWSendStr`, `SetBufPTT`, `SendCW`, `SetWPM`, `Tune`, `TuneStop`, `Reset`, `PortOpen`, `RTSEnable`, `DTREnable`, `WinkeyPutChar` | `CODEC_ONLY` · runtime blocked pending keyer integration |
| `RoverQTH`, `radio_setfrequency`, `RadioCmd`, `RCmd`, `Spectrum` | `CODEC_ONLY` · `BLOCKED_BY_SAFETY`/integration deferred |

All XML uses a DTD/entity-disabled parser and a 64 KiB datagram bound.

## Ports and ownership

| Port/surface | Coverage |
|---|---|
| TCP+UDP `12070` multi-user/discovery | `FULL_RUNTIME`, explicit start, loopback default, LAN opt-in |
| UDP `12060` general XML, UDP `12050` score XML | `CODEC_ONLY`; loopback defaults, outbound disabled |
| UDP `12080` CW/serial/control | `CODEC_ONLY`; runtime blocked pending keyer integration |
| UDP `13064` radio/spectrum/control | `CODEC_ONLY`; no second radio/panadapter owner |
| UDP `12040` rotor command, UDP `13010` rotor status | `NOT_IMPLEMENTED`; rotator ownership deferred |
| UDP `13065` SDR server and raw CAT TCP | `BLOCKED_BY_SAFETY`; no second CAT/radio transport |
| WSJT/JTDX `2237`, `2239`, `2240`, `2241`, `52001`, `52002`, `52004`, `52006`, `61002`, `61004` | `EXISTING_SHACKCQ_OWNER` — Digi/WSJT interop; no listener here |

Receiver-supported commands with no known current sender path remain truthfully covered only to the level above; no undocumented full-support claim is made.
