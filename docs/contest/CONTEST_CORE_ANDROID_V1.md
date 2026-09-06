# Contest Core Android v1

## Sweep 2 schema and authority amendment

Contest schema 2 now owns temporary `contest_qso_entry` bodies during an event. No staged QSO enters canonical `shackcq.sqlite` until explicit confirmed merge through `QsoMutationCoordinator`; repeated and partial merges retain per-row state for safe retry. N1MM safe additions stage only. SCP is a separate validated app-private read-only assistance cache and never defines callsign validity.

## Sweep 1 operator workspace

Setup, Logging, Review and Network are now full tablet workspaces over the existing Contest authorities. Setup is definition-driven; Logging exposes eight configurable panels, dynamic exchanges, live context and canonical mutation; Review provides bounded canonical rows, filters, local-only deletion and export previews; Network exposes safe N1MM state, trust, counters and controls. Score rebuilds are latest-generation background work. No Contest surface directly owns CAT, PTT, TUNE, Digi, Chaser or remote Wavelog mutation.

## Ownership and architecture

`shackcq-contest.sqlite` schema 1 owns session lifecycle, operator/radio metadata, serial reservations, QSO ID/revision links, derived score/rate snapshots, rule-pack state, bounded N1MM dedupe/link/claim state and sanitized peer state. It contains no canonical callsign, exchange, comment or QSO body table.

Canonical QSOs remain in schema-13 `shackcq.sqlite`. `ContestQsoMapper` maps standard `CONTEST_ID`, `STX`, `SRX`, `STX_STRING`, `SRX_STRING`, RST, band/mode, zone/state and station fields. `CoordinatorContestQsoMutationPort` invokes `QsoMutationCoordinator`, preserving the existing Wavelog outbox/link/conflict/tombstone path. Wavelog availability never blocks a local save; no contest code makes Wavelog HTTP requests.

`ContestCanonicalQsoReader` pages a maximum 500 contest link IDs ordered by `(linked_at,qso_id)` and resolves only those canonical rows. The index `contest_qso_link_session_idx(session_id,linked_at,qso_id)` is asserted by instrumentation. Score rebuilds sort deterministically by `(createdAt,qsoId)`, retain the last good snapshot while a caller marks recalculation, and never materialize unrelated log rows.

## Rule and truth model

Rule packs combine immutable metadata and typed Kotlin evaluators. Load validation requires ID, ADIF/Cabrillo names, HTTPS official sources, SHA-256 digests, bands/modes and golden vector IDs. Outcomes distinguish `VALID`, `INVALID`, `INCOMPLETE`, `UNKNOWN` and `REVIEW_REQUIRED`; missing entity/zone data is never converted into zero points.

Duplicate scopes cover contest, band, mode, band/mode and bounded custom periods. Point and multiplier results include operator-readable reasons. Incremental add and full rebuild share the same definitions; edit/delete/rule change/resync callers must request rebuild. Rates include last-QSO interval, 10/60-minute rates, best 60, points/hour, multipliers/hour and bounded five-minute buckets.

## Sessions, serials and QSO mutation

Lifecycle: `DRAFT → READY → RUNNING ↔ PAUSED → STOPPED → CLOSED`. Restore changes a previously running session to paused and clears network/keyer arming. Networking never auto-starts.

Serials have reservation ID, owner, timestamp and `RESERVED/COMMITTED/RELEASED/CONFLICT` state. A reservation commits only after canonical save succeeds; failure releases it. A partial unique index prevents duplicate committed serials per session. Abandoned reservations are recoverable by a caller-supplied cutoff.

## Run/S&P, ESM and Band Map contract

`ContestEsmEngine` is pure. Blank Enter suggests `CQ` in Run or `MY_CALL` in S&P. Valid call/exchange transitions focus fields and can produce an explicit `LogQso`; duplicate/review state blocks silent logging. Escape clears state and emits `STOP`. `ContestKeyerIntent` contains session/rule/role/mode, expected operating-context generation, template variables and a post-completion logging flag—never CAT, keyer or audio objects.

`ContestOpportunityEvaluator` is side-effect free and returns band/mode validity, the same dupe/multiplier authority used by logging, expected exchange plus source, network claim and priority reasons. Future Band Map integration must consume this service and immutable session/claim/score snapshots.

## UI and exports

`ContestWorkspace` is a responsive Compose surface with setup, logging, score/multipliers, review, network and export callbacks. It has compact and wide layouts, keyboard-compatible text fields, explicit Log/Clear controls and colour-independent text states. The semantic integration adds a native top-level Contest destination backed by one `ContestRuntime`; it does not duplicate Contest, keyer, QSO or network authorities.

ADIF export is a bounded sequence of session QSOs. Cabrillo uses `START-OF-LOG: 3.0`, deterministic ordering, category/header validation, a typed formatter boundary and `VALID/VALID_WITH_WARNINGS/BLOCKED`. No online submission is performed.

## Evidence boundaries

Automated JVM/Android build evidence is software evidence. No APK install, physical UI, N1MM+, authenticated Wavelog, CAT/PTT/CW/voice/Digi/RF or sponsor upload acceptance is claimed. Apple and desktop parity are later phases.

## Final automated evidence — 2026-08-22

- `./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug`: pass; 459 JVM tests, zero skipped/failures/errors; debug and instrumentation APKs built.
- `./gradlew --no-daemon --max-workers=1 :app:lintDebug`: pass in 28m 3s; zero errors, 157 warnings and 34 hints. The non-fatal report is at `android/app/build/reports/lint-results-debug.html` and is not committed.
- Native CMake/CTest: pass, 2/2 tests.
- Focused new test inventory: 23 JVM `@Test` cases and four instrumentation `@Test` cases. The instrumentation sources and APK compiled; cases were not run because no Android device was attached or required by this brief.
- Debug APK: 111,291,921 bytes, SHA-256 `086ecec1fbf5bdec11bff6bc02cb65c598aed27213d565b28c98ec56a92fc539`.
- Instrumentation APK: 1,154,853 bytes, SHA-256 `3b97ef01754571205a4270484f9066ce5948aadde3c9737d7a183bda4eb3861f`.

Final ownership scans and outcomes:

- `git diff --name-only b4f12e17... -- <forbidden paths>` plus conflict-marker scan: no output.
- `rg -l 'QsoMutationCoordinator' .../contest`: one adapter file; `rg -l 'HttpURLConnection|OkHttpClient|Retrofit.Builder|newCall(' .../contest .../n1mm`: zero HTTP client owners.
- N1MM socket-owner scan: two transport files; raw-file-write scan: zero files; XML scan confirms DTD and both external-entity features disabled.
- Session schema scan lists only session, serial, canonical-link/revision, derived score/rate, rule-pack, sanitized peer/network/dedupe/remote-link/claim tables; no QSO-body table.
- `definitions-v1.json`: 13 definition IDs.
