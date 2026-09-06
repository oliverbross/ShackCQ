# SDRoxide Feature Crosswalk

Pinned reference: `dividebysandwich/sdroxide` commit `312b27ec4303d3fe0ef3a70d3aa99ff615e460a4`, tree `09b6e8ed95bcf586b59f00f1c7c4d4ef8173bd11`.

| SDRoxide concept | Classification | ShackCQ decision |
|---|---|---|
| TCI text command builders and status parsing | REIMPLEMENT | Portable C++ parser with deterministic fragmented-burst tests |
| WebSocket connection/handshake/ready lifecycle | REIMPLEMENT | Qt WebSockets transport beneath `DesktopRadioController`; bounded timeout/reconnect |
| 64-byte little-endian binary header | REIMPLEMENT | Validate sixteen `u32` fields, type, receiver, channels, length and payload alignment |
| Interleaved little-endian `float32` I/Q | REIMPLEMENT | Direct bounded per-receiver feed into existing shared Panadapter DSP |
| RX audio stream | REIMPLEMENT | Capability/state plumbing with explicit route; no microphone fallback |
| TX audio and transmit control | DEFER | No production TX-on command; only safe one-shot Stop de-key/tune-off |
| Multiple TCI receivers | ADAPT | Receiver-indexed state/routing semantics adapted to ShackCQ’s singular controller |
| Radio/logbook/settings application shell | REJECT_DUPLICATE_AUTHORITY | Existing ShackCQ owners remain canonical |
| Existing shared Panadapter FFT/window/averaging/peak hold | ALREADY_STRONGER_IN_SHACKCQ | Preserve and extend existing `shackcq_panadapter_context` behavior |
| Waterfall history and GPU texture approach | REIMPLEMENT | Public Qt Quick scene graph, bounded ring of rows, no private Qt API |
| Waterfall color lookup tables | REIMPLEMENT | Flightline palettes generated from ShackCQ-owned stops; no upstream table copied |
| FIT/auto-contrast | REIMPLEMENT | Robust bounded histogram/percentile fit with attack/release and manual override |
| Band-plan rendering | ADAPT | Use ShackCQ’s existing JSON plan and Qt overlay, not SDRoxide band data |
| Spectrum/passband/frequency interaction | ADAPT | Receiver-aware ShackCQ gestures with explicit QSY and controller readback |
| Great-circle geometry | REIMPLEMENT | Standard spherical formulas with antimeridian segmentation tests |
| Propagation control points | REIMPLEMENT | Derived evidence model; never fabricated prediction or exact-location claim |
| Flat map | REIMPLEMENT | One Qt-native Flightline map surface, no WebView or commercial-key requirement |
| 3D globe | REIMPLEMENT | Public Qt Quick 3D/scene graph implementation with rotate/zoom/coastlines/paths |
| Source/time/band/mode filtering | ADAPT | One RF observation filter model fed by canonical ShackCQ sources |
| SDRoxide egui/wgpu shell, shaders, assets and theme | OUT_OF_SCOPE | Not imported |
| SDRoxide providers, awards, logbook and operating workflow | REJECT_DUPLICATE_AUTHORITY | Existing ShackCQ authorities remain singular |
| Unrelated SDRoxide modes, voice models and server runtime | OUT_OF_SCOPE | Not built, linked, packaged, or represented |

## Completion status

All selected REIMPLEMENT and ADAPT rows are implemented in the existing ShackCQ owners and covered by deterministic desktop tests. The TCI client, receiver roles, direct-float I/Q path, bounded Qt Quick spectrum/waterfall, RF observation model, flat map, orthographic globe, filters, selection, and action-free handoffs are production-wired. OUT_OF_SCOPE and REJECT_DUPLICATE_AUTHORITY rows remain excluded. Hosted builds, packaging, and gallery runs are recorded separately at the final exact SHA; physical radio, audio, TX, and RF acceptance remain pending.
