# Android-to-Agent Digi parity ledger

| Android/operator behavior | Agent/Web result | Evidence boundary |
|---|---|---|
| FT8, FT4, FT2, FST4, Q65, MSK144, JT65, WSPR decode/encode | Existing Rust ABI linked into `ShackCQAgent`; local slot buffers and finite scheduling | Synthetic/reference/build evidence only; no authoritative clock source is wired |
| FT8/FT4 finite repeats | Maximum 1–10, precomputed waveform, local slots, arm expiry, no reconnect resume | Slotted arm fails closed pending measured UTC authority; no RF acceptance in this task |
| CW | Portable continuous receive and finite 20 WPM prepared audio | No general keyer/WinKeyer claim |
| RTTY | Portable continuous receive and finite prepared text | Existing reverse/reacquisition limits retained |
| PSK31 | Portable BPSK31 receive/encode at selected carrier | QPSK31 not implemented or advertised |
| SSTV | Streaming receive progress/completed bounded preview; bounded Martin/Scottie/Robot image preparation | Server holds no image archive; ISS workflow remains receive-only |
| Waterfall | 512 quantized bins, maximum 5 rows/s, row/session/UTC/frequency/scale metadata | Display telemetry, not raw audio or total bandwidth |
| Decode selection | Prepares context only; no implicit tune/TX/log | Companion remains separate provenance |
| Presets | Existing hosted 12-slot source retained; context links and shared interlock | No second preset store |
| Band Stack | Separate forced-RLS entries, named replace/record/cycle, Agent readback | Separate from RadioPreset and TX state |
| Receive scanner | Tenant preset resolution, explicit bounded local dwell/iterations, default off | No cloud cron, transmit, memory write, or reconnect restore |
| QSO draft | Explicit completed/user-authorized identity accepted at canonical hosted boundary | Auto-log off; provider confirmation separate |
| Local raw/replay WAV | Existing Android behavior remains local; Agent introduces no upload/archive | Reference audio is not live RF evidence |
| WSJT-X companion | Optional design remains loopback/non-authoritative | Cannot enable TX, identity, or logging |

Screen-level omissions are not labelled platform-inapplicable. Local Android gallery rename/pin/delete and raw recording controls remain local-device data-management features; the hosted browser exposes completed Agent preview and bounded image preparation without creating a cloud media library.

## Release blockers retained by the independent review

- No decode-driven FT8/FT4 automatic QSO sequencer is claimed; `autoSequenceModes` is empty.
- Android package workflows do not yet prove this Agent Digi implementation on every requested platform.
- Audio channel selection and release-quality resampling need real-device implementation and acceptance.
- The primary-plus-emergency Hamlib STOP/watchdog boundary has deterministic blocked-call preemption, queued-write rejection, late-result rejection, bounded retry, quarantine, and recovery-readback coverage; physical-radio timing and RX recovery remain owner-present acceptance evidence.
- Slotted modes have no measured UTC authority and therefore remain fail-closed.
