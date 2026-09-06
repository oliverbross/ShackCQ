# TCI Client

## Ownership and scope

DesktopRadioController remains ShackCQ's sole desktop radio authority. Its private TciClient supplies a receive-only Qt WebSockets backend; it does not create a second settings, radio, logbook, provider, or transmit owner.

Profiles contain a stable ID, display name, ws:// or normally validated wss:// endpoint, preferred receiver and I/Q sample rate, optional RX-audio route, and an auto-connect preference. Auto-connect defaults off. Profiles are bounded to 32 and restore is rejected when a future schema or excessive profile list is found.

## Protocol lifecycle

The client implements bounded Connect, WebSocket upgrade, TCI status handshake, start/capability/ready gating, receiver discovery, I/Q and RX-audio attachment, disconnect, and three-attempt exponential reconnect. Connection and ready timeouts fail closed. A reconnect adopts fresh server state and does not replay ambiguous frequency or mode mutations.

Status frames may be fragmented across WebSocket deliveries or contain multiple semicolon-delimited commands. Unknown commands are counted. Malformed fields, receiver indices, binary headers, lengths, data types, non-finite samples, or unsupported profiles are rejected and surfaced as sanitized diagnostics.

The binary contract is a 64-byte little-endian header followed by interleaved little-endian float32 values. Incoming WebSocket messages are capped at 8 MiB. Decode runs on a dedicated single-thread pool with an eight-frame queue; overload is dropped and counted. Tests assert decode occurred off the controller/UI thread.

## Mutation and Stop safety

Frequency and mode requests are validated and coalesced per receiver. A status readback clears the matching pending mutation. No production builder or controller path emits trx:true or tune:true.

Global Stop clears pending mutations, detaches attached I/Q streams, and emits at most one trx:false;tune:false request for each current connection generation. Repeated Stop is idempotent. Physical radio, RX-audio routing, PTT, TUNE, TX audio, and RF acceptance remain pending.

## Deterministic evidence

desktop_tci_contract_tests uses a raw localhost WebSocket fixture for delayed upgrade, fragmented/multi-command handshake, one/two receivers, float32 I/Q and RX audio, attach/detach, reconnect, coalescing, malformed frames, ready timeout, and one-shot safe Stop. Gallery mode uses a separate localhost-only fixture in an isolated demo root; it is visual fake-server evidence, not physical-radio proof.
