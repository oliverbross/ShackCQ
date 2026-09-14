# Nexus-native desktop security and lifecycle

The Tauri shell bundles trusted assets and explicitly enables only
`desktop-main`. Its CSP denies remote frames, objects, forms and arbitrary
connections. It contains no shell, opener, updater, filesystem or generic IPC
plugin. Each frontend action maps to a named typed command.

The shell launches one fixed bundled `shackcq-nexus-runtime` executable using
Rust Command, cleared environment, private anonymous pipes and a random
per-launch nonce. Frames are versioned, generation
bound, identifier/size bounded and nonce authenticated. STOP bypasses stale
generation rejection. The shell also holds a separate atomic child PID: emergency
STOP terminates capture without waiting for the normal IPC mutex or decoder and
reports RX_UNCONFIRMED.

Startup is inert. Device selection persists only preferences; no default input
fallback is allowed. `Start receiving` is the only input-open transition.
Output selection is inert and no output stream, PTT, TUNE or RF command exists.
On window/process shutdown the shell sends local STOP, terminates the child and
waits for it. Armed state and executable waveforms are never persisted.

Reviewed contacts are account/station/authority partitioned and encrypted with
XChaCha20-Poly1305. The key is held in the OS credential vault. Writes use a
private atomic replacement; count/byte limits stop admission rather than delete
undelivered contacts. A queue row is removed only for an explicit durable
canonical receipt. Fixture provenance remains marked and must be rejected by a
production ingress adapter.

The optional browser-local listener defaults disabled and does not bind on
startup. A user action in the trusted desktop shell invokes
`digi_set_browser_local_enabled`; enabling binds IPv4 127.0.0.1:17654 and shows
a one-use 32-character lowercase hexadecimal pairing code. IPv6 is unimplemented
and not claimed. A bind collision degrades that bridge to UNAVAILABLE without
preventing offline desktop startup. Every capability route remains closed until
the exact https://shackcq.com origin supplies that code. The code is validated
in constant time and consumed exactly once. The resulting in-memory capability
is random, compared in constant time, expires within five minutes, is explicitly
revocable, is never a query parameter, and is not persisted. Disable, session
close, and process restart revoke the capability; restart returns to disabled.
Host, Origin, content type, contract, request/response size and exact CORS/PNA
preflight are checked; there is no wildcard origin. A dedicated accept/STOP
dispatcher bypasses the bounded normal worker queue so blocked approval or
decode handlers cannot prevent emergency STOP. Browser-local availability never
changes the production TX lock.
