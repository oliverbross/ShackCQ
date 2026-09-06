# N1MM network Android v1

## Sweep 1 presentation

The Contest Network tab now presents enabled/armed state, loopback/LAN policy, bind identity, peers, exact trust, last-seen and bounded diagnostic counters, sanitized errors, and start/stop/arm/disarm controls. It remains off and unarmed by default, never restores armed, and continues to reject incoming radio, keyer, time and file commands. The UI does not expose raw XML or create a second network controller.

Claim: **Protocol coverage against s53zo/n1mm-network-protocol at `2decc5adbdffedf1138fd4b75c65f811f6a21064`.** This is not certified full N1MM+ interoperability.

## Architecture and lifecycle

Codecs, stream parsing, transport, policy, QSO bridge and diagnostics are separate. UDP discovery and TCP links use port 12070, exact trailing-percent advertisements and `DATA__NN%...%~__DATA` frames. The parser tolerates split/concatenated frames, leading noise and six-NUL payloads; it replaces malformed UTF-8 while marking the frame, limits a frame to 64 KiB and retained stream data to 256 KiB.

The feature defaults to `OFF`. `start()` requires explicit enable, a non-off mode, and loopback unless LAN opt-in is true. Restore never calls `start()`. Shutdown and network-change cleanup are idempotent. Master announcement, LAN broadcast, external XML output and trusted mutation default off. There is no cloud relay, NAT traversal, account or protocol credential.

Discovery records both advertised and packet-source addresses. A mismatch is surfaced and the packet source wins. Trust additionally requires configured station/operator/interface/subnet, optional pinned address and matching contest/rule; a broadcast alone is insufficient.

## Policy

- Safe station/visibility commands update bounded snapshots only.
- QSO mutation commands are monitor-only by default. Trusted review can propose changes; only a trusted, matching, unambiguous new `QSO` may auto-accept in the separately enabled safe-add mode.
- Edits, deletes and checksum repair always require review in v1.
- Serial commands apply only through the active matching contest’s serial authority and expose conflicts.
- `FREQMODE`, `FUNCTIONKEY`, `XMIT`, `CLOSEPORT`, `PACKETSTRING`, control XML and radio XML are parsed but blocked from side effects.
- `TIME` never changes system time. `FILE`/`PACKET` never writes or executes content.

Accepted remote adds map to a canonical contest QSO, call `QsoMutationCoordinator` through the contest repository, then commit the remote link. Origin/revision metadata prevents rebroadcast loops without suppressing genuinely distinct station identities. No ambiguous mutation is retried blindly.

## Bounds, privacy and collision ownership

Defaults: 32 peers/links, 600 frames/minute/peer, 4,096 characters/field, 128 fields, 1 KiB discovery, 64 KiB XML, one-hour dedupe retention, 60-minute resync window, 500 sanitized events and bounded exponential reconnect up to 60 seconds.

Diagnostics retain command/category/safe reason and a truncated peer hash. They contain no raw QSO, callsign, comment/exchange body, packet dump or IP address. XML DTDs/external entities are rejected.

ShackCQ does not bind N1MM-adjacent WSJT/JTDX ports because Digi owns them. Port 12080 control/CW, 13064 radio/spectrum, 12040/13010 rotor, 13065 SDR and raw CAT remain codec-only/existing-owner/blocked as shown in the coverage matrix.

This is a **MULTI-OP NETWORK FOUNDATION**: peer/status/talk/claim/score/rate/QSO/serial groundwork, not proven Multi-Multi orchestration, hardware interlock or band-change-rule enforcement.

## Automated protocol evidence — 2026-08-22

The final 459-test JVM pass includes framing, split/concatenated streams, malformed UTF-8, six-NUL fields, all typed command schemas, contact round-trip, XML hardening, policy, safe-add bridge behavior, dedupe/rate bounds, 10,000-QSO rebuild, loopback TCP/UDP, strict interface/subnet/contest/rule trust and link cleanup. Four contest/N1MM instrumentation cases compiled into the instrumentation APK but were not executed because no Android device was attached. No live N1MM+ station or multi-computer LAN acceptance is claimed; follow `N1MM_LIVE_ACCEPTANCE.md` for that evidence layer.

## Semantic integration

The application now owns exactly one `N1mmNetworkController` scoped to the active Contest runtime. It remains disabled, loopback-only, unarmed and non-restoring by default; entering Contest does not start networking. Background, context loss and the integrated Stop action close it. Health and future Band Maps receive bounded sanitized/read-only state only. Live N1MM+ and LAN interoperability remain pending physical acceptance.
