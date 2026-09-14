# Desktop Domain Schema Matrix

Desktop databases are app-private, independently migrated and never import Android private data or credentials.

| Store | Schema | Primary owned records | Safe restore/migration invariant |
|---|---:|---|---|
| Canonical QSO | 17 | QSOs, revisions, outbox/projections, advanced award/satellite/propagation/antenna/QSL fields | Additive transactional migration from populated schema 16; unknown imported propagation values and `extraAdif` survive; canonical mutation owner only |
| Neural DX | 5 | Evidence, calibration, opportunities | Station-scoped retention; empirical labels preserved |
| Digi | 2 | Sessions, decodes, SSTV metadata | Sessions restore stopped; no TX authority |
| Contest/SCP/N1MM | 2 | Sessions, staged QSOs, score, SCP manifest/callsigns, peer lifecycle and deduplicated packet ledger | Atomic SCP last-good promotion; N1MM inactive, loopback, untrusted and unarmed restore |
| Groups.io | 2 | Memberships, groups, topics, messages, drafts, outbox, delivery ledger, FTS | Alias only; no token; no automatic send/refresh |
| Engagement | 1 | Chaser attempts and local engagement state | No target lock or transmit authority restored |
| Portable/Operations/Satellite caches | 1 | Last-good catalogue/calendar/TLE projections | Explicit foreground refresh and bounded retention |

Each migration runs in a transaction, rejects a newer unknown schema and is covered by fresh/current/upgrade/reopen/corrupt-input tests. Backup/rollback evidence is recorded by the migration suite; no downgrade is attempted.

Schema 17 stores POTA, SOTA, WWFF, and IOTA references as normalized, unique comma-separated lists in their canonical columns. ADIF and Wavelog adapters use the same representation. TX power is nullable so “unset” remains distinct from an explicit `0 W`.

Wavelog CREATE maps the complete advanced field set. Wavelog PATCH maps the fields accepted by the provider and records each unsupported advanced field as `ACCEPTED_RETAINED`; the provider baseline contains only delivered fields, while retained values remain in the canonical QSO rather than a second authority. Invalid provider rows fail synchronization visibly instead of being skipped.
