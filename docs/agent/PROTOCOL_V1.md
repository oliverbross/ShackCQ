# ShackCQ Agent protocol v1

Protocol version is major 1, minor 2. Frames are UTF-8 JSON and limited to 64 KiB; binary frames are rejected. Minor 0 Agents retain Radio/Presets behavior, minor 1 adds Digi, and minor 2 adds the optional receive-only logger capability.

1. Agent connects outbound to /api/v1/agent/connect with an Authorization bearer credential.
2. Agent sends agent.hello with Agent identity, build, platform, and up to eight capability-described radio devices.
3. Server responds with agent.accepted, the connection generation, heartbeat interval, and pending-command limit.
4. Agent publishes monotonic radio.snapshot frames containing actual observed frequency, mode, passband, connection state, and declared capabilities.
5. Server sends radio.command with a unique command ID, Agent/device scope, and expected generation.
6. Agent returns radio.command.result; success means the requested value was read back from Hamlib. It then publishes a fresh snapshot.

The v1 implemented allowlist is radio.set.frequency, radio.set.mode, radio.set.filter, and preset.recall. Unsupported receive-side setters fail closed. Explicitly prohibited actions include PTT, MOX, TUNE, radio power, memory write, raw CAT, TX audio, CW keying, voice/digital transmit, amplifier keying, and rotator movement.

Radio commands still have no offline queue. Logger minor 2 separately journals accepted local completed-contact packets before sending them. The event body is stored in the operating-system credential vault; its mode-0600 disk journal contains identifiers, timestamps, aliases and byte counts only. A server receipt removes the vault entry, while reconnect/restart replays the same event identity.

`agent.hello.loggerSources` advertises `WSJTX` and/or `N1MM`. The server may send a generation-scoped `logger.profile.apply`; it contains only a reviewed loopback port and capture-time account/station/authority revisions. The Agent returns `logger.profile.result` for device `logger`. It publishes up to 32 events in `logger.event.batch`, and the server returns per-event `logger.event.receipt` dispositions.

Logger profiles bind only `127.0.0.1`, never open CAT/serial hardware, never send a logger command and never acquire the radio owner. A maximum 64 KiB UDP datagram, 5,000 pending records, 32 MiB encrypted payload total and 30-day local age bound apply. Queue exhaustion stops admission with a visible safe error instead of silently discarding an accepted record.
