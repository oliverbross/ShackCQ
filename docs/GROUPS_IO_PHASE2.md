# Groups.io Phase 2: Complete Messaging and Offline Archives

## Scope and evidence boundary

Phase 2 completes ShackCQ's member-facing Groups.io workflow: cached reading, local and online search, new topics, replies, local drafts, an explicitly authorised foreground outbox, server-draft reconciliation, incoming and outgoing attachments, complete selected-group mirroring, and manual official archive export. Administration, subscriptions, moderator queues, chat, calendars, wikis, albums, polls, background polling, multiple accounts, desktop UI and AI summarisation remain out of scope.

All automated coverage uses invented fixtures and fake transports. No API key, private group data, real draft, post, attachment or archive was used. Apple/iPad parity is source-only and uncompiled by explicit owner instruction.

## Official API contract

Reference inspected: <https://groups.io/api>, marked **Revised Jul 31, 2026**, inspected 2026-08-20. Base URL: `https://groups.io/api/v1`.

Every request uses `Authorization: Bearer <API_KEY>`. ShackCQ does not call `/login`, use cookies or Basic authentication, place the key in a query parameter, or send `csrf`. GET arguments use the query string. Writes use `application/x-www-form-urlencoded`, except `/uploadattachments`, which is streamed multipart with `draft_id`, `fileupload` and `inline=false`.

Retained reads: `/groups`, `/gettopics`, `/gettopic`, `/getmessages`. Phase 2 adds `/getperms`, `/getsinglefeed`, `/getmessage`, `/searcharchives`, `/getdrafts`, `/getattachments`, and `/downloadarchives`. Writes are `/newdraft`, `/updatedraft`, `/uploadattachments`, `/deleteattachment`, `/deletedraft`, and `/postdraft`.

The current reference shows `group_id` for `gettopics`; no speculative fallback is active. IDs and `page_token`/`next_page_token` are opaque. Bounded reads use 50 rows, while explicit complete-archive pages use up to 100. Idempotent GETs may receive at most two bounded retries for rate-limit or temporary failures. Writes are reconciled before retry; `/postdraft` and `/downloadarchives` are never automatically retried.

## Permissions

Membership responses seed cached `can_post`, `can_reply` and `download_archives` values. Opening a connected group shows cached content first, then refreshes `/getperms` and the limited member/group fields from `/getsinglefeed`: post status, maximum attachment size and default reply policy. Cached capabilities remain available after partial failure and carry a stale timestamp.

Read-only groups hide posting actions. `can_post` gates New Topic; `can_reply` plus the topic's closed state gate Reply. `download_archives` gates official ZIP export. Server responses remain authoritative.

## Composer, drafts and posting

The native Compose and SwiftUI composers edit plain text. Minimal HTML escapes ampersands, angle brackets and quotes, preserves paragraphs/line breaks, and wraps optional quoted text in `<blockquote>`; raw HTML, scripts, styles and remote images are unavailable.

Typing autosaves locally after roughly 600 ms and never contacts Groups.io. Drafts use random local IDs and survive restart, disable and disconnect. Closing saves safely. The final confirmation names the group, draft kind, reply destination and subject, and distinguishes Send Now from Send When Online.

New topics use `/newdraft` with `draft_type_post`, `/updatedraft`, sequential `/uploadattachments`, then `/postdraft`. Replies use `draft_type_reply` and an authoritative message ID; `/getmessage` fills a missing ID. Reply labels map to the supported group default, `sender`, `group_and_sender` and `mods` values without exposing raw enums.

The outbox states are `draft_local`, `queued`, `creating_remote`, `updating_remote`, `uploading`, `ready_to_post`, `posting`, `posted`, `pending_moderation`, `failed_retryable`, `needs_attention`, and `delivery_unknown`. One mutex-protected item posts at a time. Queueing and processing are explicit foreground actions only; app launch, connectivity changes, autosave, view disappearance and attachment completion never send.

`extra == "pending post"` becomes a successful pending-moderation state. A timeout or connection loss around `/postdraft` becomes `delivery_unknown`; it disables automatic sending and requires deliberate reconciliation against recent messages, `/getdrafts`, and the retained remote draft ID. This prevents duplicate posts.

Errors are mapped without displaying private bodies, including invalid draft, missing subject/body, hashtag restrictions, size/storage limits, announcement groups, bad attachments, subscription/reply errors, permissions, credentials, rate limiting, temporary failures and compatibility changes.

## Server drafts

Drafts & Outbox groups local drafts, queued/sending items, needs-attention/delivery-unknown items, server drafts and recently submitted items. `/getdrafts` and `/getattachments` are bounded and fetched only on explicit refresh/open. Local edits are never overwritten. Import/open and `/deletedraft` remain explicit; deletion copy distinguishes local-only from local-and-server effects.

## Attachments

Android uses `OpenMultipleDocuments`; Apple uses `fileImporter` with correct security-scoped access. Selection immediately streams a copy into `GroupsIO/outbox/<draft-id>/`, sanitises traversal/separators/control characters, avoids overwrites, records media type and size, and calculates SHA-256. The client ceiling is 100 MiB per attachment and is not a Groups.io policy claim.

Uploads stream sequentially through `/uploadattachments`. Successful remote IDs and hashes prevent duplicate uploads. Removing an uploaded item uses `/deleteattachment` and response confirmation.

Incoming attachment metadata is refreshed through `/getmessage`; signed URLs remain transient and are never persisted. Downloads require explicit action, HTTPS, a fresh attachment-ID match, streamed temporary files, size enforcement, SHA-256 and atomic completion under `GroupsIO/attachments/<group>/<message>/`. Cancellation/failure removes partial data while retaining metadata. Android shares through the existing FileProvider `content://` authority; Apple uses native preview/share. No `file://`, app-private path or unrestricted HTML is exposed.

## Search and complete archive

Downloaded search remains SQLite FTS5 and works offline across cached rows. Groups.io search requires one selected group and uses bounded `/searcharchives` pages with `q`, relevance ordering and signature exclusion. It never fans out across memberships. Opening a result fetches `/getmessage`, caches the authoritative message/identity/attachments and opens the native thread.

Complete Offline Archive is a manual per-group `/getmessages` traversal with pages up to 100. Each page is transactionally upserted with its FTS rows and deduplicated by `(group_id,message_number)`. Progress and opaque cursor state allow pause/resume; cancellation preserves completed pages. Only authoritative `has_more=false` marks completion. An invalid stored cursor restarts safely while preserving/deduplicating existing rows. Normal newest-message sync never implicitly re-walks the archive.

Official ZIP/MBOX export is permission-gated and manual. UI warns that Groups.io permits one request per person/group per 24 hours. `/downloadarchives` streams to a feature-owned temporary file, hashes it, moves it atomically under `GroupsIO/archive-exports/<group>/<timestamp>-archive.zip`, and exposes native share/export. Failure preserves older exports; there is no automatic retry and ShackCQ does not parse MBOX.

## Schema version 2 and isolation

Only `shackcq-groupsio.sqlite` moves from v1 to v2. Android's `SQLiteOpenHelper` performs a transactional `onUpgrade`; Apple reads `PRAGMA user_version`, creates v2 at version 0, transactionally migrates v1, opens v2, rejects newer versions and never downgrades.

The migration preserves groups, topics, messages, FTS and sync state. It adds capability columns to `groups`; reply/quote/attachment-sync columns to `messages`; and `message_attachments`, `local_drafts`, `draft_attachments`, `server_drafts`, and `archive_exports` with query-driven indexes. Credentials and signed URLs never enter SQLite.

`QsoDatabase.kt`, `QsoDatabaseInstrumentedTest.kt`, and `QSOStore.swift` remain unchanged. There are no attached databases, cross-database joins, main-schema migrations or Groups.io startup dependencies.

Clear Downloaded Groups.io Cache removes re-fetchable remote messages/topics, FTS, incoming attachments, exports, remote-draft cache and archive sync state while preserving the credential, local drafts/outbox, outgoing attachments and reconciliation IDs. Delete All Local Groups.io Data removes only the separate database and GroupsIO directory; it warns about unsent work and preserves the API key until Disconnect. Per-group removal preserves drafts/outbox and every other group.

Disconnect cancels operations and deletes only the Keychain/Keystore credential. Disable hides the destination, cancels network/transfers, pauses archive and outbox work, and autosaves a composer. Both preserve local content.

## Validation

Android JVM tests cover no-CSRF construction, permissions, post/reply sequences, pending moderation, ambiguous delivery, safe HTML, filename safety, archive completion and online-result fetching. Instrumentation source covers v1→v2 preservation/FTS/idempotence, cache-clear draft preservation, delete-all targeting and a main-database sentinel; it is compile-only and is not run on an operator device.

Apple status: Implemented source-only. No Apple build, simulator, device or runtime validation performed. No Apple success claim.

Authenticated checks remain deferred to [GROUPS_IO_LIVE_TEST_CHECKLIST.md](GROUPS_IO_LIVE_TEST_CHECKLIST.md).
