# ADR: TCI and Multi-Receiver SDR Architecture

Status: Implemented; physical acceptance pending

## Context

ShackCQ’s Qt desktop already has one `DesktopApplication` composition root, one QML-facing `DesktopRadioController`, one `DesktopPanadapter`, a shared C++17 DSP core, Qt WebSockets, deterministic desktop tests, and cross-platform Qt packaging. TCI must add multi-receiver SDR behavior without adding a second radio authority or breaking Android/Apple callers.

The pinned SDRoxide reference demonstrates useful TCI protocol and multi-receiver behavior, but importing its Rust/egui/wgpu application graph would duplicate ShackCQ authorities and dependencies.

## Decision

Select Option A.

1. Add portable C++17 TCI wire/domain contracts to the shared core. They parse semicolon-delimited status, validate capability and receiver indices, build bounded RX mutations, and decode the 64-byte binary header plus interleaved little-endian `float32` payload.
2. Add a Qt WebSockets connection object as a private backend of `DesktopRadioController`. The controller remains the only QML-facing radio owner and exposes a receiver list plus active-control/listening receiver IDs.
3. Keep Hamlib and TCI mutually exclusive within the controller’s connection generation. A stale callback cannot mutate a newer connection.
4. Route I/Q by stable receiver ID to bounded receiver-indexed rings/contexts owned by `DesktopPanadapter`. Multiple views share one radio/connection authority.
5. Keep RF observations derived and non-canonical beneath existing spot/QSO/provider owners. Map/globe selection produces explicit review/handoff actions only.

## State and safety invariants

The TCI connection states are Disconnected, Connecting, Handshaking, Ready, Reconnecting, Stopping, and Error. Restored profiles are always inert and require explicit Connect; legacy auto-connect metadata is not dispatched by startup. Handshake completion requires protocol readiness and a bounded receiver capability snapshot.

Readback is authoritative. Frequency/mode writes are validated, coalesced per receiver, and complete only after matching status or a bounded failure. Writes are not replayed after an ambiguous disconnect. Unknown commands, formats, receivers, lengths, types, or non-finite samples fail closed and increment bounded diagnostics.

No production path sends transmit-on or tune-on. Global Stop cancels pending writes, stops/disarms streams, clears armed state, and sends at most one `trx:false`/`tune:false` request when the live connection can accept it. Stop remains responsive and idempotent.

## Binary contract

TCI binary frames use a 64-byte header interpreted as sixteen little-endian 32-bit words. The parser validates data type, receiver index, channel count, sample format, payload length, configured maximum, and `float32` finiteness before publication. I/Q and RX audio are server-to-client. TX audio/chrono frames are parsed only for truthful diagnostics and pacing state; they do not enable production transmission.

## Compatibility

Existing Hamlib single-receiver QML properties remain projections of the active-control receiver. Existing `shackcq_panadapter_*` ABI remains source-compatible. New portable C++ types are internal until a native client needs them; Android and Apple receive regression builds, not new UI.

## Rejected alternative

Option B, a Rust static library with C ABI, is rejected. It would add an FFI surface and multiplatform link/package work while Qt WebSockets and the C++ core already satisfy transport and domain needs. Implementing both is prohibited.

## Consequences

The desktop gains a deterministic fake-server seam and receiver-aware state without changing canonical owners. The main cost is careful lifecycle and model refactoring inside two existing controllers. Physical TCI dialect, device, audio, PTT/TUNE and RF acceptance remain separate checklists.
