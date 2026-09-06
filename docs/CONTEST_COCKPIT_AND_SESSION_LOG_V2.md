# Contest cockpit and session log v2

`shackcq-contest.sqlite` schema 2 adds `contest_qso_entry`. Each row stores the event-time draft, exchange JSON, station/worked context, network origin/revision, dupe override and merge state. This is temporary Contest data, not the canonical log.

Logging, editing, deletion, score and export operate on staged rows. `Merge to Logbook` requires confirmation, maps each unmerged row through the existing canonical mutation coordinator, records the canonical ID/revision only after success, and leaves failures retryable. Repeating merge skips completed rows. Existing canonical Contest QSOs remain immutable from the temporary review surface.

N1MM safe-add handling stages the mapped draft and retains replay identity. It does not silently create a canonical QSO. Incoming radio/keyer/time/file/FREQMODE/FUNCTIONKEY/XMIT authority remains blocked.

On expanded tablets, QSO Entry owns the full-width operating strip and the read-only opportunity lists are separated from score, multipliers, recent entries, keyer and network truth. Setup pairs related category controls instead of stretching each across the screen. An empty Review page explains the staged-log workflow and routes directly back to Logging rather than presenting an unbounded empty panel.

SCP discovery is `https://www.supercheckpartial.com/api/v1/files`; the selected `SCP.DB` is app-private, conditional, bounded to 16 MiB, validated as SQLite with required tables/columns and replaced atomically. No SCP content is uploaded or packaged.

Sweep 3 moves versioned global defaults/policies to Settings while session definition, edition, identity, exchange, serial and controls remain in Contest. Logging has one authoritative status row. SCP rows render only the callsign with exact matched character positions highlighted. Contest spots and multi-band opportunities consume the existing cluster repository and Band Map snapshot; they do not create a transport/controller.
