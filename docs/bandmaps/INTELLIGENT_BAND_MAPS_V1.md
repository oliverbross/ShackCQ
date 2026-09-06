# ShackCQ Intelligent Band Maps v1

## Sweep 2 renderer amendment

V2 replaces the label-box presentation with frequency-anchored classic lanes, callsign-first default labels, focal zoom, leader lines and deterministic stacks. The persisted Radio setting now renders the same snapshot/controller as a compact active-band vertical ladder with RX/TX/split truth. Contest S&P is inactive outside a live Contest session. See `../BAND_MAPS_V2_OPERATOR_GUIDE.md`.

## Sweep 1 interaction and presentation

The operator-visible selector is restricted to 160m through 23cm. All four layouts show labelled frequency axes and reviewed Region 1/2/3 operating guidance explicitly marked non-regulatory. Per-band viewports support bounded pinch, wheel/trackpad, buttons, double-click/tap, drag and keyboard zoom/pan with safe persistence/reset. Dense labels use six deterministic lanes plus bounded exact-member stacks; Grid and Single layouts preserve selected frequency, while Single adds RX, TX/split and passband markers. Shared CS/DS configured tokens drive status colour and text.

## Frozen source and platform boundary

Android Band Maps v1 was developed on `feature/intelligent-bandmaps-v1` in the isolated worktree
`/Users/oliver/Documents/Projects/ShackCQ/shackcq-intelligent-bandmaps-v1` from the immutable integrated commit
`98490b6d5234c3f12cc5d00bbea3163c8273c3dc` (`integration/keyer-contest-dxchaser-v1`). At task start,
`origin/main` was `b4f12e17fa87df16d2094b518ae187553e370be5`; the frozen base did not equal `origin/main`.

Verified integrated ancestry:

| Authority | Source SHA | Semantic merge SHA |
|---|---|---|
| Keyer and physical hotkeys | `ecba146f064e57e7ebb8a48d897b9ad4bb4cdf43` | `0bf3aa33e208b01f0fa7868cff06f4c2c21caed7` |
| Contest and N1MM | `d3f2a3b1f182a98d442e97182aa91e5c873f0e67` | `727a5f8b6a550d983302bc2f961a24c929cb5bcd` |
| DX Chaser | `b30ee05ad9231627afc9854e7182555ef229f50c` | `606e343d21dd6c0d58a42c5a3c4f7498702400f0` |

The QSO schema remains version 13 and the indexed QSO projection remains version 2. Contest opportunity contract
version 1 and DX Chaser read-only contract version 1 (`ShackCQ DX Chaser Score v1`) are consumed unchanged. No QSO,
Neural, Digi, Groups.io, Contest or Chaser schema was changed.

This delivery is Android-only. iOS, iPadOS, macOS, Windows and Linux Band Maps remain later phases. No Apple or
desktop source was edited.

## Integrated contract audit

The frozen `docs/bandmap/INTEGRATED_INPUT_CONTRACT_V1.md` and actual Kotlin declarations were inspected before
production changes. Band Maps uses these existing signatures:

```text
ContestReadOnlyPort.snapshot(): ContestReadOnlySnapshot
ContestRuntime.opportunity(callsign: String, band: String, mode: String): ContestOpportunityState?
DxChaserReadOnlyPort.snapshot(): DxChaserReadOnlySnapshot
KeyerDispatchPort.availability(context: KeyerContextSnapshot): KeyerAvailability  [read outside Band Maps]
KeyerDispatchPort.snapshot(): KeyerQueueSnapshot                                [read outside Band Maps]
WorkspaceActionRouter.resolve(action: WorkspaceAction): WorkspaceRoute
OperatingContextSnapshot(generation, station, radio, network, database and provider context fields)
```

`KeyerDispatchPort` is not imported or callable from the Band Map package. Main activity supplies immutable
`KeyerQueueSnapshot` and `KeyerAvailability` values. `DxChaserRuntime.snapshot` is consumed as immutable data; Band
Maps does not receive the Chaser controller, engine, store or action port.

## Source ownership audit

| Truth or capability | Existing sole owner | Band Map use |
|---|---|---|
| DX cluster socket, parsing and spot retention | `FeatureController` plus native feature core | `liveSpots` immutable list |
| RBN evidence | `FeatureController` / HamClock typed RBN path | bounded `rbnObservations` and source state |
| PSK Reporter and personal WSPR | `HamClockPublicProviders` through `NeuralDxController` | immutable `SignalReport` snapshots |
| POTA | `PotaController` | immutable POTA spot snapshot converted through existing `toPortable()` |
| SOTA and WWFF | `PortableController` | immutable `PortableSpot` snapshots |
| Neural current opportunities and history | `NeuralDxController` / `NeuralDxStore` | existing current evidence only; no direct journal access |
| CTY/entity lookup | `CtyController` | memoisation-friendly `lookup()` callback |
| Worked/confirmed/Needs truth | canonical QSO DB and `QsoProjectionStore` | indexed, station-scoped compact snapshot |
| QSO mutation | `QsoMutationCoordinator` | no Band Map mutation path |
| Contest rules and N1MM claims | `ContestRuntime`, evaluator and N1MM owner | side-effect-free opportunity and immutable snapshot |
| DX Chaser score/session/database | `DxChaserRuntime` and its internal owners | read-only adapter only |
| Keyer profile, queue and F1-F12 focus | `AndroidKeyerRuntime`, `KeyerController`, `KeyerProfileStore` | queue/availability display only |
| Radio/CAT and RX/TX truth | radio backends and `OperatingContextAuthority` | truthful markers/context; no CAT owner |
| Reviewed navigation/receive preparation | `WorkspaceActionRouter` and existing home receive review | typed action; CAT remains outside Band Maps |
| Configuration backup/recovery | `ConfigurationRecovery` | `band_maps` preference section |
| Support ZIP sanitisation | `SanitizedSupportBundle` | bounded aggregate diagnostics only |

The final architecture leaves each network feed and every transmit-capable path with one owner.

## Architecture and bounded data flow

`BandMapSourceAdapters` translate existing immutable source rows into observations without mutating upstream data.
`BandMapSpotCanonicalizer` normalises calls, bands and mode families. `BandMapSpotIndex` caps input at 20,000 rows and
uses callsign/band/mode/frequency buckets for deterministic source-aware coalescing. All contributing observations are
retained in each canonical spot.

`BandMapNeedsProjection` issues station-scoped `SELECT DISTINCT` queries against indexed `qso_projection` and
`qso_reference` dimensions. Every dimension is capped at 20,000 distinct values, exposes truncation, never selects QSO
payloads or `details_json`, and never materialises canonical QSO rows in a normal map refresh.

`BandMapController` debounces rapid updates, cancels superseded coroutine generations, rebuilds on a background
dispatcher and publishes only the latest immutable snapshot. `close()` is idempotent and cancels local work without
closing any upstream owner. It retains no historical spot database.

`BandMapFilterEngine`, `BandMapPriorityEngine` and `BandMapLayoutEngine` are pure. Filtering never changes scores;
ranking exposes every component and penalty. The UI never displays a synthetic workability percentage.

## Canonicalisation, coalescing and ageing

- Callsigns are case/whitespace normalised while meaningful `/P`, `/MM`, `/QRP` and other suffixes remain part of identity.
- All integrated canonical bands from 2190 m through 1 mm can be selected; `2200m` maps to canonical `2190m`.
- Source-reported mode/submode remains explicit. Unknown mode remains unknown rather than being invented from frequency.
- Coalescing key: normalised call, canonical band, compatible mode family, source-aware frequency tolerance and a
  bounded 15-minute observation window.
- Initial tolerances: Digi 80 Hz, CW 400 Hz, unknown 1 kHz, phone 2.5 kHz, FM/AM 5 kHz.
- Current/aging/stale/expired thresholds are source-aware. RBN is shortest-lived; PSK/WSPR and portable observations
  remain current longer. Pins retain local visibility only and never alter the observation timestamp.

## Needs, Contest, Chaser, Keyer and evidence truth

Needs dimensions remain independent: entity, band, mode, band-mode slot, grid, CQ zone, ITU zone, WPX and portable
reference. Missing or incomplete projection/CTY data remains `UNKNOWN` with a reason. Station profile or callsign is part
of every projection snapshot and operating-context changes invalidate controller generations.

Contest visuals consume `ContestRuntime.opportunity()` and the read-only session snapshot. No rule engine, serial
reservation, session mutation or N1MM socket exists in Band Maps.

The Chaser adapter maps only supplied eligibility, priority tier/score, reasons, penalties, selected/engaged target,
cooldown and evidence/outlook labels. A missing candidate is `CHASER UNAVAILABLE`; it does not hide ordinary spots by
default. Band Map rank and Chaser-supplied rank remain separate.

Current observed evidence, empirical outlook and historical personal context are distinct typed channels. Unlike
signals are never collapsed into a probability. Provider unavailability remains visible and does not silently remove all
spots.

## Layout, filters, ranking and safe actions

Delivered layouts are multi-band vertical frequency lanes, multi-band horizontal frequency maps, adaptive grid overview
and single-band expanded view. Deterministic ticks, direction transforms, bounded collision lanes and stack counts are
computed independently of Compose. Tablet, smaller-screen and phone layouts use adaptive grids, scrolling lanes and a
phone navigation fallback. Essential actions do not require hover.

Built-in presets are editable data: All current, Needed DX, Contest S&P, DX Chaser context, Portable activators, RF
evidence now and Watchlist. Filters cover bands/segments, modes, sources, age, source diversity, independent spotters,
spotter/target continent, Needs, Contest, Chaser, portable programme, evidence status, text and unknown/stale truth.

Ranking components include watch/pin, Need dimensions, Contest multiplier/non-dupe/duplicate, bounded Chaser priority,
current evidence, empirical outlook, source diversity, freshness and stale penalties. Values are limited to -100..100 and
the detail surface explains the strongest components.

Selection, traversal, filters, presets, marks and restore issue no CAT. Only `REVIEW RX` includes a frequency and it is
resolved through `WorkspaceActionRouter` into the existing receive-review surface. DX details, Logbook history and Open
Chaser are navigation actions only. Context-generation mismatches reject actions. The package has no PTT, Keyer dispatch,
Digi TX, Chaser start/select/accept, logging or direct radio method.

## Persistence and recovery

`BandMapStateStore` persists feature/navigation visibility, layout, band order, presets, bounded weights, palette,
traversal, read-only context preferences and up to 1,000 local watch/pin/hide records. Live spots, Keyer state, Chaser
state, provider bodies, credentials and transmit state are not persisted. JSON is schema-validated before an atomic
SharedPreferences commit; a malformed current document recovers from `document_last_good`. The `band_maps` section is
included in normal configuration export/transactional restore. Restore code has no runtime-action dependency.

## Remaining gaps

- Apple and desktop clients have no Band Maps UI.
- Physical touch, keyboard, screen-reader, rotation and colour assessment remain device validation.
- RX/TX/passband markers render only when authoritative integration fields are wired into the lane scene; no TX marker is fabricated.
- Authenticated/live provider completeness depends on the existing configured owners and was not exercised by this task.
