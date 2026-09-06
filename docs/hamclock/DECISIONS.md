# OpenHamClock integration decisions

These decisions apply to the ShackCQ OpenHamClock completion programme unless a later owner-approved decision supersedes them.

## Native clients, no embedded React

Android uses Jetpack Compose and iOS/iPadOS uses SwiftUI. ShackCQ will not embed the upstream React application in a WebView, ship its Express server, or download/execute its JavaScript at runtime.

## Behaviour parity, not pixel cloning

Parity is judged by truthful operator outcomes. OpenHamClock layout density and information hierarchy may inform the native design, but upstream pixels, logos, artwork, trade dress and React components are not cloned.

## ShackCQ workspaces remain authoritative

Radio, Digi, DX, Portable, Operations, Satellite, Logbook, Wavelog and Log Intelligence retain ownership of their established outcomes. Home summarizes or delegates to them rather than creating weaker duplicate implementations.

## No shared OpenHamClock DXSpider proxy

ShackCQ keeps its user-configured DX-cluster connection and does not adopt the upstream shared DXSpider proxy. Existing session identity, persistence and operator configuration remain authoritative.

## No runtime source-code updating

Installed clients and workflows never download and execute upstream source. The watcher reads GitHub metadata/content only and never changes ShackCQ source or pins automatically.

## Stable updates require review

A stable `main` change may open/update one audit issue and may lead to a separately reviewed implementation branch. It never auto-merges adapted code.

## Preview is report-only

`Staging` is a separate, potentially divergent preview channel. Preview changes are reported only; only security or provider-contract preview changes may open/update the audit issue.

## No unattended tune, TX or PTT

Home remains observational except for existing explicit, bounded, operator-initiated controls. No provider result, watcher result, timer or background refresh may tune a radio, arm transmission, key PTT, or retry a transmit-capable action unattended.

## Typed Home registries own presentation

One module registry owns default layout, migration completion, configuration labels, wide/compact rendering metadata, deep-link ownership and unavailable explanations. One layer registry owns map defaults, source/layer IDs, bounds, source labels, selection actions and low-data representations. Unknown imported IDs are preserved, rendered as unavailable and removable rather than crashing or being silently discarded.

## Persistent lawful map and text parity

Android Home owns one lifecycle-forwarded MapLibre view whose style may change without recreating the view. DARK and LIGHT currently use attributed OpenFreeMap network styles; no key or demo endpoint is committed. SATELLITE and TERRAIN remain explicitly unavailable. User pan disables follow, gesture camera writes debounce and merge into the latest non-camera settings, newer profile/camera state cancels a late write, reset returns to the configured station, and style failure/low-data mode uses the same bounded Map Data snapshot without tile work.

## Manual target and workspace actions

Manual targets resolve through configured callbook data, Maidenhead geometry and CTY fallback, persist source/lock/clear state, and feed both selected-path geometry and propagation. A locked manual target blocks automatic DX replacement. Marker/module actions deep-link only to the existing DX, Portable, Operations/Satellite, Logbook, Progress, Radio or Digi workspaces; they never issue CAT commands.

Task 2A1 retains exact marker identity across the hand-off: spot ID/callsign/frequency/mode, portable spot ID, NORAD, QSO ID and target context. Satellite positions are calculated only by `SatelliteOperationsController` through the pinned `NativeSatellite` SGP4 engine. Home frequency actions and destination frequency actions converge on one receive-only review dialog; confirmation may change RX frequency/mode but cannot key PTT or start TUNE.

Task 2A2 narrows that decision: the shared application `send` callback is the established Radio/Preset transport and must never be intercepted by Home review. Only explicitly reviewed Home actions and the Operations satellite receive preview request the receive-only dialog. Home-launched DX detail retains that review requirement after exact navigation; normal Radio controls remain direct operator controls. General compound commands retain all fields.

Home satellite presentation has one authority: `SatelliteOperationsController`. Its Home lifecycle is foreground/visibility-scoped, uses a separate station/Wavelog observer input, reuses validated elements, propagates locally at 45-second cadence, serializes calculations and caps output at 40. The legacy Neural DX satellite provider/ticker remains available only to `FULL_DX`; `HOME` refresh explicitly excludes it.

Exact map routing uses distinct DX and PSK identities. Visible layer health counts come from the complete registry status map. Watchlist is a secondary marker property and never replaces band colour. A current-generation late style callback clears timeout state; obsolete callbacks remain ignored. Home unit selection covers distance/altitude surfaces, while density remains partial and is labelled Layout density until typography and controls share one complete metric model.

## One DX News and PSK authority

Task 2B1 uses `DxNewsRepository` and `PskReporterRepository` from `HamClockPublicProviders` as the sole native network authorities. DX News merges DX-World RSS with the already-owned NG3K schedule; it does not create another NG3K request. DXNews.com remains explicitly unavailable until a stable direct structured contract can be verified. PSK Reporter uses its direct public query contract in both sender and receiver directions; mutual is derived locally from the same remote callsign and band within the active window.

Home cards deep-link once to the exact native DX Briefing or My Signal map. News and PSK details may open external articles, show batch log/intelligence context, history and watchlist, or request the established receive-only review. They never tune from a list/article/report click and never issue CAT directly. Disabling PSK cancels work, clears display state and generation-rejects late completions.

The former Immersive Home label was overstated. It is now Minimal Home, hides only the Operations summary and remains `PARTIAL` in settings truth. `RESET PANELS` resets only the panel layout and never deletes named profiles.

## Retail RBN, shared personal WSPR and schedule-only IBP

Task 2B2 deliberately avoids a second socket and a second PSK transport. RBN classification runs before the existing cluster parser but consumes the same configured retail connection and respects the app-scoped Home preference. It never opens the official raw RBN firehose.

Personal WSPR delegates to `PskReporterRepository` with `mode=WSPR`. Regional WSPR.live remains a visible, disabled `UNAVAILABLE_POLICY` explanation until explicit owner approval changes the provider decision; no request or active toggle is exposed.

IBP uses a local 18-site manifest reviewed against NCDXF/IARU references. The five simultaneous scheduled transmissions are calculated locally from 10-second slots in a 180-second cycle. Schedule state and heard evidence remain separate types and labels.

Band Health is explainable evidence, not an oracle. It caps repeated contributors, exposes source diversity/confidence/reasons, and uses `NO LIVE EVIDENCE` when selected providers are unavailable. It must never infer `CLOSED`.

Task 2B2A corrects “source diversity” to separate source count, call diversity and receiver diversity. QSO projection is historical comparison only, and cross-source copies of one event count once. RBN paths are observed DX-to-skimmer paths; unresolved endpoints are not replaced by DE geometry. PSK/WSPR directions remain independent, so one available and one unavailable direction is `DEGRADED`. IBP schedule rows remain reference data while cluster/RBN matches are explicitly observed evidence.

Task 2B2B makes the RBN point the skimmer receiver, uses the current station identity for `WHO_HEARS_ME`, periodically expires quiet-feed rows and derives typed freshness only from RBN observations. Cached callbook grids precede approximate CTY centroids. Personal PSK/WSPR geometry changes are local reprojections with no request. One application-scoped Band Health snapshot feeds Home, DX RF Evidence and Log Intelligence; indexed projection aggregates are separate historical context and cannot make live evidence available. `STALE EVIDENCE` and `DEGRADED SOURCES` reduce confidence explicitly. Log Intelligence labels this context operational live evidence, not forecast or award credit.

## Provenance and licence

The audited upstream is `accius/openhamclock` at immutable stable commit `d4a50eaaa61d3432a1de5f80cbe61790739930a5`, MIT, Copyright 2024-2026 OpenHamClock Contributors. Repository attribution is retained in `NOTICE`. No upstream implementation code is copied by this phase.

## Finish-line Phase 1 decisions

- Do not vendor ITU-R-HF v14.3. Its headers both permit implementer use free from copyright assertions and prohibit reproduction without written ITU permission. That conflict is not an unambiguous redistribution grant.
- Keep the independently written C++/JNI adapter truthful: it validates the complete bounded input contract and returns `LICENSE_BLOCKED`, never heuristic P.533 output.
- Keep the reviewed OpenHamClock REST current-band adapter as fallback and label it as remote/current-hour only. Do not present it as a native 24-hour matrix or scientific reference.
- Reuse the authoritative Satellite Operations controller. Home adds rendering only; it does not create a second element cache or propagator.
- Use official NOAA/NASA endpoints with byte, row, geometry, cadence and last-good bounds. Missing measurements remain missing.
- Query contest QSOs from the indexed projection and selected contest window. Geometry-free QSOs stay in the list and are never assigned a fabricated point.
- The ID timer is a local reminder driven by explicit start or a verified CAT TX transition. `ID SENT` is an operator assertion; the app neither decodes an ID nor transmits one.
- Shack display is a full-window Compose dialog with a persistent exit, lifecycle-restored system bars, optional keep-screen-on, reduced motion, and touch-paused rotation. It reuses Home state and providers.
## Empirical outlook

The future-window feature is named `ShackCQ Empirical Outlook v1`, not AI probability, VOACAP or P.533. One application-scoped controller receives existing owner snapshots; it cannot fetch, tune, transmit, spot or log. Current Band Health and future outlook remain separate scores. QSO history and calendar/watchlist/Needs are context/value ranking only. Percentages remain hidden until the 40-family/15-bin calibration gate passes. Local P.533 remains `LICENSE_BLOCKED`.
