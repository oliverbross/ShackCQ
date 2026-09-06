# Groups.io new-message alerts

The existing `GroupsIoController` and database remain authoritative. Foreground refresh records a pre-fetch per-topic baseline; a topic with no baseline is archive/backfill and cannot alert. Rows newer than a real baseline are deduplicated by group/message identity, suppressed for the currently viewed topic or muted group, and placed in a 20-item app-scoped queue.

One alert is shown at a time with group, sender, subject/excerpt and server time. Open navigates to the exact group/topic/message; Dismiss changes no server read state; Mute suppresses future alerts for that group without deleting content. The debug injection is compiled only for debug diagnostics and writes no database row.

New-message alerts are shown when ShackCQ refreshes Groups.io while active. No permanent background polling was added.
