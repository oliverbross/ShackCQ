# ShackCQ Phase 4A — Local Log Sync Hub v1

## QRZ Logbook, Club Log and eQSL delivery for local-log users

You are working autonomously in:

```text
https://github.com/oliverbross/ShackCQ
```

This task authorises the next major product feature: a polished, durable **Sync Hub** for operators who use ShackCQ’s local log as their logging authority.

The owner wants real feature delivery, excellent UI/UX and lean engineering. Do not turn this into a maintenance phase, generic sync framework, broad refactor, lint campaign or evidence factory.

---

# 1. Starting point

Expected `origin/main` when this prompt was prepared:

```text
80c1a0fd8a3ba2fd1dd2b92efeb1a9f920ec41bf
docs: document Android POTA Activate validation
```

The preceding implementation commit is:

```text
f1f31de1b6356adf558a93c772b08d2220921228
```

Current product capabilities include:

- Android KX3/KX2 CAT control;
- local SQLite QSO journal and ADIF;
- existing Wavelog two-way authority mode and durable upload queue;
- QRZ/HamQTH callbook enrichment;
- POTA/SOTA/WWFF Portable Chase;
- recoverable POTA Activate sessions and correct POTA ADIF export;
- existing per-QSO fields such as `qrzSent`, `clublogSent`, `eqslSent`, confirmation fields and `syncState`;
- Android Keystore-backed secret storage patterns in the Wavelog implementation;
- a known unrelated lint baseline that is not part of this task.

Current `docs/ROADMAP.md` defines Phase 4 as Sync and Progress. This task implements the first half:

```text
Phase 4A — Local Log Sync Hub v1
```

The next separate feature after this task will be:

```text
Phase 4B — Needs Board, statistics and progress
```

Before editing:

1. Fetch `origin`.
2. Confirm the actual latest `origin/main`.
3. Read every applicable `AGENTS.md`.
4. Inspect `QsoDatabase`, all operator-created QSO save paths, ADIF serialization, Wavelog authority/queue behaviour, settings, logbook UI and POTA Activate.
5. Preserve unrelated owner work, especially the existing dirty iOS checkout and old panadapter worktree.
6. Use an isolated worktree.
7. Create:

```text
feature/android-local-log-sync-hub-v1
```

Branch from the current clean `origin/main`.

If `main` has advanced, use the newer baseline and report it. Never reset, clean destructively, force-push, discard or rewrite owner work.

---

# 2. Product outcome

A user operating in **Local log** mode must be able to:

1. configure one or more supported destinations;
2. explicitly enable automatic delivery of future operator-created QSOs;
3. log normally in Radio, POTA Activate or any existing operator logger;
4. see each destination’s independent status;
5. continue logging offline;
6. allow valid queued QSOs to send when connectivity returns;
7. understand accepted, duplicate, rejected, authentication-blocked and retry states;
8. correct a local QSO or credentials and deliberately retry;
9. explicitly select historical QSOs for catch-up without accidental bulk upload;
10. retain Wavelog as the sole authority when Wavelog mode is selected.

The core interaction should feel like:

> **Log once → ShackCQ safely delivers to enabled services → attention is required only when a destination rejects or needs credentials.**

This phase uploads QSOs. It does not claim or calculate remote confirmation unless a later phase imports authoritative confirmation data.

---

# 3. Authority model — non-negotiable

ShackCQ already supports:

```text
LOCAL
WAVELOG
```

Preserve that distinction.

## Local authority mode

Only in `LOCAL` mode may ShackCQ directly deliver QSOs to:

```text
QRZ Logbook
Club Log
eQSL.cc
```

The user chooses each destination independently.

## Wavelog authority mode

When `WAVELOG` mode is active:

- existing Wavelog two-way sync remains authoritative;
- newly logged QSOs continue through the existing Wavelog queue;
- direct QRZ/Club Log/eQSL automatic delivery is paused and cannot be newly enabled;
- show a clear explanation that Wavelog can manage downstream integrations and duplicate direct uploads should be avoided;
- do not delete existing direct-destination history or queued state merely because authority changed;
- do not send a paused direct queue until the user returns to Local mode and explicitly resumes it.

Do not add an advanced override in v1. The owner explicitly wanted direct uploads only for users relying on the local log.

Do not overload the existing `Qso.syncState`; it currently participates in Wavelog/local semantics. Multi-destination delivery needs its own state.

---

# 4. Official provider interfaces

Verify the current official documentation at implementation time. Use only official interfaces and current requirements.

## 4.1 QRZ Logbook

Official documentation:

```text
https://www.qrz.com/docs/logbook/QRZLogbookAPI.html
https://www.qrz.com/docs/logbook30/api
```

Endpoint:

```text
https://logbook.qrz.com/api
```

Current relevant rules:

- HTTP POST using `application/x-www-form-urlencoded`;
- every request contains `KEY` and `ACTION`;
- one-QSO insertion uses `ACTION=INSERT` plus one ADIF record;
- the API key selects a specific QRZ logbook and gives full read/write access;
- insert/API use requires the appropriate QRZ subscription, currently XML level or higher;
- every application must use an identifiable user agent no longer than 128 characters;
- QRZ logbooks are callsign-specific, including `/P`, `/M` and other suffixes;
- QRZ logbooks have active date ranges;
- `OPTION=REPLACE` can overwrite an existing confirmed QSO with an unconfirmed record.

ShackCQ requirements:

- use `ACTION=STATUS` for a read-only connection test;
- use `ACTION=INSERT` for delivery;
- never send `OPTION=REPLACE` automatically;
- never delete or modify a QRZ QSO in this phase;
- parse `RESULT`, `REASON`, `LOGID`/`LOGIDS`, `COUNT` and `DATA` from form-encoded responses;
- treat `OK` as accepted;
- treat `REPLACE` as success only if it is ever returned without ShackCQ requesting replacement, and record it visibly;
- treat `AUTH` or an invalid-key status as authentication-blocked;
- treat a clearly identified duplicate as `ACCEPTED_DUPLICATE` only when the provider response unambiguously says it already exists;
- quarantine other `FAIL` responses with the exact human-readable reason;
- ensure the QSO `STATION_CALLSIGN` exactly matches the configured QRZ logbook callsign before sending;
- if callsign or date-range information from `STATUS` proves a mismatch, block that QSO before upload;
- retain the returned QRZ `LOGID` as remote metadata.

## 4.2 Club Log

Official documentation:

```text
https://clublog.freshdesk.com/support/solutions/articles/54906-how-to-upload-qsos-in-real-time
https://clublog.freshdesk.com/support/solutions/articles/54905-how-to-upload-batches-of-qsos-directly-into-club-log
https://clublog.freshdesk.com/support/solutions/articles/3000110752-ip-address-bans
```

New QSOs at normal operator pace:

```text
POST https://clublog.org/realtime.php
Content-Type: application/x-www-form-urlencoded
```

Fields:

```text
email
password        — Club Log application password, not the main password
callsign
adif            — exactly one record ending in <EOR>
api             — Club Log API key
```

Historical/batch catch-up:

```text
POST https://clublog.org/putlogs.php
Content-Type: multipart/form-data
```

Fields:

```text
email
password
callsign
file
api
```

Never send `clear=1`.

Current response rules for realtime delivery:

- `200 QSO OK` — accepted;
- `200 QSO Duplicate` — accepted duplicate;
- `200 QSO Modified` — accepted with provider adjustment;
- `400` — rejected QSO, quarantine with body;
- `403` — authentication/prerequisite failure: stop all Club Log traffic immediately and remain blocked until credentials/configuration change and the operator explicitly resumes;
- `500` — transient server/parser failure: retain for later retry.

Important anti-abuse rules:

- `realtime.php` must never be used to replay a sequential backlog;
- `putlogs.php` must never be used repeatedly for tiny real-time submissions;
- repeated `403` traffic can firewall the user’s IP address;
- every failure body must be shown safely to the user;
- credentials use the user’s email, Club Log application password and owned callsign;
- an API key is required.

### Club Log app-key handling

Do not commit a real Club Log API key.

Support an API key supplied through one of these safe routes:

1. untracked build configuration/local property for an official ShackCQ app key; or
2. an advanced user-entered API-key field for self-built/personal installations.

The normal provider card must show:

```text
APP API KEY REQUIRED
```

when none is available.

Do not invent, scrape or borrow another application’s key.

## 4.3 eQSL.cc

Official documentation:

```text
https://www.eqsl.cc/qslcard/Programming.cfm
https://www.eqsl.cc/qslcard/ImportADIF.txt
https://www.eqsl.cc/qslcard/ADIFContentSpecs.cfm
```

At implementation time:

- retrieve and inspect the current official `ImportADIF.txt` specification;
- implement only the endpoint, credential fields, encoding and response semantics currently documented there;
- use the documented minimum ADIF requirements and accepted modes/bands;
- preserve `QTHNickname` or equivalent current profile-routing field when supported;
- include `PROGRAMID=ShackCQ` and current programme version where the official specification supports it;
- support an optional configured eQSL QTH nickname;
- never guess the endpoint or parse success from a generic HTTP 200 alone;
- treat provider-declared duplicate as accepted duplicate;
- quarantine provider-declared rejects with their message;
- block authentication failures until credentials change;
- never log the password or credential-bearing URL/body.

Portable/QTH safety:

- eQSL accounts and QTH profiles affect where an eQSL is attributed;
- if a QSO contains a portable activation/reference, a materially different `MY_GRIDSQUARE`/state, or a station profile different from the configured home profile, require an explicit eQSL QTH nickname mapping before automatic upload;
- do not silently send portable QSOs into an unrelated home-QTH profile;
- if the official interface cannot safely express the selected QTH, mark the item `PROFILE_REQUIRED` and provide manual ADIF export/browser handoff.

If the official `ImportADIF.txt` specification is unavailable or materially ambiguous during implementation, do not reverse-engineer the website. Deliver QRZ and Club Log, keep eQSL visibly disabled as:

```text
OFFICIAL LOGGER SPEC UNAVAILABLE
```

and report `PASS WITH NOTES`. Do not guess.

---

# 5. Scope

## Required

- Android Sync Hub UI.
- Local-vs-Wavelog authority gating.
- QRZ provider.
- Club Log real-time provider.
- Club Log explicit batch catch-up.
- eQSL provider when the current official logger specification is verifiable.
- Secure credential storage.
- Durable per-QSO, per-provider delivery state.
- Automatic enqueue for future operator-created local QSOs only.
- Explicit historical/backfill selection.
- Offline queueing and safe retry.
- Authentication/provider-level blocking.
- Human-readable attention/rejection workflow.
- Logbook per-QSO delivery indicators.
- Existing POTA Activate and normal logger integration.
- Focused tests.
- One Lenovo smoke run without fake QSOs.
- One concise document.
- Commit, push and conditional merge.

## Excluded

- LoTW certificate import or signing.
- Direct LoTW upload.
- QRZ remote delete/modify.
- Club Log remote delete.
- eQSL Inbox/Outbox download or graphics.
- Remote confirmation import.
- Official award imports.
- Wavelog downstream-provider configuration.
- Background Android service or unrestricted WorkManager loop.
- cloud account/backend.
- fake QSO creation for testing.
- SOTA live API work.
- SOTA/WWFF Activate.
- iPadOS.
- FlexRadio.
- desktop.
- QMX.
- Nexus/Rust integration.
- broad QSO database redesign.
- broad lint repair.
- release/store work.

---

# 6. Durable delivery model

Add a small dedicated table in the existing app database, or an equally robust app-private SQLite store, for per-provider delivery.

A reasonable row shape is:

```text
qso_id
provider
state
created_at
updated_at
attempt_count
last_attempt_at
next_attempt_at
payload_hash
remote_id
provider_message
http_status
```

Use the existing QSO row as the authoritative data. Generate canonical one-record ADIF from the latest local QSO at send time, then store its hash and the provider result.

Recommended state vocabulary:

```text
QUEUED
SENDING
ACCEPTED
ACCEPTED_DUPLICATE
ACCEPTED_MODIFIED
RETRY_WAIT
REJECTED
AUTH_BLOCKED
PROFILE_REQUIRED
CONFIG_REQUIRED
PAUSED_AUTHORITY
LOCAL_CHANGED
```

Exact names may differ, but the UI and transitions must be explicit.

Requirements:

- primary key: QSO + provider;
- migration preserves all existing QSOs and app data;
- no delivery record means “never selected for this provider”, not failure;
- accepted states are durable across restart;
- provider responses are sanitized and bounded in length;
- credentials never enter the table;
- queue ordering is deterministic, oldest first;
- only one in-flight request per provider;
- no overlapping attempts for one delivery;
- process survives app restart;
- network loss returns the item to retry state;
- deleting a not-yet-sent local QSO removes/cancels its queued delivery;
- deleting a remotely accepted QSO does not automatically delete the remote record in v1;
- editing a queued/retry/rejected QSO uses the current local data on deliberate retry;
- editing an already accepted QSO marks it `LOCAL_CHANGED`; do not silently overwrite the remote record;
- provide a deliberate `Queue updated copy` action with a warning that the remote service may treat it as duplicate;
- never auto-retry a validation reject or authentication block.

Update existing compatibility flags only after provider acceptance:

```text
qrzSent = Y
clublogSent = Y
eqslSent = Y
```

Do not touch `qrzReceived`, `clublogReceived`, `eqslReceived` or any confirmation field in this phase.

---

# 7. Which QSOs auto-enqueue

Automatic delivery defaults to **OFF** for every provider.

After the user configures and explicitly enables a provider in Local mode:

- only future operator-created QSOs auto-enqueue;
- this includes QSOs saved from the normal Radio logger and POTA Activate;
- it does not include Wavelog-pulled QSOs;
- it does not include ADIF imports;
- it does not include remote merges;
- it does not include test fixtures;
- it does not silently queue the existing logbook.

Inspect every operator QSO save path and introduce the smallest central save/delivery hook that cannot miss a legitimate operator save.

Do not place provider/network logic inside Compose.

A small origin classification is appropriate:

```text
OPERATOR
IMPORT
REMOTE_SYNC
```

Only `OPERATOR` auto-enqueues.

Historical catch-up is explicit:

- select a date range or selected QSOs;
- preview count and station callsigns;
- choose destinations;
- confirm once;
- never include remotely imported QSOs unless the user deliberately selects them;
- never change provider remote logs destructively.

---

# 8. Delivery scheduling and retry

No permanent background service is required.

Process queues when:

- a new operator QSO is saved while the app is foregrounded;
- the Sync Hub is opened;
- the user taps Sync now;
- connectivity returns while the app process is active;
- the existing lightweight foreground timer runs.

Use conservative bounded retry for network and transient server failures:

```text
1 minute
5 minutes
15 minutes
1 hour
6 hours
```

Cap automatic attempts. After the cap, keep the item visible for manual retry.

Rules:

- never retry `AUTH_BLOCKED`, `REJECTED`, `PROFILE_REQUIRED` or `CONFIG_REQUIRED` automatically;
- a credentials/configuration change resets the provider block only after explicit operator confirmation or a successful read-only test;
- QRZ `STATUS` may validate credentials without creating a QSO;
- do not create a fake QSO to test Club Log or eQSL;
- a Club Log `403` stops all Club Log activity immediately;
- show an IP-block warning after Club Log `403`;
- provider queues are independent;
- one provider failure never blocks the others;
- no retry storm after restart or connectivity change;
- use UTC timestamps.

---

# 9. Backfill behaviour

## QRZ

An explicit historical selection may queue individual `INSERT` requests.

Use a conservative sequential cadence and pause immediately on:

- authentication failure;
- rate-limit response;
- repeated server failure;
- provider request to stop.

Never use `OPTION=REPLACE` for catch-up.

## Club Log

- future operator QSOs use `realtime.php` at normal logging pace;
- historical selection must be assembled into one ADIF file and uploaded once through `putlogs.php`;
- do not call `realtime.php` repeatedly for historical catch-up;
- do not call `putlogs.php` repeatedly for tiny groups;
- never include `clear=1`;
- show that Club Log processing may take time after batch acceptance;
- mark selected rows `SUBMITTED_BATCH` or equivalent until the batch submission is accepted locally; do not claim per-QSO remote processing/confirmation from one HTTP 200;
- keep the batch manifest and response so the operator can reconcile it.

## eQSL

Follow the current official real-time/batch specification exactly.

If the official interface supports a multi-record submission for catch-up, use one explicit operator-confirmed batch. Otherwise provide ADIF export/browser handoff rather than replaying hundreds of real-time requests.

---

# 10. Credentials and configuration

Use Android Keystore-backed encrypted storage. Reuse or extract the existing Wavelog secret-storage pattern with the smallest safe implementation.

Never store secrets in:

- plain SharedPreferences;
- SQLite delivery rows;
- logs;
- support exports;
- screenshots;
- crash messages;
- source code;
- committed Gradle/local property files.

Provider configuration:

## QRZ

- logbook callsign;
- QRZ logbook API key;
- read-only STATUS test;
- subscription requirement explanation;
- returned logbook name/owner/date range when available.

## Club Log

- account email;
- Club Log application password;
- target callsign;
- app API key state;
- explicit explanation that the main Club Log password should not be used;
- no fake-QSO connection test.

## eQSL

- callsign/username;
- password;
- optional QTH nickname;
- station-profile mapping where needed;
- current official read-only credential test if documented;
- otherwise first-real-upload validation with no fake QSO.

Changing callsign, key, password, QTH mapping or target profile:

- pauses the provider;
- clears the provider-level authentication block only after the operator saves and explicitly resumes/tests;
- does not delete accepted history;
- does not silently reroute existing queued QSOs to a different callsign.

---

# 11. UI/UX

The UI is a primary deliverable.

Add a polished Sync Hub reachable from:

```text
Logbook → Sync
Settings → Log services
```

Do not add another top-level bottom-navigation item.

Preserve the Flightline visual language.

## Main Sync Hub

Wide tablet layout:

```text
┌───────────────────────────────────────────────────────────────┐
│ SYNC HUB · LOCAL AUTHORITY · connectivity · pending/attention │
├──────────────────────────────┬────────────────────────────────┤
│ Provider cards               │ Outbox / selected item detail  │
│ QRZ                           │ queued / attention / delivered │
│ Club Log                      │                                │
│ eQSL                          │                                │
└──────────────────────────────┴────────────────────────────────┘
```

Compact layout:

- authority banner;
- provider cards;
- segmented outbox lists;
- item detail sheet;
- no horizontal overflow.

## Authority banner

Show one of:

```text
LOCAL LOG AUTHORITY · direct destinations available
WAVELOG AUTHORITY · direct destinations paused to prevent duplicates
```

Make the reason obvious without a modal.

## Provider cards

Each card shows:

- enabled/disabled/paused;
- configured callsign/profile;
- connection/readiness state;
- queued count;
- attention count;
- accepted count;
- last success;
- last provider message;
- Configure;
- Sync now or Resume;
- provider-specific warning.

Do not expose secrets after save. Offer replace/clear actions.

## Outbox

Filters:

```text
ALL
QUEUED
ATTENTION
DELIVERED
```

Each row shows:

- callsign;
- UTC date/time;
- band/mode;
- station callsign;
- provider badge;
- state;
- attempt count;
- concise reason.

Actions depend on state:

- Retry now;
- Edit QSO;
- Requeue current local version;
- Remove unsent delivery;
- Copy provider message;
- Open provider site.

Do not allow removal of the local QSO from the Sync Hub.

## Logbook integration

Add small restrained per-QSO delivery indicators, for example:

```text
QRZ ✓
CL •
eQSL !
```

Avoid turning every log row into a dense dashboard. Full detail opens in the QSO/detail/Sync surface.

## Historical selection

Provide a clear `Queue existing QSOs` flow:

- date range;
- station callsign/profile;
- destination selection;
- preview count;
- explicit confirmation;
- provider-specific strategy explanation, especially Club Log batch upload.

No one-tap “upload everything”.

---

# 12. ADIF and provider-safe payloads

Reuse the existing canonical ADIF serializer where possible.

Every one-record payload must end in `<EOR>` and include valid current fields such as:

```text
CALL
QSO_DATE
TIME_ON
BAND or FREQ
MODE/SUBMODE
STATION_CALLSIGN
```

Retain useful compatible fields:

- RST;
- grid;
- power;
- POTA/SOTA/WWFF fields;
- own station/location fields;
- QSL message when provider supports it.

Provider-specific requirements override generic export.

Do not include malformed empty tags.

Use deterministic payload generation and SHA-256 fingerprinting.

Do not expose passwords, API keys or credential-bearing URLs in support diagnostics.

---

# 13. Failure handling

Required operator-facing states:

```text
Not configured
Ready
Paused — Wavelog authority
Queued offline
Sending
Accepted
Already present
Accepted with provider changes
Retry scheduled
Rejected — edit QSO
Authentication blocked — update credentials
Profile required
App API key required
Manual upload required
```

Rules:

- preserve the exact provider reason in a sanitized detail view;
- primary UI gets a concise explanation, not raw HTML;
- truncate excessive body content;
- strip credentials and control characters;
- no stack traces;
- no false success from HTTP 200 when body reports failure;
- no false “confirmed” state;
- provider outage never blocks local logging;
- an unsent queue remains durable after crash/restart.

---

# 14. Minimal focused validation

Do not expand unrelated test coverage.

Add compact deterministic tests for:

1. Local vs Wavelog authority gating.
2. Operator-created save enqueues enabled providers.
3. import/remote-sync saves do not auto-enqueue.
4. durable delivery round-trip and migration.
5. provider-independent state transitions.
6. retry timing and cap.
7. authentication block prevents all further requests.
8. QRZ request/response parser and no `OPTION=REPLACE`.
9. QRZ station-callsign mismatch block.
10. Club Log `200 OK`, `Duplicate`, `Modified`, `400`, `403`, `500` handling.
11. Club Log backlog uses batch, never sequential realtime.
12. Club Log batch never sends `clear=1`.
13. eQSL official response parser/profile gate when implemented.
14. credentials absent from persisted delivery/error/support output.
15. accepted state updates only the matching `*Sent` flag.
16. received/confirmation flags remain unchanged.
17. editing accepted QSO marks local change rather than silently resending.
18. queue survives restart.
19. SOTA live remains disabled pending approval.

Use local fake HTTP transports only in tests. Never add fake production success or QSO state.

Final Android gate:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Run shared CTest only if shared C++ changes.

Do not run Apple builds unless Apple/shared ABI files change.

Do not require `lintDebug`; fix only obvious new problems in touched files.

Connected instrumentation is optional unless required to prove real Android SQLite migration or Keystore behaviour.

---

# 15. Lenovo smoke run

When Lenovo `TB373FU` is available:

1. Build the final APK.
2. Install with:

   ```bash
   adb install -r <apk>
   ```

3. Preserve existing catalogues, POTA sessions/history, EQ profiles, voice recordings, settings and QSOs.
4. Open Sync Hub.
5. Confirm Local/Wavelog authority banners behave correctly.
6. Confirm all providers default disabled.
7. Open each configuration surface.
8. Confirm secrets are masked after save and absent from logs/support text.
9. With no real credentials, verify no request is made and states remain truthful.
10. Switch to Wavelog mode and confirm direct providers pause.
11. Return to Local and confirm they do not resume without explicit action where required.
12. Confirm existing Portable Chase and POTA Activate still open.
13. Confirm SOTA live remains approval-blocked.
14. Do not create or save a fake QSO.
15. Do not upload a historical database merely for testing.
16. Do not transmit.
17. If genuine credentials and an existing real unsent QSO are already available, an operator-controlled real upload may be tested; otherwise record it as not run.

Capture only:

```text
sync-hub-local.png
sync-hub-wavelog-paused.png
sync-hub-provider-detail.png
```

No large evidence campaign.

---

# 16. Documentation

Create one concise document:

```text
docs/LOCAL_LOG_SYNC_HUB_ANDROID.md
```

Include:

- authority model;
- supported providers;
- official endpoints and requirements;
- secure credential handling;
- automatic-vs-historical behaviour;
- delivery states and retries;
- Club Log anti-abuse behaviour;
- portable/eQSL profile safety;
- provider limitations;
- validation summary;
- screenshots.

Update only current truth where needed:

```text
README.md
PRODUCT.md
DESIGN.md
docs/ROADMAP.md
NOTICE
```

Do not rewrite historical Phase 0–3 evidence.

Roadmap status after pass:

- Phase 4A Local Log Sync Hub implemented on Android;
- direct services operate only in Local authority mode;
- QRZ/Club Log/eQSL confirmation import remains later;
- direct LoTW signing remains deferred;
- Phase 4B Needs Board/statistics is next;
- SOTA live still pending written API approval;
- iPadOS parity remains deferred;
- no Nexus source incorporated.

Do not create multiple reports or a maintenance gate.

---

# 17. Acceptance

Use:

```text
PASS
PASS WITH NOTES
STOPPED
```

## PASS

- local/Wavelog authority gating is correct;
- QRZ delivery is implemented against current official API;
- Club Log realtime and explicit batch strategies are correctly separated;
- eQSL is implemented only from a current official spec;
- secrets are encrypted and never leaked;
- future operator QSOs enqueue only in Local mode;
- existing/imported/remote QSOs do not auto-upload;
- delivery state is durable and provider-specific;
- Club Log `403` blocks all further requests;
- no destructive remote operation exists;
- UI is polished on Lenovo;
- Android tests/build pass;
- APK installs with data preserved;
- no fake QSO, upload or RF transmission was used;
- documentation is concise;
- branch is merged and pushed cleanly.

## PASS WITH NOTES

Acceptable notes:

- authenticated provider uploads not physically tested because no genuine credentials/real QSO were available;
- Club Log app API key still requires owner/helpdesk provisioning, with provider correctly disabled;
- eQSL official logger specification was unavailable and eQSL is correctly disabled rather than guessed;
- no historical catch-up was actually submitted during smoke validation.

These do not block merge when implementation, build, state machines and UI are complete.

## STOPPED

Do not merge if:

- Wavelog mode can direct-upload and create duplicates;
- imported/remote QSOs auto-upload without explicit selection;
- Club Log backlog is replayed through realtime.php;
- Club Log continues after `403`;
- QRZ uses `OPTION=REPLACE` automatically;
- secrets appear in logs/database/support output;
- provider HTTP 200 is treated as success despite failure body;
- confirmation flags are fabricated;
- queue is not durable;
- local QSO logging is blocked by provider failure;
- existing owner data is lost;
- build/tests fail because of this task.

---

# 18. Git and merge authority

Use focused commits, for example:

```text
feat(android): add local log Sync Hub
feat(android): add QRZ Club Log and eQSL delivery
docs: document Android local log sync
```

Push the feature branch.

The owner authorises merge into `main` on `PASS` or genuine `PASS WITH NOTES`.

Before merge:

- fetch `origin`;
- integrate latest `origin/main` normally if required;
- rerun the final Android unit/build command;
- confirm smoke evidence matches final code;
- confirm no unrelated files;
- confirm all relevant worktrees are clean.

Never force-push, delete branches, tag, release, publish an APK, deploy or submit to stores.

Final expected state:

```text
local main == origin/main
feature branch pushed
main and feature worktrees clean
SOTA live remains disabled pending approval
```

---

# 19. Required final response

Return a practical implementation report containing:

### Verdict

```text
PASS | PASS WITH NOTES | STOPPED
```

### Git

- starting main;
- feature branch;
- commits;
- final main if merged;
- clean/equality status.

### Delivered experience

- authority gating;
- provider cards/configuration;
- automatic future-QSO flow;
- historical selection;
- outbox/attention workflow;
- QSO indicators;
- retry/block behaviour;
- key UI decisions.

### Provider status

For each of QRZ, Club Log and eQSL state:

- implementation status;
- official interface used;
- authenticated physical test status;
- exact external prerequisite if blocked.

### Validation

- focused test result;
- full Android unit/build result;
- APK path/version/size/SHA-256;
- install/data-preservation result;
- three screenshot paths;
- explicit statements:

```text
No fake QSO was created or uploaded.
No RF transmission was performed.
```

### Known limitations

Only actual remaining limitations.

### Documentation

Link:

```text
docs/LOCAL_LOG_SYNC_HUB_ANDROID.md
```

Do not begin Phase 4B Needs Board, LoTW, SOTA API integration, iPadOS, FlexRadio, desktop or another maintenance phase in this task.

