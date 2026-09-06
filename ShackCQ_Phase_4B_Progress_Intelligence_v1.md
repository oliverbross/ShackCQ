# ShackCQ Phase 4B — Progress Intelligence v1

## Needs Board, local award estimates, portable analytics and actionable operating statistics

You are working autonomously in:

```text
https://github.com/oliverbross/ShackCQ
```

This task authorises the next major Android product feature: a polished **Progress Intelligence** cockpit that turns ShackCQ’s existing log, confirmation fields, Sync Hub, Portable Chase and POTA Activate data into useful operating decisions.

The owner wants clean feature implementation, excellent tablet UI/UX and lean engineering. Do not turn this into a maintenance phase, generic analytics platform, official-award service, broad refactor, chart-library experiment, lint campaign or evidence factory.

---

# 1. Starting point

Expected `origin/main` when this prompt was prepared:

```text
844df6961c3f7430896dfc324c761b3d4a495e4b
docs: document Android local log sync
```

The Phase 4A implementation commit is:

```text
8bb60f236bf3605f0d1f52ab72abf8cd820c2378
feat(android): add local log Sync Hub
```

The current Android product already contains:

- one local SQLite QSO journal;
- `QsoDatabase` schema version 7;
- local/Wavelog authority modes;
- QRZ, Club Log and eQSL delivery state;
- QSO fields for DXCC, CQ/ITU zones, state, continent, country, grid, distance, band, mode, station profile, power and confirmation channels;
- `qslReceived`, `lotwReceived`, `qrzReceived`, `eqslReceived` and related sent flags;
- existing `NeuralLogSummary`, station insight and worked-state primitives;
- live DX/cluster intelligence;
- POTA/SOTA/WWFF Portable Chase;
- offline POTA and SOTA catalogues;
- WWFF live references without a stored full directory;
- recoverable POTA Activate sessions;
- `activationSessionId`, `myPotaRefs` and `potaRefs`;
- correct multi-park/P2P POTA exports;
- a durable Sync Hub;
- MapLibre maps;
- no approved live SOTA API access.

Current roadmap truth:

```text
Phase 4A complete
Phase 4B next — Needs Board and useful activation/award statistics
```

Before editing:

1. Fetch `origin`.
2. Confirm the actual latest `origin/main`.
3. Read every applicable `AGENTS.md`.
4. Inspect:
   - `QsoDatabase.kt`;
   - `NeuralDxController.kt` / current DX screen;
   - `PortableModels.kt`, `PortableRepository.kt` and `PortableChaseScreen.kt`;
   - `PotaActivation.kt` and `PotaActivateScreen.kt`;
   - `SyncHub.kt` and `SyncHubScreen.kt`;
   - `CtyController.kt` and shared CTY resolution;
   - navigation and logbook filters.
5. Preserve unrelated owner work, especially the dirty iOS checkout, old panadapter worktree and untracked owner archives.
6. Use an isolated worktree.
7. Create:

```text
feature/android-progress-intelligence-v1
```

Branch from the current clean `origin/main`.

If `main` has advanced, use the newer baseline and report it. Never reset, clean destructively, force-push, discard or rewrite owner work.

---

# 2. Product outcome

Build one coherent Android **Progress** workspace that answers:

- What have I worked?
- What has local confirmation evidence?
- What am I still missing?
- Which live stations can advance my goals now?
- How effective are my bands, modes, station profiles and portable activations?
- What should I operate next?

The operating loop should become:

```text
local/Wavelog log
    ↓
Progress and needs calculated locally
    ↓
live DX / POTA / WWFF opportunities matched to needs
    ↓
open existing DX or Portable Chase workflow
    ↓
operator tunes and logs normally
    ↓
progress refreshes
```

The workspace must remain useful entirely offline except for the already-existing live-opportunity sources.

This phase calculates **local estimates**. It does not claim official award credit.

---

# 3. Lean delivery rules

Do not create:

- a generic award definition language;
- a downloadable award-rule marketplace;
- a cloud analytics backend;
- a telemetry system;
- a data warehouse;
- a second QSO database;
- a denormalised statistics database;
- a new network client;
- a new map provider;
- a third-party chart dependency;
- a large shared-core refactor;
- an AI scoring service;
- official POTA/SOTA/WWFF account scraping;
- LoTW certificate/signing work;
- remote confirmation import;
- a Nexus/Rust dependency;
- a broad test campaign;
- a broad lint repair;
- iPadOS parity.

Implement a focused local snapshot engine and a high-quality native Compose UI using the data ShackCQ already has.

Nexus may be inspected as behavioural inspiration only. No source, crate, dependency or derived component is authorised here.

---

# 4. Scope

## Required

- Android Progress workspace.
- Overview dashboard.
- Needs Board.
- Local award-style progress.
- Portable hunter and activator analytics.
- General operating statistics.
- Confirmation and data-quality breakdown.
- Small predefined pinned goals.
- Live-needs matching using existing DX and Portable feeds.
- Deep links into existing DX, Portable Chase, Logbook and Sync Hub surfaces.
- Existing station/time filters.
- Responsive expanded-tablet and compact UI.
- Focused tests.
- One Lenovo smoke run without fake QSOs.
- One concise implementation document.
- Commit, push and conditional merge.

## Excluded

- official award applications;
- official programme credit import;
- POTA account statistics scraping;
- SOTA live API work;
- WWFF full directory storage;
- eQSL/QRZ/LoTW confirmation download;
- direct LoTW signing/upload;
- all POTA award categories;
- SOTA or WWFF Activate;
- panadapter spot overlays;
- notifications/background alerts;
- automatic tuning;
- any transmit action;
- iPadOS;
- FlexRadio;
- desktop;
- QMX;
- IOTA/BOTA/lighthouse programme implementation;
- Nexus/Rust;
- release/store work.

---

# 5. Truth and terminology

Every award-like surface must carry a visible persistent label:

```text
LOCAL ESTIMATE · NOT OFFICIAL AWARD CREDIT
```

Use distinct states:

```text
WORKED LOCALLY
LOTW/QSL CONFIRMED LOCALLY
DIGITAL QSL RECORDED
UPLOADED
OFFICIAL STATUS UNKNOWN
```

Never use:

```text
AWARD GRANTED
OFFICIAL CREDIT
POTA CONFIRMED
SOTA CONFIRMED
WWFF CONFIRMED
DXCC CREDIT
WAS CREDIT
WAZ CREDIT
```

unless a future authoritative service explicitly supplies that status.

## Confirmation semantics

For the award-style confirmed count, use only:

```text
lotwReceived in Y/V
OR
qslReceived in Y/V
```

This matches the existing ShackCQ `NeuralLogSummary` convention and avoids pretending that an upload or unrelated service is official award credit.

Show these separately when present:

```text
QRZ confirmation recorded
eQSL confirmation recorded
```

Do not count Club Log acceptance as confirmation.

Phase 4A delivery states are upload/delivery evidence only. Do not reinterpret `qrzSent`, `clublogSent` or `eqslSent` as confirmation.

## Missing data

Unknown is not zero.

Display coverage for:

- DXCC entity;
- CQ zone;
- U.S. state;
- grid;
- distance;
- TX power;
- station profile;
- portable reference.

When a metric excludes incomplete rows, show:

```text
Based on 842 of 1,017 QSOs with DXCC data
```

Do not silently resolve or mutate stored QSO fields during statistics calculation.

A temporary in-memory CTY resolution from callsign may improve display where reliable, but:

- do not write it back automatically;
- preserve the distinction between stored and inferred data;
- do not claim CTY.DAT is the official current ARRL DXCC list.

---

# 6. Progress data engine

Create a small feature-led layer such as:

```text
ProgressController
ProgressSnapshot
ProgressFilters
ProgressGoalStore
```

Exact names may follow repository conventions.

## Snapshot behaviour

- Read the existing QSO journal as the sole local authority.
- Refresh when:
  - `QsoDatabase.changeToken()` changes;
  - station scope changes;
  - time range changes;
  - relevant live-spot snapshots change;
  - a pinned goal changes.
- Build snapshots off the main thread.
- Publish immutable UI state.
- Cancel superseded calculations.
- Never run one SQL query per card, row or recomposition.
- Never call `database.all()` repeatedly from Compose.
- Prefer a small number of aggregated SQL queries or one bounded immutable row snapshot processed on `Dispatchers.Default`.
- Do not create a persistent analytics cache unless a measured performance problem proves it necessary.
- Preserve acceptable performance with a realistically large log.
- No new database schema is expected. Add an index/schema migration only if it is clearly required and preserves all data.

## Scope filters

Support:

```text
Station:
- Current station/profile
- Specific station profile
- Specific station callsign
- All local data

Period:
- 30 days
- 90 days
- 12 months
- This calendar year
- All time

Band:
- All
- one selected band

Mode family:
- All
- CW
- Phone
- Digital
```

Rules:

- Award-style progress defaults to all time.
- Activity charts remember the last chosen period.
- Station/location-sensitive estimates default to the current station profile where available.
- Mode family normalisation reuses existing ShackCQ rules.
- Time is stored/calculated in UTC; activity charts may toggle UTC/local presentation without changing QSO identity.

---

# 7. Navigation and layout

Add a `Progress` destination.

## Entry points

- Expanded tablet NavigationRail: show **Progress** directly.
- Compact bottom navigation: do not add another item.
- Compact access:
  - Home → Progress card;
  - Logbook → Progress action;
  - Portable → Progress link where appropriate.
- Preserve existing bottom navigation and Back behaviour.

## Main sections

Use four clear sections:

```text
OVERVIEW
NEEDS
AWARDS
PORTABLE
```

Use tabs/segmented controls appropriate to current screen width.

Do not create five separate mini-apps.

## Wide tablet layout

Target Lenovo `TB373FU`:

```text
┌─────────────────────────────────────────────────────────────────┐
│ PROGRESS · station · period · worked/confirmed · data coverage │
├─────────────────────────────┬───────────────────────────────────┤
│ KPI / pinned goals          │ primary chart / map / matrix      │
│ quick filters               │                                   │
├─────────────────────────────┼───────────────────────────────────┤
│ next actions / needs        │ drill-down detail                 │
└─────────────────────────────┴───────────────────────────────────┘
```

## Compact layout

- one stable vertical scroll;
- compact filter row;
- horizontally scrollable KPI cards only where sensible;
- no nested scrolling traps;
- no horizontal overflow;
- large touch targets;
- useful empty states;
- no tiny diagnostic text as primary content.

Preserve the Flightline visual language.

---

# 8. Overview dashboard

Provide a concise operating dashboard.

## Core KPIs

At minimum:

- total QSOs;
- unique callsigns;
- unique DXCC identifiers worked;
- LoTW/QSL-confirmed DXCC identifiers;
- countries;
- grids;
- current-period QSOs;
- longest-distance QSO where valid;
- QRP QSOs at `<= 5 W` where TX power is known;
- pending/attention Sync Hub count.

Do not include a KPI that cannot be calculated honestly.

## Activity charts

Use native Compose/Canvas; no chart dependency.

Include:

1. **QSO activity trend**
   - day/week/month bucket chosen automatically for the range;
   - no misleading interpolation through missing periods.

2. **Band distribution**
   - useful bar chart;
   - tap to filter/drill down.

3. **Mode-family distribution**
   - CW / Phone / Digital / Other.

4. **Hour-of-day × day-of-week heatmap**
   - UTC/local toggle;
   - label the timezone.

5. **Distance distribution**
   - only when distance or valid grids exist;
   - show coverage;
   - no zero-distance substitution for missing data.

6. **Contact map**
   - reuse current MapLibre;
   - derive contact points from valid grids;
   - cluster broad views;
   - no new tile service;
   - list/statistics remain usable when tiles fail.

## Drill-down

Tapping a metric or chart segment opens:

- a bounded QSO list;
- relevant filter summary;
- action to open the existing Logbook with equivalent filters when supported.

Do not duplicate the full QSO editor.

---

# 9. Needs Board

The Needs Board is the actionable centre of this phase.

## Sections

### Live now

Match existing live activity against local needs without creating new network clients.

Use:

- existing DX/cluster live spots;
- existing POTA/WWFF Portable Chase live activity;
- SOTA live remains unavailable pending approval.

Identify, where data supports it:

```text
NEW DXCC ENTITY
NEW DXCC ON BAND
NEW DXCC MODE
NEEDED U.S. STATE
NEEDED CQ ZONE
NEW POTA PARK
NEW SOTA SUMMIT       — only from a future approved live source; unavailable now
NEW WWFF REFERENCE
PINNED GOAL MATCH
```

Rules:

- reuse current resolved spot fields and existing provider status;
- never infer a state/zone/reference from comments;
- never label an unresolved spot as a need;
- do not rewrite Neural DX or Portable ranking;
- calculate a small separate Needs priority with visible reasons;
- selection does not tune;
- `Open in DX` or `Open in Portable` transfers selection/filter to the existing workflow;
- existing Tune/Tune & Log confirmation remains authoritative;
- no transmission.

### Worked, not LoTW/QSL confirmed

Show:

- DXCC identifiers worked but lacking local LoTW/paper-QSL confirmation;
- U.S. states worked but unconfirmed;
- CQ zones worked but unconfirmed;
- last QSO date;
- bands/modes worked;
- available QRZ/eQSL confirmation indicators separately;
- open filtered Logbook action.

Do not request QSLs automatically.

### Band/mode gaps

Show compact matrices for:

- unique DXCC identifiers by band;
- unique DXCC identifiers by mode family;
- U.S. states by band where data exists;
- CQ zones by band.

Use worked and confirmed layers.

Do not attempt to list every unworked DXCC entity from CTY.DAT as an official missing list.

### Data quality

Show the most important missing metadata that limits progress:

```text
173 QSOs missing DXCC
88 QSOs missing CQ zone
241 QSOs missing grid
```

Provide a drill-down to those records.

Do not auto-edit them.

---

# 10. Local award-style estimates

Verify current official reference pages at implementation time:

```text
ARRL DXCC rules:
https://www.arrl.org/dxcc-rules

ARRL DXCC award information:
https://www.arrl.org/dxcc-award-information

ARRL Worked All States:
https://www.arrl.org/was

ARRL QRP DXCC:
https://www.arrl.org/qrp-dxcc

CQ Worked All Zones:
https://cq-amateur-radio.com/cq_awards/cq_waz_awards/index_cq_waz_award.html

POTA awards:
https://docs.pota.app/docs/awards.html
```

These pages define official programmes; ShackCQ displays local estimates only.

## 10.1 DXCC-style local estimate

Display:

- mixed worked;
- mixed LoTW/QSL confirmed;
- CW worked/confirmed;
- Phone worked/confirmed;
- Digital worked/confirmed;
- selected-band worked/confirmed;
- 100-entity milestone;
- 5-band matrix for 80/40/20/15/10 m.

Rules:

- count unique nonblank numeric/string DXCC identifiers stored or reliably resolved for display;
- do not combine different numeric entities because country names match;
- do not claim current/deleted official entity eligibility;
- 60 m may appear in general statistics but not as a DXCC band-award milestone;
- label every card `DXCC-STYLE LOCAL ESTIMATE`.

## 10.2 WAS-style local estimate

Display:

- 50-state worked count;
- LoTW/QSL-confirmed count;
- state grid/list;
- band/mode drill-down.

Rules:

- use the canonical 50 U.S. state abbreviations;
- do not count territories;
- do not guess state from callsign;
- do not count 60 m toward the award-style view;
- default to one station profile/location scope;
- do not claim official location compliance;
- label `WAS-STYLE LOCAL ESTIMATE`.

## 10.3 WAZ-style local estimate

Display:

- CQ zones 1–40 worked;
- LoTW/QSL-confirmed;
- zone grid;
- band/mode drill-down.

Rules:

- accept only stored/resolved zones 1–40;
- do not guess a missing zone;
- label `WAZ-STYLE LOCAL ESTIMATE`.

## 10.4 QRP DXCC-style local estimate

Display:

- unique DXCC identifiers worked with known `txPowerW <= 5`;
- QRP QSO count;
- data coverage for known TX power;
- 100-entity milestone.

Rules:

- unknown power does not count;
- zero is unknown unless current data semantics explicitly prove otherwise;
- no confirmation requirement for this local worked counter;
- label `QRP DXCC-STYLE LOCAL ESTIMATE`.

## 10.5 POTA local milestone preview

Implement only a useful bounded subset:

- unique parks hunted locally;
- unique parks activated locally;
- next standard unique-park milestone;
- P2P count;
- best local rover day by unique own parks;
- repeated contacts/activations for the most-used parks.

Verify the current standard POTA milestone levels from the official awards page.

Rules:

- local hunted counts come from `potaRef` / `potaRefs`;
- local activated counts come from `myPotaRef` / `myPotaRefs`;
- official hunter credit still depends on activator-submitted logs;
- official activator credit depends on accepted uploaded logs;
- do not mirror the entire POTA award catalogue;
- show `VERIFY IN POTA` browser handoff.

---

# 11. Portable analytics

Create a useful portable-performance view.

## Hunter progress

Show:

- unique POTA parks worked;
- unique SOTA summits worked;
- SOTA associations and regions represented by local QSOs where the downloaded summit catalogue can join them;
- unique WWFF references worked;
- P2P contacts;
- portable contacts by band/mode;
- portable contacts over time.

Do not calculate official SOTA points or WWFF award credit.

The full WWFF directory is unavailable by design, so do not show a false worldwide denominator.

## Activator progress

Derive from persisted QSO metadata:

```text
activationSessionId
activationProgram
myPotaRef
myPotaRefs
createdAt
band/mode
distance
station profile
radio model
antenna path
txPowerW
```

Show:

- distinct local POTA activation sessions with QSOs;
- unique own parks;
- own park × UTC-day activation attempts;
- local successful activation count using `>= 10` QSOs per own park and UTC day;
- QSO totals;
- unique calls;
- P2P totals;
- average and best QSO rate;
- activation duration from first to last QSO;
- best distance where data exists;
- bands/modes;
- station profile/radio/antenna/power comparisons;
- best activation;
- recent activation list.

Rules:

- multi-park operation counts each own reference for reference progress but does not duplicate the underlying local QSO total;
- one QSO in a session cannot produce a meaningful rate/duration;
- empty/abandoned sessions without retained QSOs are not invented;
- `>= 10` is a local threshold estimate, not official accepted activation status;
- older QSOs with `myPotaRef` but no session ID may contribute to unique activated parks, but not fabricated session-performance metrics;
- show coverage when antenna/power/distance data is incomplete.

## Portable maps

- POTA own/hunted references may join the downloaded POTA catalogue;
- SOTA hunted references may join the downloaded summit catalogue;
- WWFF references remain list-only unless QSO grid data provides a contact location;
- no full WWFF directory download;
- no provider logos.

---

# 12. Pinned goals

Add a small, deliberately bounded goal feature.

Users may pin up to four goals from predefined metrics:

```text
Total QSOs
Unique DXCC-style worked
LoTW/QSL-confirmed DXCC-style
QRP DXCC-style worked
POTA parks hunted locally
POTA parks activated locally
Successful local POTA activations
P2P QSOs
SOTA summits worked locally
WWFF references worked locally
U.S. states worked locally
CQ zones worked locally
```

Each goal has:

- metric type;
- integer target;
- optional band;
- optional mode family;
- optional deadline;
- name/label.

Store app-private JSON/preferences atomically.

Do not create formulas, scripting, notifications, social sharing or cloud sync.

Show:

- current/target;
- percentage;
- remaining count;
- pace only when a deadline and enough historical data exist;
- `Open needs` action where relevant.

A goal is personal, not an official award claim.

---

# 13. Integration with existing screens

## DX

Where current DX cards already have entity/state/zone data, add restrained need chips such as:

```text
DXCC NEEDED
NEW ON 20M
STATE NEEDED
ZONE NEEDED
```

Do not rewrite the Neural DX ranking engine.

Do not add chips when data is unresolved.

## Portable Chase

Preserve existing programme-specific worked labels.

A pinned-goal match may add one small reason chip.

Do not change provider polling or SOTA approval behaviour.

## Logbook

Add:

```text
Progress
```

entry and drill-down filter handoffs.

Do not overload log rows with all progress state.

## Sync Hub

Overview may show:

- queued;
- attention;
- accepted delivery counts.

Keep upload acceptance separate from confirmation.

## Home

Add one useful compact Progress card:

- primary pinned goal;
- one live-needed opportunity count;
- open Progress.

Do not redesign the full Home screen.

---

# 14. Empty and partial data experience

The owner’s current Lenovo log may contain no QSOs.

The production UI must remain polished with zero data:

- explain what will appear after the first QSO or Wavelog sync;
- show available live needs only when worked-state data permits;
- do not display fake chart bars, sample maps or placeholder achievements;
- keep filters and navigation functional;
- provide direct actions to Logbook, Wavelog setup and Portable Chase.

Sample data may exist only in Compose previews and tests, clearly isolated from runtime.

---

# 15. Security and privacy

This feature is local.

- No new network endpoint.
- No analytics/telemetry.
- No external AI.
- No QSO data upload beyond providers already explicitly configured in Phase 4A.
- No secret access is required.
- Do not include callsigns/QSO details in new support exports unless the existing operator explicitly requests that export.
- Do not expose private provider messages or credentials in screenshots.

---

# 16. Minimal focused validation

Do not expand unrelated test coverage.

Add compact deterministic tests for:

1. station/time/band/mode filtering;
2. mode-family normalisation;
3. worked vs LoTW/QSL-confirmed semantics;
4. QRZ/eQSL indicators kept separate from award-style confirmation;
5. DXCC-style unique counts and 100 milestone;
6. 60 m excluded from DXCC award-style band progress;
7. WAS 50-state set, territories excluded and unknown state not guessed;
8. WAZ zones 1–40;
9. QRP `<= 5 W`, unknown power excluded;
10. POTA hunter vs activator reference direction;
11. multi-own-park reference counts without duplicate QSO totals;
12. local successful POTA activation by own reference and UTC day;
13. P2P count;
14. older `myPotaRef` contribution without fabricated session metrics;
15. SOTA catalogue join for association/region;
16. WWFF no-directory denominator rule;
17. live-needs matching and unresolved-data exclusion;
18. pinned goal round-trip and target progress;
19. data-coverage denominator;
20. SOTA live remains approval-blocked;
21. no new network request from Progress.

Keep tests focused on rules, not every composable or chart pixel.

Final Android gate:

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Run shared CTest only if shared C++ changes.

Do not run Apple builds unless Apple/shared ABI files change.

Do not require `lintDebug`; fix only obvious new issues in touched files.

Connected instrumentation is optional unless needed to prove preferences/database behaviour. Do not add tests for count inflation.

---

# 17. Lenovo smoke run

When Lenovo `TB373FU` is available:

1. Build the final APK.
2. Install with:

   ```bash
   adb install -r <apk>
   ```

3. Preserve:
   - QSOs;
   - delivery rows;
   - provider credentials;
   - POTA/SOTA catalogues;
   - activation state/history;
   - EQ profiles;
   - voice recordings;
   - settings.
4. Open Progress from Home and Logbook.
5. Exercise:
   - Overview;
   - Needs;
   - Awards;
   - Portable;
   - station/time filters;
   - empty or real-data states;
   - Sync Hub handoff;
   - DX/Portable deep links.
6. Confirm existing Portable Chase, POTA Activate and Sync Hub still open.
7. Confirm SOTA live still says approval required and makes no request.
8. Background/resume once.
9. Do not import or create fake QSOs.
10. Do not upload a provider record.
11. Do not transmit.
12. If genuine real QSO data already exists, verify calculated values against a small manual sample; otherwise record the zero-data experience honestly.

Capture only:

```text
progress-overview.png
progress-needs.png
progress-awards.png
progress-portable.png
```

No large evidence campaign.

---

# 18. Documentation

Create one concise document:

```text
docs/PROGRESS_INTELLIGENCE_ANDROID.md
```

Include:

- purpose;
- data sources;
- filters;
- confirmation semantics;
- local-estimate disclaimer;
- Needs Board;
- award-style metrics;
- portable analytics;
- pinned goals;
- live-screen integration;
- missing-data behaviour;
- validation summary;
- screenshots;
- exclusions.

Update only current truth where needed:

```text
README.md
PRODUCT.md
DESIGN.md
docs/ROADMAP.md
NOTICE
```

Do not rewrite historical Phase 0–4A evidence.

Roadmap status after pass:

- Phase 4B Progress Intelligence implemented on Android;
- award-like values are local estimates only;
- official confirmation/credit imports remain later;
- direct LoTW signing remains deferred;
- SOTA live still requires written approval;
- iPadOS parity remains deferred;
- no Nexus source incorporated;
- Phase 5A FlexRadio SmartLink is the next major feature.

Do not create multiple reports or another maintenance gate.

---

# 19. Acceptance

Use:

```text
PASS
PASS WITH NOTES
STOPPED
```

## PASS

- Progress workspace is polished and responsive;
- local truth/official-status distinction is explicit;
- Overview metrics and charts are correct;
- Needs Board uses existing live sources and has useful deep links;
- DXCC/WAS/WAZ/QRP/POTA local estimates follow documented bounded rules;
- portable hunter/activator analytics are correct;
- multi-park/P2P counting is correct;
- confirmation and upload states are not conflated;
- missing data is visible;
- pinned goals work;
- no new network client exists;
- Android tests/build pass;
- APK installs with data preserved;
- no fake QSO, provider upload or RF transmission is used;
- documentation is concise;
- branch is merged and pushed cleanly.

## PASS WITH NOTES

Acceptable notes:

- the Lenovo has no real QSOs, so runtime smoke proves polished empty states while deterministic tests prove calculations;
- some real log rows lack DXCC/grid/power data and coverage is shown honestly;
- no authenticated official award/confirmation service is connected because that is intentionally outside scope;
- live SOTA opportunities remain unavailable pending approval.

These do not block merge.

## STOPPED

Do not merge if:

- any local estimate is presented as official credit;
- QRZ/Club Log/eQSL upload acceptance is counted as award confirmation;
- multi-park QSOs are double-counted in total-QSO statistics;
- incomplete metadata is treated as zero/negative evidence;
- live Needs creates a new polling client or bypasses current provider limits;
- SOTA unapproved API is called;
- runtime contains fake QSOs/statistics;
- existing data is lost;
- UI is placeholder or materially unusable;
- tests/build fail because of this task;
- unrelated owner work would be overwritten.

---

# 20. Git and merge authority

Use focused commits, for example:

```text
feat(android): add Progress Intelligence dashboard
feat(android): add Needs Board and local award estimates
docs: document Android progress intelligence
```

Push the feature branch.

The owner authorises merge into `main` on `PASS` or genuine `PASS WITH NOTES`.

Before merge:

- fetch `origin`;
- integrate latest `origin/main` normally if required;
- rerun the final Android test/build;
- confirm smoke evidence corresponds to final code;
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

# 21. Required final response

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

- navigation;
- Overview;
- Needs Board;
- Awards;
- Portable analytics;
- pinned goals;
- deep links;
- key UI decisions.

### Calculation truth

State:

- worked definition;
- confirmed definition;
- local-estimate disclaimer;
- missing-data coverage;
- POTA/multi-park/P2P counting;
- SOTA/WWFF limitations.

### Validation

- focused test result;
- full Android unit/build result;
- APK path/version/size/SHA-256;
- install/data-preservation result;
- four screenshot paths;
- device data status;
- explicit statements:

```text
No fake QSO or statistic was inserted into production data.
No provider upload was performed.
No RF transmission was performed.
```

### Known limitations

Only actual remaining limitations.

### Documentation

Link:

```text
docs/PROGRESS_INTELLIGENCE_ANDROID.md
```

Do not begin FlexRadio, LoTW, SOTA API integration, iPadOS, desktop or another maintenance phase in this task.
