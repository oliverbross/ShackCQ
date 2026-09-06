# Native Wavelog integration

## Baselines

- ShackCQ start: `c45fb567f2c6db6b986f95cf14d35964511ea26b` (`origin/main`, 2026-08-19).
- Wavelog stable release: `3.1.0`; annotated tag object `465dad8ab4fd33ec637c043466a2829f51ccc87d`; peeled commit `af3256140bd05403b7c4a421746c2ea653a4f04f`.
- Wavelog licence: MIT. ShackCQ remains GPL-3.0-only.
- The implementation studies the pinned API and product behaviour. It does not embed PHP, Wavelog pages, its navigation, a web server, or Wavelog source files.

## Placement

ShackCQ remains local-first. `QsoDatabase` is the one Android QSO store and existing ADIF import/export remains the serialization boundary. Schema v11 adds only Advanced Logbook query indexes after schema v10's synchronization metadata. `WavelogSyncStore`, `WavelogApiV2Client`, `WavelogSyncEngine`, and `QsoMutationCoordinator` are separate from downstream QRZ/Club Log/eQSL delivery.

One enabled writable Wavelog binding is enforced by a partial unique index. A binding stores a credential alias, never a token. Existing Android Wavelog secrets remain AES-GCM encrypted with an Android Keystore key; existing iOS Wavelog secrets remain in Keychain. A `wl2_` token is never reinterpreted as a legacy API key, and the existing legacy adapter remains available for older installations.

The Apple client negotiates token metadata/scopes, discovers stations, performs bounded paginated QSO reads, and retains local operation identities for queue bookkeeping. Wavelog 3.1.0 does not provide server-side idempotency, so neither platform may claim that a custom header makes writes idempotent. A network failure after an Apple write is marked ambiguous for inspection rather than retried. Android remains the complete Phase 1 reconciliation implementation; Apple field-level three-way conflict resolution remains a documented later-platform gap.

## Canonical and synchronization rules

- Canonical ADIF keys are uppercase and stably sorted. Callsigns, modes, bands, grids, station callsigns, propagation/satellite values, and confirmation flags are normalized. UTC date/time is normalized without changing the instant.
- Volatile sync state, remote IDs, outbox IDs, last-sync timestamps, and credential aliases are excluded from hashes.
- Unknown valid ADIF fields are retained in `extraAdifFields`, persisted inside the existing QSO details JSON, and exported again.
- Every Android operator save, POTA save, Fast Entry batch, ADIF import, edit, and delete routes through `QsoMutationCoordinator`. Local writes and native Wavelog outbox insertion share one SQLite transaction. Pending updates coalesce; an unacknowledged create remains a create.
- Reconciliation uses the last common baseline. Same-field divergent edits create a persisted conflict; disjoint field edits merge; timestamp-based last-write-wins is not used.
- API v2 list pagination uses the server's `page`, `per_page`, `total_pages`, and `has_more` metadata. Quick sync remains a bounded newest-page overlap. Full reconciliation restarts safely at page 1 and requires two complete passes with identical remote-ID inventories before it may infer deletion; cancellation, an incomplete pass, or a shifting inventory never infers deletion.
- A single JSON create captures the returned remote QSO ID, link, and baseline before the outbox operation is accepted. Failed or lost CREATE and DELETE responses are classified as ambiguous, remain operator-visible, and cannot be blindly retried. Natural-key or stable-inventory reconciliation may later prove their outcome. Rate limits and baseline-guarded UPDATE operations use bounded retry scheduling.
- Operator deletion always records an explicit `LOCAL_ONLY` or `DELETE_REMOTE_IF_UNCHANGED` intent. Local-only deletion retains remote identity and baseline metadata. Remote deletion requires `qso:delete`, verifies the remote canonical value against the accepted baseline, and turns a remote change into an operator-visible conflict.
- Keep Remote conflict resolution completes after its durable local/baseline update. Keep Local and Merged persist their intent and remain open across restart or network failure until their CREATE/PATCH/DELETE is accepted or later reconciliation proves convergence.
- A paused binding remains configured and receives durable `PAUSED` outbox work. Resume moves only paused work back to the safe pending queue; blocked ambiguous CREATE/DELETE operations remain blocked. The native dialog exposes lifecycle controls, destructive confirmations, operational error classes, remote/local identities, conflicts, tombstones, and the outbox state machine.
- API v2 requires HTTPS and platform certificate validation. Bearer tokens and QSO comments are absent from normal diagnostic messages.

## Phase 2 native logbook and Fast Entry

The Android Advanced Logbook uses SQLite `COUNT`, `WHERE`, stable `ORDER BY`, `LIMIT`, and `OFFSET` for normal browsing. It does not materialize `QsoDatabase.all()`. The native filter model covers station/provenance, worked identity and geography, TX/RX radio fields, portable and satellite references, contest/operator, QSL/service state, free text, validity, duplicate candidates, and the native Wavelog relationship state. The compact phone list and wide tablet table are two views over the same page result. Filters, sorting, dates, page size, and current page survive configuration and navigation recreation.

Selected-row actions remain explicit and bounded: edit through the existing local correction workflow, enrich through the configured callbook, export a selected row or the complete filtered result, retry the selected row's safe outbox entry, or start full reconciliation. Deletion remains a single-QSO confirmation with its existing local-only versus guarded remote consequence; no bulk remote delete was added.

Android and Apple Fast Entry are native workspaces, not embedded Wavelog pages. Both use `fixtures/wavelog/fast_entry_golden.json` for the canonical behavior contract. They preserve inherited date/time/timezone/band/mode/submode/frequency/operator/power/own references, normalize UTC, accept broad ADIF bands and modes, retain arbitrary UTF-8 ADIF fields, expose every line error/warning, preview inherited fields and duplicate candidates, allow per-row edits/callbook enrichment, and import either all valid or selected rows. Android commits local QSO and native Wavelog outbox state through `QsoMutationCoordinator`; Apple commits its SQLite batch and then durably appends the Apple queue before reporting success. Undo is accepted only before a later local mutation and removes only provably unsent creates.

## API 3.1.0 contract reviewed

The pinned server exposes `/index.php/api/v2`. API v2 accepts only `wl2_` bearer tokens (with `X-API-Key` as a server fallback), returns `{data, meta}` or `{error}`, and applies resource scopes. ShackCQ uses Bearer authorization only.

- `GET token`: owner, expiry, and scopes.
- `GET station`: selectable station ID/UUID/identity; `station:read`.
- `GET qso`: stable page metadata and station scoping; `qso:read`.
- `POST qso`: one JSON QSO for the explicitly mapped station, returning the created row and ID; `qso:write`.
- `PATCH qso/{id}`: partial update only; `qso:write`.
- `DELETE qso/{id}`: explicit destructive action; `qso:delete`.
- `GET statistic`: optional server aggregate, not the authority for local analytics; `statistic:read`.

A read-only token creates a useful local replica. The UI and engine must not imply that local edits can be pushed without `qso:write`.

## Evidence boundary

Automated tests and builds prove source/domain behaviour and migration compatibility only. They do not prove a live token, a particular Wavelog installation, network reliability, remote QSO mutation, physical device behaviour, or radio operation. No credential, remote write, merge, deployment, release, or store submission is part of this branch.

Phase 1 validation is recorded in `COMPLETION_LEDGER.md`. Compiled or simulated evidence does not substitute for authenticated Wavelog mutation or physical-tablet acceptance.
