# OpenHamClock parity ledger

Audited against `accius/openhamclock` stable `main` at `d4a50eaaa61d3432a1de5f80cbe61790739930a5` (26.5.0) on 2026-08-20. Status means an operator-visible Android outcome, not the existence of a model, enum, provider, document, or test. Dedicated ShackCQ workspaces remain authoritative.

Status vocabulary: `NATIVE` is a working native Home outcome; `PARTIAL` is useful but lacks stable behaviour; `DELEGATED` routes to a stronger native ShackCQ workspace; `MISSING` is not available; `EXCLUDED` is intentionally outside ShackCQ Home.

## Stable panels

All IDs are the keys returned by `panelDefs` in upstream `src/DockableApp.jsx`. Conditional stable panels (`rotator`, `ambient`) remain inventoried.

| Upstream id / name | Upstream paths | Operator outcome | ShackCQ status | ShackCQ owner/files | Provider owner | Notes / gaps |
|---|---|---|---|---|---|---|
| `world-map` / World Map | `src/DockableApp.jsx`, `src/components/WorldMap.jsx` | Map-first RF activity view | NATIVE | `HamClockHomeMap.kt`, `HamClockRegistries.kt` | ShackCQ controllers | Persistent lifecycle-managed MapLibre view; DARK and LIGHT use attributed OpenFreeMap network styles, with bounded GeoJSON layers and guarded camera persistence. |
| `map-list-view` / Map Data (text view) | `src/DockableApp.jsx`, `src/components/MapDataListView.jsx` | Accessible text equivalent of map layers | NATIVE | `HamClockHomeMap.kt` | Same native snapshots | Low-data and map-failure paths expose the same visible bounded layers and actions without tile work. |
| `de-location` / DE Location | `src/DockableApp.jsx` | Station identity and position | NATIVE | `HamClockHomeScreen.kt`, `AppController.kt`, `WavelogController.kt` | ShackCQ local/Wavelog | Live station identity; no upstream component embedded. |
| `dx-location` / DX Target | `src/DockableApp.jsx` | Current DX target and path | NATIVE | `HamClockHomeScreen.kt`, `HamClockHomeMap.kt` | Callbook + CTY + DX cluster | Persisted manual target, grid/coordinate resolution, lock and clear; locked manual state blocks automatic replacement. |
| `analog-clock` / Analog Clock | `src/DockableApp.jsx`, `src/components/AnalogClockPanel.jsx` | Analogue clock face | NATIVE | `HamClockAnalogClock.kt` | Local time calculation | Optional registry module; UTC/local selection follows Home display preferences. |
| `solar` / Solar (all views) | `src/DockableApp.jsx`, `src/components/SolarPanel.jsx` | Solar condition overview | PARTIAL | `HamClockHomeScreen.kt`, `FeatureController.kt` | NOAA SWPC + local astronomy | Core indices/X-ray/celestial truth; not every upstream view. |
| `solar-image` / Solar Image | `src/DockableApp.jsx`, `src/components/SolarPanel.jsx` | Current SDO imagery | MISSING | `SolarCelestialProvider.kt` | NASA SDO metadata | Metadata exists; Home does not render image UI. |
| `solar-indices` / Solar Indices | `src/DockableApp.jsx`, `src/components/SolarPanel.jsx` | SFI/A/Kp indices | NATIVE | `HamClockHomeScreen.kt`, `FeatureController.kt` | NOAA SWPC | Visible live/cached truth. |
| `solar-xray` / X-Ray Flux | `src/DockableApp.jsx`, `src/components/SolarPanel.jsx` | GOES X-ray state | NATIVE | `SolarCelestialProvider.kt`, `HamClockHomeScreen.kt` | NOAA SWPC GOES | Validated feed with last-good state. |
| `lunar` / Lunar Phase | `src/DockableApp.jsx`, `src/components/LunarPanel.jsx` | Moon phase/timing | NATIVE | `SolarCelestialProvider.kt`, `HamClockHomeScreen.kt` | Local astronomy | Offline calculation; no upstream image dependency. |
| `propagation` / Propagation (all views) | `src/DockableApp.jsx`, `src/components/PropagationPanel.jsx` | Path prediction overview | PARTIAL | `HamClockPropagationRepository.kt`, `HamClockHomeScreen.kt` | openhamclock.com public propagation API | Schema-validated live/cache/fallback state; provider dependency remains. |
| `propagation-chart` / VOACAP Chart | `src/DockableApp.jsx`, `src/components/PropagationPanel.jsx` | Time/band prediction chart | PARTIAL | `HamClockHomeScreen.kt` | openhamclock.com | Current path/band snapshot, not a full chart. |
| `propagation-bars` / VOACAP Bars | `src/DockableApp.jsx`, `src/components/PropagationPanel.jsx` | Per-band reliability | NATIVE | `HamClockHomeScreen.kt`, `HamClockPropagationRepository.kt` | openhamclock.com | Bounded per-band reliability/status rows. |
| `band-conditions` / Band Conditions | `src/DockableApp.jsx`, `src/components/BandConditionsPanel.jsx` | Current usable-band summary | NATIVE | `HamClockHomeScreen.kt`, `NeuralDxController.kt` | ShackCQ measured data | Behaviour parity from native RF evidence. |
| `band-health` / Band Health | `src/DockableApp.jsx`, `src/components/BandHealthPanel.jsx` | Provider health by band | NATIVE | `HamClockRfEvidence.kt`, `HamClockHomeScreen.kt`, `NeuralDxScreen.kt`, `ProgressScreen.kt` | ShackCQ measured evidence | One shared immutable live snapshot; indexed `qso_projection` aggregates are historical comparison only. Stale/degraded sources are explicit and the reducer never reports `CLOSED`. |
| `band-activity` / Band Activity | `src/DockableApp.jsx`, `src/components/BandActivityHeatmap.jsx` | Activity counts/heat | NATIVE | `HamClockHomeScreen.kt`, `NeuralDxController.kt` | ShackCQ measured data | Native activity summary. |
| `ibp` / IBP Beacons | `src/DockableApp.jsx`, `src/components/IBPPanel.jsx` | Current beacon schedule | NATIVE | `HamClockRfEvidence.kt`, `HamClockHomeMap.kt`, `HamClockHomeScreen.kt` | NCDXF/IARU local manifest | Versioned/hash-recorded 18-site manifest, five bands, 10-second slots and 180-second cycle; schedule is explicitly not heard evidence. |
| `dx-cluster` / DX Cluster | `src/DockableApp.jsx`, `src/components/DXClusterPanel.jsx` | Live DX spots | NATIVE | `HamClockHomeScreen.kt`, `FeatureController.kt` | User-configured DX cluster | Home summary plus authoritative DX workspace. |
| `dx-news-ticker` / DX News | `src/components/DXNewsTicker.jsx`, `server/routes/dxNewsSources/*`, `server/utils/dxNewsMerge.js` | Source-attributed current/upcoming DX news | NATIVE | `HamClockHomeScreen.kt`, `NeuralDxScreen.kt`, `DxNewsPskRepository.kt` | DX-World RSS + shared NG3K ADXO | Typed merge, bounded cache, source truth, search/filter, exact article/log/watch actions. DXNews.com is explicitly unavailable because no stable direct structured contract was verified. |
| `psk-reporter` / PSK Reporter | `src/DockableApp.jsx`, `src/components/PSKReporterPanel.jsx`, `src/hooks/usePSKReporter.js`, `server/routes/pskreporter.js` | Direct reports being heard/hearing | NATIVE | `HamClockHomeScreen.kt`, `HamClockHomeMap.kt`, `NeuralDxController.kt`, `NeuralDxScreen.kt`, `DxNewsPskRepository.kt` | PSK Reporter direct query | Sender and receiver queries, mutual intersection, active direction/window/cadence/cap/filter controls, directional map and exact review; no automatic CAT. |
| `dxpeditions` / DXpeditions | `src/DockableApp.jsx`, `src/components/DXpeditionPanel.jsx` | Active/upcoming expeditions | NATIVE | `DxpeditionScheduleProvider.kt`, `HamClockHomeScreen.kt` | NG3K ADXO | Parsed, bounded, cached and attributed. |
| `pota` / POTA | `src/DockableApp.jsx`, `src/components/POTAPanel.jsx` | Portable activators | DELEGATED | `PotaRepository.kt`, `PortableChaseScreen.kt` | POTA | Home summary delegates to authoritative Portable workspace. |
| `wwff` / WWFF | `src/DockableApp.jsx`, `src/components/WWFFPanel.jsx` | WWFF activators | DELEGATED | `PortableRepository.kt`, `PortableChaseScreen.kt` | WWFF | Dedicated native workspace. |
| `sota` / SOTA | `src/DockableApp.jsx`, `src/components/SOTAPanel.jsx` | SOTA activators | DELEGATED | `PortableRepository.kt`, `PortableChaseScreen.kt` | SOTA | Live access remains subject to provider terms. |
| `wwbota` / WWBOTA | `src/DockableApp.jsx`, `src/components/WWBOTAPanel.jsx` | WWBOTA activators | MISSING | — | WWBOTA | Stored programme preference is not an implementation. |
| `aprs` / APRS | `src/DockableApp.jsx`, `src/components/APRSPanel.jsx` | APRS station activity | MISSING | — | — | Planned, not currently available. |
| `rotator` / Rotator | `src/DockableApp.jsx`, `src/components/RotatorPanel.jsx` | Local rotator control | EXCLUDED | — | — | Conditional upstream local-install feature; no unattended hardware control. |
| `contests` / Contests | `src/DockableApp.jsx`, `src/components/ContestPanel.jsx` | Active/upcoming contest calendar | NATIVE | `ContestCalendarProvider.kt`, `HamClockHomeScreen.kt` | WA7BNM | Parsed RSS with cache/fallback truth. |
| `ambient` / Ambient Weather | `src/DockableApp.jsx`, `src/components/AmbientPanel.jsx` | Credentialed personal weather station | EXCLUDED | — | Ambient Weather | Conditional upstream credential feature; local weather remains native. |
| `rig-control` / Rig Control | `src/DockableApp.jsx`, `src/components/RigControlPanel.jsx` | Rig state/control | DELEGATED | `MainActivity.kt`, radio controllers | Physical CAT transport | Existing Radio workspace authoritative; no unattended TX/PTT. |
| `on-air` / On Air | `src/DockableApp.jsx`, `src/components/OnAirPanel.jsx` | On-air/TX state | NATIVE | `HamClockHomeScreen.kt`, `AppController.kt` | Live CAT state | Shows verified CAT and SAFE/ARMED state only. |
| `id-timer` / ID Timer | `src/DockableApp.jsx`, `src/components/IDTimerPanel.jsx` | Regulatory ID reminder | MISSING | — | — | Planned, not currently available. |
| `keybindings` / Keyboard Shortcuts | `src/DockableApp.jsx`, `src/components/KeybindingsPanel.jsx` | Shortcut reference | MISSING | — | — | Mobile/touch client has no parity outcome. |
| `meshtastic` / Meshtastic | `src/DockableApp.jsx`, `src/components/MeshtasticPanel.jsx` | Meshtastic messages/nodes | MISSING | — | — | Not currently available. |
| `meshcom` / MeshCom | `src/DockableApp.jsx`, `src/components/MeshComPanel.jsx` | MeshCom activity | MISSING | — | — | Not currently available. |
| `digital-modes` / Digital Modes | `src/DockableApp.jsx`, `src/components/DigitalModesPanel.jsx` | Receive/decode digital modes | DELEGATED | `DigiScreen.kt`, native decoder stack | Local audio/native core | Dedicated Digi workspace authoritative; receive/TX boundaries preserved. |
| `winlink` / Winlink | `src/DockableApp.jsx`, `src/components/WinlinkPanel.jsx` | Winlink gateway/message state | MISSING | — | — | Planned, not currently available. |

## Stable map plugins

These are all 24 static modules in upstream `src/plugins/layerRegistry.js`; local auto-discovered plugins are not stable inventory.

| Upstream id / name | Upstream path | Operator outcome | ShackCQ status | ShackCQ owner/files | Provider owner | Notes / gaps |
|---|---|---|---|---|---|---|
| `n3fjp_logged_qsos` / Logged QSOs (N3FJP) | `src/plugins/layers/useN3FJPLoggedQSOs.js` | Logged-QSO map overlay | NATIVE | `HamClockHomeMap.kt`, `QsoDatabase.kt` | ShackCQ local/Wavelog | Bounded 120-row compact projection query replaces N3FJP-specific transport and canonical JSON decoding. |
| `wxradar` / Weather Radar | `src/plugins/layers/useWXRadar.js` | Radar overlay | MISSING | — | — | Not currently available. |
| `owm-clouds` / Global Clouds (OWM) | `src/plugins/layers/useOWMClouds.js` | Cloud overlay | MISSING | — | OpenWeatherMap | Not currently available. |
| `citylights` / City Lights (Night) | `src/plugins/layers/useCityLights.js` | Night imagery | MISSING | — | — | Grayline is native; imagery is not. |
| `earthquakes` / Earthquakes | `src/plugins/layers/useEarthquakes.js` | Earthquake overlay | MISSING | — | — | Not currently available. |
| `wildfires` / Wildfires | `src/plugins/layers/useWildfires.js` | Wildfire overlay | MISSING | — | — | Not currently available. |
| `floods` / Floods | `src/plugins/layers/useFloods.js` | Flood overlay | MISSING | — | — | Not currently available. |
| `tornado-warnings` / Tornado Warnings | `src/plugins/layers/useTornadoWarnings.js` | Warning overlay | MISSING | — | — | Not currently available. |
| `aurora` / Aurora | `src/plugins/layers/useAurora.js` | Aurora oval | MISSING | — | NOAA | Persisted layer model alone is not a visible feature. |
| `wspr` / WSPR | `src/plugins/layers/useWSPR.js` | WSPR paths/activity | NATIVE | `HamClockRfEvidence.kt`, `NeuralDxController.kt`, `HamClockHomeMap.kt` | PSK Reporter | Personal sender/receiver evidence reuses the PSK transport with `mode=WSPR`; regional WSPR.live is `UNAVAILABLE_POLICY` and is never queried. |
| `grayline` / Gray Line | `src/plugins/layers/useGrayLine.js` | Day/night terminator | NATIVE | `NeuralDxMap.kt`, `HamClockHomeMap.kt` | Local calculation | GeoJSON night fill and dateline-safe terminator line. |
| `lightning` / Lightning | `src/plugins/layers/useLightning.js` | Lightning strikes | NATIVE | `NeuralDxController.kt`, `HamClockHomeMap.kt` | Public lightning feed | Bounded selectable GeoJSON points with source truth. |
| `rbn` / Reverse Beacon Network | `src/plugins/layers/useRBN.js` | RBN spots | NATIVE | `FeatureController.kt`, `HamClockRfEvidence.kt`, `HamClockHomeMap.kt` | Operator-configured retail DX cluster | Typed, bounded RBN observations parsed from the existing retail cluster socket; no official raw RBN firehose. |
| `contest_qsos` / Contest QSOs | `src/plugins/layers/useContestQsos.js` | Contest-specific QSO overlay | PARTIAL | `QsoDatabase.kt`, `HamClockHomeScreen.kt` | ShackCQ log | Logged QSOs visible; contest-specific selection is absent. |
| `great-circle` / DE/DX Great Circle | `src/plugins/layers/useGreatCircle.js` | DE-to-DX path | NATIVE | `NeuralDxMap.kt`, `HamClockHomeScreen.kt` | Local geometry | Native reporting paths. |
| `voacap-heatmap` / VOACAP Propagation Map | `src/plugins/layers/useVOACAPHeatmap.js` | Propagation heatmap | PARTIAL | `HamClockPropagationRepository.kt`, `NeuralDxMap.kt` | openhamclock.com + ShackCQ evidence | Path prediction exists; no full heatmap. |
| `muf-map` / MUF Map | `src/plugins/layers/useMUFMap.js` | Global MUF layer | MISSING | — | — | Not currently available. |
| `satellites` / Satellite Tracks | `src/plugins/layers/useSatelliteLayer.js` | Satellite positions/tracks | PARTIAL | `SatelliteOperationsController.kt`, `HamClockHomeScreen.kt` | CelesTrak/SatNOGS/local SGP4 | Positions visible; Home track/footprint preferences inactive. |
| `meshtastic` / Meshtastic Nodes | `src/plugins/layers/useMeshtastic.js` | Mesh nodes | MISSING | — | — | Not currently available. |
| `active-users` / Active Users | `src/plugins/layers/useActiveUsers.js` | Hosted-client presence | EXCLUDED | — | openhamclock.com | Product-specific presence/tracking is not adopted. |
| `ibp` / IBP Beacons | `src/plugins/layers/useIBPLayer.js` | Beacon overlay | NATIVE | `HamClockRfEvidence.kt`, `HamClockHomeMap.kt` | NCDXF/IARU local manifest | 18 selectable schedule sites and five current scheduled paths; not heard evidence. |
| `winlink-gateways` / Winlink Gateways | `src/plugins/layers/useWinlinkGateways.js` | Gateway overlay | MISSING | — | — | Not currently available. |
| `aircraft` / Aircraft | `src/plugins/layers/useAircraft.js` | Aircraft overlay | MISSING | — | — | Not currently available. |
| `atc-sectors` / ATC Sectors | `src/plugins/layers/useATCSectors.js` | ATC sector overlay | EXCLUDED | — | — | Outside current amateur-radio operator outcome. |

## Task 2A map availability

Active registry layers are DE station, DX spots, DX reporting paths, selected target, PSK Reporter, portable activity, authoritative Satellite Operations positions, recent logged QSOs, grayline/night, sun, Maidenhead field grid and lightning. Moon map position is explicitly unavailable because truthful sublunar geometry is not implemented; the separate Moon phase panel remains active. RBN, expanded WSPR, IBP, aurora, global MUF, propagation heatmap, weather radar and WWBOTA remain explicitly unavailable with reasons in Layout and Map Data; Task 2B owns those provider/image contracts.

Home now consumes panel visibility/order/column/row span/column span/collapse, map visibility/opacity/camera/follow/basemap, density, time-zone/hour format, units, low-data, immersive, manual-target and complete profile lifecycle settings. Satellite/portable/provider filter fields not wired by production UI remain truthfully planned.

Task 2A1 correction: `display.immersive` is presented as **Minimal Home** and remains `PARTIAL`; it hides only the Operations summary. Density changes layout thresholds, spacing and panel height, while unit selection applies to weather and map distance/altitude values. Exact feature identity is retained for DX, Portable, NORAD, QSO and target drill-through. Home and explicitly reviewed destination frequency actions use the receive-tune review gate; dedicated Radio controls retain their established direct operator behavior.

Task 2A2 runtime closure: the shared Radio/Preset CAT transport again keeps its established direct semantics, including complete compound Elecraft commands and all Flex receive fields currently supported by `ReceiveTuneRequest`. Receive review is scoped to Home and the Operations satellite receive preview; cancel dispatches nothing and confirmation remains receive-only. Home satellite positions now come from a foreground-only 45-second local lifecycle in `SatelliteOperationsController`, using the current station/Wavelog grid without modifying the Operations observer profile. Home's Neural DX refresh excludes the legacy satellite downloader/ticker.

Map selection is typed as DX spot, PSK report, Portable spot, NORAD satellite, QSO, target or weather. PSK references resolve in the native My Signal map rather than being sent to the DX-cluster spot resolver. Exact DX opens receive review, Advanced Logbook callsign history, and add/remove watchlist controls. Every registry layer has source truth; header counts cover visible current/degraded/empty/unavailable layers. Watched DX keeps the shared band fill and uses a separate ring/label. Late OpenFreeMap success clears the timeout fallback. `display.unitSystem` is active across Home distance/altitude surfaces; `display.density` is truthfully `PARTIAL` and labelled **Layout density**.

## Task 2B1 DX News and PSK availability

DX News and direct bidirectional PSK Reporter are operator-visible native features. Home consumes the same shared repositories as the DX workspace; NG3K is reused from the public-provider feed rather than fetched a second time. News opens the exact Briefing workspace, while PSK opens the directional My Signal map. Article/report actions expose batch log context, history, watchlist and explicit receive review only; neither provider result issues CAT directly.

## Task 2B2 RBN, WSPR, IBP and Band Health availability

RBN, personal WSPR and IBP are active native modules and map layers. RBN consumes only the operator's already configured retail DX-cluster connection and retains skimmer, DX, frequency, mode, SNR, speed, CQ/test and observation time. Personal WSPR is a mode-filtered view of the existing PSK Reporter transport. Regional WSPR.live remains visible as `UNAVAILABLE_POLICY`; no query is made.

IBP renders 18 manifest sites and up to five current scheduled paths. Those rows are reference schedule geometry, never reception proof. Band Health derives bounded live evidence with explicit source count, unique calls/receivers, call/receiver diversity, median SNR, trend, confidence and reasons. One application-scoped snapshot feeds Home, DX RF Evidence and Log Intelligence. Indexed `qso_projection` aggregates provide separate station/band/mode/comparable-UTC-window historical context; stale-only and mixed-source states are `STALE EVIDENCE` and `DEGRADED SOURCES`.

Map caps are RBN 120 observations/paths, personal WSPR 100 observations/paths, and IBP 18 sites plus five scheduled paths. Exact typed selection opens the RF Evidence page and retains receive-review safety.

Task 2B2B makes the RBN map point the skimmer receiver and the path heard/DX station to skimmer. `WHO_HEARS_ME` uses the normalized current station/Wavelog call, skimmer mode accepts a base call for `-#` sources, endpoint geometry prefers stream/station/cached-callbook grids before approximate CTY centroids, and the typed RBN lifecycle expires quiet-feed rows independently of ordinary cluster spots. Direct DX launch loads the shared NG3K/DX News lifecycle without Home composition. The official IBP ordered vector and recomputed hash are fixture-checked; exact details retain receive-review safety.

## Closure rule

## Empirical outlook completion

`NEURAL_OUTLOOK` is now a native Home module and typed map layer driven by the same application-scoped snapshot as Neural DX. The module is visible by default on wide layouts, hidden by default on compact layouts, and operator-configurable. The layer is disabled by default, selects 30/60/120 minutes and one band, renders at most 72 confidence-aware region cells, and performs no request or CAT action. Propagation presentation now explicitly separates the remote propagation estimate, observed empirical outlook and `P.533 local engine unavailable · LICENSE_BLOCKED`. Band Health remains current evidence; Log Intelligence adds the operational forecast/calibration handoff without changing awards.

Production wiring confirms the previous Finish-Line truth: solar imagery, space weather, NOAA aurora, Satellite Operations tracks/footprints, contest QSO mapping, ID reminder and shack display are native Android outcomes. With the added outlook module the effective panel count is `NATIVE 24 / PARTIAL 2 / MISSING 6 / DELEGATED 5 / EXCLUDED 2`; with the outlook layer the effective map count is `NATIVE 11 / PARTIAL 1 / MISSING 11 / EXCLUDED 2`.

Future reviews must compare this inventory to the pinned stable source. A model or provider changes status only after an operator-visible native outcome exists and is validated at the appropriate software/device/service boundary.

## Finish-line broad Phase 1 update

The baseline ledger counted panels as `NATIVE 19 / PARTIAL 3 / MISSING 8 / DELEGATED 5 / EXCLUDED 2` and map plugins as `NATIVE 7 / PARTIAL 3 / MISSING 12 / EXCLUDED 2`.

Source-complete Android outcomes change the effective counts to panels `NATIVE 23 / PARTIAL 2 / MISSING 6 / DELEGATED 5 / EXCLUDED 2` and maps `NATIVE 10 / PARTIAL 1 / MISSING 11 / EXCLUDED 2`. The added native outcome is the explicit Home-owned shack display. Solar imagery, the ID reminder, NOAA OVATION aurora, contest-specific QSO mapping, and authoritative Satellite Operations tracks/footprints are now native. The satellite status is `NATIVE` because the same Operations controller supplies capped positions, four tracks and four radio-horizon footprints, and Home consumes the saved show/selection preferences.

Propagation remains `PARTIAL`: the current-band bars and cache are useful, but the local P.533 engine, 24-hour matrix, scientific reference comparison, coverage heatmap, and MUF layer are blocked. Official ITU-R-HF v14.3 headers contain conflicting permission statements, so no source/data was copied. The independently written native boundary reports `LICENSE_BLOCKED`; it is not counted as an engine. The 383,264,044-byte compressed candidate data/source inventory would also exceed the 180 MB APK ceiling.

Exact mappings added by this phase: `SolarPanel` and space-weather detail to the native full-screen presentation; `useAurora` to bounded NOAA OVATION fills; `useSatelliteLayer` to the existing local SGP4 owner; `useContestQsos` to the indexed `qso_projection`; `IDTimerPanel`/`useIDTimer` to the reminder-only persisted timer; and themes/layouts to the explicit, escapable Compose shack dialog. `PropagationPanel`, `usePropagation`, P.533 services, VOACAP heatmap, and MUF map remain at the truthful partial/missing boundary above.
