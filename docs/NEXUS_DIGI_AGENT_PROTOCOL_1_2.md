# Nexus Digi Agent protocol 1.2

Protocol 1.2 is additive to the existing 1.1 Digi command and snapshot contract.
Older 1.x peers keep their existing Radio, Presets and Digi behavior.

The Agent now retains at most 12 local receive sessions and 64 MiB of mono
12 kHz PCM. The hosted service receives bounded session summaries and decode
metadata only. Raw audio and local paths never enter a command result or
snapshot. Re-decodes are marked `REFERENCE_RECORDING`, are not exact-timed and
cannot advance an exchange.

FT8 and FT4 expose Agent-local CQ-runner and search-and-pounce state machines.
Only an exact-timed `LIVE_CAPTURE` decode from the current local session can
select or advance an exchange. Standard GRID, REPORT, R-report, RR73/RRR and 73
transitions use finite retry and auto-CQ limits. `digi.sequence.stop`, lease
loss, mode/RX changes and the emergency Digi STOP cancel pending work locally.

Slotted transmission remains fail-closed. A sequence can prepare its next
message, but the Agent schedules it only while server permission, local
permission, hardware acceptance, fresh receive/PTT-off proof and 30 seconds of
stable wall/sample-clock evidence all hold. The production server policy remains
disabled until separately authorized physical-radio acceptance.

The history commands are `digi.history.redecode`, `digi.history.replay`,
`digi.history.export` and confirmed `digi.history.delete`. The sequence commands
are `digi.sequence.start` and `digi.sequence.stop`.
