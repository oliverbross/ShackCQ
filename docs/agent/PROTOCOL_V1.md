# ShackCQ Agent protocol v1

Protocol version is major 1, minor 0. Frames are UTF-8 JSON and limited to 64 KiB; binary frames are rejected.

1. Agent connects outbound to /api/v1/agent/connect with an Authorization bearer credential.
2. Agent sends agent.hello with Agent identity, build, platform, and up to eight capability-described radio devices.
3. Server responds with agent.accepted, the connection generation, heartbeat interval, and pending-command limit.
4. Agent publishes monotonic radio.snapshot frames containing actual observed frequency, mode, passband, connection state, and declared capabilities.
5. Server sends radio.command with a unique command ID, Agent/device scope, and expected generation.
6. Agent returns radio.command.result; success means the requested value was read back from Hamlib. It then publishes a fresh snapshot.

The v1 implemented allowlist is radio.set.frequency, radio.set.mode, radio.set.filter, and preset.recall. Unsupported receive-side setters fail closed. Explicitly prohibited actions include PTT, MOX, TUNE, radio power, memory write, raw CAT, TX audio, CW keying, voice/digital transmit, amplifier keying, and rotator movement.

There is no reconnect replay and no offline queue.
