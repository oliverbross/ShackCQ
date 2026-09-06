# ShackCQ Remote Protocol v1

Control transport is WSS with subprotocol `shackcq.remote.v1`. Each JSON request carries `version`, `type`, `requestId`, and—after authentication—`stationId`, `sessionId`, `generation`, `timestampMs`, and a bounded `payload` object.

Lifecycle: server `HELLO`; client signed `AUTH`; server `ACK/AUTHENTICATED`; periodic `HEARTBEAT`; optional `LEASE`; typed `MUTATE`; projected `STATE`; binary media. Pairing uses `PAIR_REQUEST` and always requires local approval. Unknown versions/messages, oversized input, invalid signatures, stale generations, missing sessions, and denied leases fail closed.

Channels: STATE, SPOTS, HEALTH, AUDIO_RX, AUDIO_TX, SPECTRUM, WATERFALL, optional IQ, DIGI, KEYER, VOICE, ROTATOR, optional LOG_EVENT. v1 implements state, RX audio, derived spectrum, and guarded TX-audio rejection. Unsupported channels remain unavailable—not silently emulated.

Limits: 8 sessions, 64 KiB control, 256 KiB media. Heartbeat 5 seconds; station session expiry 15 seconds. Writer/TX/rotator lease TTL is 1–30 seconds.
