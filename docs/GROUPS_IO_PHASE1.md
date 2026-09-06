# Groups.io read-only offline integration — Phase 1

## Scope

ShackCQ provides an optional native **Groups.io** destination on iPad and expanded Android layouts. Compact Android reaches the same screen from Settings → Integrations without changing the established bottom navigation. The feature reads subscribed groups, topics and messages, caches successful pages locally, and searches downloaded content with SQLite FTS5. It does not post, reply, mirror a full archive, download attachments, poll in the background, or display the Groups.io website in a WebView.

The feature is disabled by default. Disabled means the destination is hidden, active work is cancelled, and no Groups.io request or startup sync occurs. Credentials and downloaded content remain until their separate explicit actions are used.

## Official API contract inspected

The implementation was checked on 2026-08-20 against the [official Groups.io API reference](https://groups.io/api), revision **July 31, 2026**. The reference labels the API alpha and therefore all wire paths and response mapping remain private to the feature API implementation.

Implemented calls, all under `https://groups.io/api/v1`:

- `GET /groups` — connection verification and the authenticated user's subscribed groups.
- `GET /gettopics?group_id=…` — bounded newest topic pages.
- `GET /gettopic?topic_id=…` — bounded messages within one topic.
- `GET /getmessages?group_id=…` — inspected and retained as the group-message boundary; Phase 1's thread UI uses `/gettopic`.

The official `GET /searcharchives` endpoint was considered but deliberately deferred. Phase 1 search is local FTS over downloaded content only, works offline, and does not imply complete archive coverage.

Every request sends the credential only as `Authorization: Bearer <API_KEY>`, accepts JSON, uses explicit connect/read timeouts, and never logs headers or response bodies. Archive calls require the official `archives_visible` permission. Documented errors use the `{ "object": "error", "type": "…", "extra": "…" }` shape.

List requests use `limit=50`. Pagination treats `page_token` and `next_page_token` as opaque values, follows `has_more`, and rejects a `has_more=true` response without a next token as an API compatibility error. Membership absence is applied only after every page completes successfully. Topic and message screens request only the newest page; no automatic archive walk occurs.

## Authentication and lifecycle

Settings explains that an API key, not a password, is required and links to <https://groups.io/settings/apikeys>. **Connect and Verify** performs the smallest authenticated memberships request, completes its pagination, and stores the candidate only after successful validation.

- Apple stores `groupsIoApiKey` as a generic-password Keychain item for service `app.shackcq.mobile` with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- Android encrypts the key with an AES-GCM key generated in Android Keystore under alias `app.shackcq.mobile.groupsio.api-key`; only ciphertext is placed in feature-private preferences.

The saved key is never redisplayed. **Disconnect Groups.io** cancels work and deletes only the Keychain/Keystore-backed credential; it preserves the cache and leaves the feature enabled. **Delete Downloaded Groups.io Data** requires confirmation, closes the database, deletes the feature database/WAL/SHM and feature-owned future attachment/import directories, preserves the credential, and recreates schema version 1 on the next feature access.

## Separate database and schema

The Groups.io cache has its own handle, schema version, transactions, repository operations and deletion lifecycle. It is never attached to or joined with the main ShackCQ database.

- Apple: `Application Support/ShackCQ/GroupsIO/shackcq-groupsio.sqlite`
- Android: app-private database `shackcq-groupsio.sqlite`

Schema version 1 owns only:

- `groups`: stable ID/name/title, useful summary and permission/membership state, active marker, first/last seen, successful sync.
- `topics`: stable topic/group IDs, subject, timestamps, count, closed state, first/latest message numbers and sync time.
- `messages`: stable API/group/topic/message identity, reply number, subject/author, timestamps, normalised and display text, state/attachment flags and sync time. `(group_id,message_number)` is unique.
- `sync_state`: scope/identifier, attempts/success, cursor, error category/redacted text and more-pages state.
- `message_search`: FTS5 index containing group title, topic/message subjects, author and normalised body.

Indexes cover topics by group/latest activity, messages by topic/number, messages by group/date and sync scope. Page application is transactional and idempotent. Lists use bounded limits (40–100 rows). FTS rows are refreshed within the same transaction as message pages and whenever cached group/topic display text changes.

The Apple feature directory and database are marked excluded from backup. Android `backup_rules.xml` and `data_extraction_rules.xml` exclude the Groups.io database, credential ciphertext preferences and future `GroupsIO/` files from cloud backup and device transfer. Neither platform adds SQLCipher; protection at rest is the app-private platform sandbox plus normal device data protection, while the API key separately receives Keychain/Keystore protection.

## Local-first UI and safe rendering

Opening a group or topic queries SQLite first and displays downloaded rows immediately. A connected client then refreshes one bounded page. Network, permission, rate-limit, server and compatibility failures leave cached rows visible and record only a short non-sensitive category/message. Offline search uses FTS5 ranking and snippets across downloaded data.

Message markup is reduced to normalised plain text. Script, style, iframe and form blocks are removed; basic block boundaries become line breaks; entities are decoded conservatively. No JavaScript, forms, iframes, remote images, local file URLs or unrestricted HTML rendering are used. Attachment presence is shown without downloading an attachment.

## Manual setup

1. Open Settings → Integrations and enable Groups.io.
2. Open the system-browser API-key link and create/copy a key in Groups.io.
3. Paste it into the secure field and choose **Connect and Verify**.
4. Open **Groups.io** from the iPad/expanded Android left navigation, or from the compact Android Integrations panel.
5. Select a group and topic to cache the newest bounded pages. Use **Sync Now** for memberships.
6. Search from the Groups.io screen; results are explicitly limited to downloaded content.

## Validation evidence

Repository baseline at implementation start was branch `feature/groupsio-offline-reader`, commit `c45fb567f2c6db6b986f95cf14d35964511ea26b`, with a clean worktree. Baseline SHA-256 values for the pre-existing main-database files were recorded and are checked again at completion:

- `android/app/src/main/java/app/shackcq/mobile/QsoDatabase.kt`: `f3ad7766810158556860836b3a7e5dd5b87d257abac9e5dd992d736f0ae248a5`
- `android/app/src/androidTest/java/app/shackcq/mobile/QsoDatabaseInstrumentedTest.kt`: `e1db63b39b1691c2f6464418ced399f5d0f42e5aec7e23ee59b7ad788de266f4`
- `ios/ShackCQ/QSOStore.swift`: `8874808b5bbf34cea9ffdf277aa3839ff4cb1b86713ca583bf3114ea85ec087d`

Android deterministic JVM tests cover disabled/expanded/compact visibility, opaque pagination, safe text normalisation and documented error categories. Existing Android instrumentation infrastructure contains isolated-database tests for feature-only schema, repeated-page idempotency/offline reads/FTS, and deletion that preserves a supplied main-database fixture. Instrumentation is not run on an operator tablet.

Android Kotlin compilation passed during implementation. Final `:app:testDebugUnitTest :app:assembleDebug` evidence is recorded in the completion report.

Apple implementation is source-only and unvalidated by build/test at the owner's request; no Apple compile or runtime success is claimed. Authenticated Groups.io operation is also unverified because no owner API key was used.

## Known limitations and Phase 2 boundary

Phase 1 intentionally defers replying, new topics, drafts, an offline outbox, attachments, `GET /searcharchives` online archive search, ZIP/MBOX import, background refresh, notifications, automatic retention, multiple accounts and desktop UI. It caches only explicitly fetched bounded pages and never represents the cache as a complete group archive.

Those member-facing messaging and archive capabilities are implemented on `feature/groupsio-complete-messaging`; see [GROUPS_IO_PHASE2.md](GROUPS_IO_PHASE2.md). Background polling, administration, multiple accounts and desktop UI remain out of scope.
