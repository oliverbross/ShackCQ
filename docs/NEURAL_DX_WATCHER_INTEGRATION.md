# Neural DX Watcher behavioural integration record

## Provenance and release status

### Empirical-outlook completion

Android now has an independently designed `ShackCQ Empirical Outlook v1` in the existing Neural workspace. It receives immutable snapshots from the established cluster/RBN/PSK/WSPR/weather/solar/calendar/Needs/QSO owners, performs no provider fetch, and publishes the single snapshot used by Neural DX, HamClock Home/map, Band Health handoff and Log Intelligence. Schema 4, UTC-matched baseline, 30/60/120-minute outputs, verification/calibration gates and safety limits are specified in `NEURAL_HAMCLOCK_EMPIRICAL_OUTLOOK.md`. No upstream predictor code, model, database, weights or assets were copied; unresolved upstream permission remains recorded.

- ShackCQ repository: https://github.com/oliverbross/ShackCQ
- Reviewed remediation base: `39a2926648bdd98ca3d8e1200eff4892dca5eee9`
- Current-opportunities Android task base: `73b2f5e997d90a634dfa141fd414131599d2bf56`
- Behavioural reference: https://github.com/F1SMV/Neural-DX-Watcher
- Approved behavioural baseline: `fe3cba8ed9c0502f5dabdb2f64ebd990de986559` (`version 12.1`). The original remediation brief contained a non-resolving transcription/reference error; no upstream owner confirmation is required merely to identify this corrected pin.
- Upstream licence status: no `LICENSE`, `LICENCE`, `COPYING`, or `NOTICE` file, SPDX declaration, or licence grant was found in the inspected upstream tree or README. GitHub does not display a detected licence for the repository. Permission is therefore not established.
- Bundled upstream files or assets: none.
- This remediation copied no upstream source, comments, text, graphics, or assets. It changes ShackCQ's existing native implementation and documentation only.
- The earlier ShackCQ commit history identifies native Neural DX implementation commits, but current source inspection alone cannot prove the provenance of every pre-existing line.

> **Release blocker — unresolved upstream provenance and permission.** Do not describe the relationship as licensed parity or distribute material derived from Neural DX Watcher without a compatible licence or permission grant. ShackCQ's internal worked-log correctness fix is valid independently of that unresolved release decision.

## Integration boundary

ShackCQ uses Neural DX Watcher as a behavioural product reference. It does not embed the upstream Python/Flask application, HTML/CSS/JavaScript UI, SQLite files, predictor, resolver, deployment scripts, artwork, or runtime dependencies. ShackCQ remains GPL-3.0-only.

| Reference concept | ShackCQ implementation |
| --- | --- |
| Cluster spot ingestion and bounded live history | `FeatureController.kt`, `native_bridge.cpp`, `core/src/features.cpp`, `core/portable/src/dx_analysis.cpp` |
| Entity resolution | `CtyController.kt`, shared `CtyResolver` |
| Opportunity score/reason | shared `DxInsightEngine` in `dx_analysis.cpp` |
| Worked entity/call/band/mode intelligence | `QsoDatabase.kt`, shared `WorkedIndex` in `operator_intel.cpp` |
| Android seven-page workspace and local caches | `NeuralDxController.kt`, `NeuralDxScreen.kt`, `NeuralDxMap.kt`, separate `neural-dx.sqlite` |
| Apple DX surface | `FeatureModel.swift`, `QSOStore.swift`, `ContentView.swift` |
| Watchlist, solar and activity context | shared core plus the platform controllers above |

## Capability matrix

| Capability | Android | iOS | Desktop |
| --- | --- | --- | --- |
| Shared native live opportunities | Implemented | Implemented in the smaller DX view | Not implemented |
| Authoritative worked-log reload | Local log or selected cached Wavelog station | Current local `QSOStore` only | Not implemented |
| Worked-log loaded/complete health | Decoded and shown | Decoded and shown | Not implemented |
| Full seven-page Neural DX workspace | Implemented to differing provider depths | Not implemented | Not implemented |
| Separate `neural-dx.sqlite` cache | Preserved | Not added | Not implemented |

iOS cannot currently prove or select a Wavelog log authority in `QSOStore`; it loads only the local SQLite authority. No Wavelog station scope is fabricated.

## Corrected worked-log defect

Previously `shackcq_feature_add_worked_qso()` populated `WorkedIndex`, but `DxInsightEngine` ranked spots from a separate worked-country hash list. Android exposed no JNI operation to populate either native ranking authority. A spot already present in `QsoDatabase.spotStatuses()` could therefore receive the 22-point new-entity bonus and `NEW ENTITY IN LOGBOOK`.

The shared calculation now receives a per-spot classifier backed directly by `WorkedIndex`. It maps entity, call, band, mode, band+mode, and recent-dupe state into the opportunity before score and reason are calculated. `NEW ENTITY IN LOGBOOK` is possible only when the index is loaded, complete, and the resolved entity is non-empty and absent.

The reload contract is:

- `shackcq_feature_begin_worked_sync`: clears the prior index and marks it unloaded while rebuilding;
- `shackcq_feature_add_worked_qso`: normalises and records each local or Wavelog row, counting accepted and rejected input;
- `shackcq_feature_end_worked_sync`: atomically activates a legitimately empty or populated rebuild.

`workedLog.loaded` distinguishes never-loaded/loading from an empty log. `complete` is true only after activation with no rejected or truncated rows. An incomplete index may report positive matches it contains, but absence is never treated as proof of a new entity.

Android queries only callsign, stored country, frequency, mode, timestamp, band, and submode under the existing `stationScope()` rule. Current CTY resolution takes precedence over stored country; stored country is the fallback. A valid stored canonical observation band takes precedence over frequency-derived band. A fingerprint of QSO change token, authority, selected Wavelog station, and CTY revision prevents unchanged periodic rebuilds. For each new CTY revision, the installed bounded `cty.dat` text is loaded through JNI into the same native feature context before the worked-log rebuild is installed under the native lock. Native live spots and historical QSO classification therefore use the same installed CTY authority when available; without CTY, a blank live entity cannot receive the new-entity claim.

iOS loads the bounded installed `cty.dat` text into its existing shared feature context before rebuilding the local `QSOStore` authority. Historical QSO entity classification prefers the current CTY country and falls back to the stored country when CTY is unavailable or has no match. A successful CTY replacement triggers one native CTY load, one complete local WorkedIndex rebuild, and one DX snapshot refresh. ADIF import defers visible-record reload and worked-log notification during per-record inserts, then performs each once for a changed batch. iOS still does not fabricate Wavelog station authority or implement the Android-only seven-page Neural DX workspace.

## Intentional differences from the behavioural reference

- ShackCQ retains one active cluster connection with configured failover, not simultaneous multi-cluster aggregation.
- Shared native observation ingestion covers `160m`, `80m`, `60m`, `40m`, `30m`, `20m`, `17m`, `15m`, `12m`, `10m`, `6m`, `4m`, `2m`, `70cm`, `23cm`, and `3cm`.
- Direct CAT tuning from Neural DX remains limited through 6 m. Higher-band spots are visible and analysable but show an observation-only state until ShackCQ has an explicit supported radio/transverter path.
- The full Neural DX workspace remains Android-only. iOS has the smaller native DX surface; desktop has none.
- ShackCQ's opportunity score is a deterministic freshness/watchlist/entity/surge/solar heuristic. It is not the upstream `predictor.py` model, prediction database, verification history, or probability calibration.
- Android uses the selected ShackCQ local or cached Wavelog authority. iOS currently uses only its provable local log authority.
- ShackCQ preserves its existing native UI, local storage, controllers, and separate `neural-dx.sqlite`; it does not run Flask/nginx or an upstream local web API.

## Android current opportunities and historical spot journal

Android labels the ranked live rows as **Current opportunities**. They are derived directly from the shared native spot priority and evidence values: `priority` is the existing opportunity `score`, and `evidenceScore` is the existing `confidence`. Rows below priority 45 are excluded, equal callsign/band/mode rows are deduplicated after ranking, and the list is capped at 12. There is no probability, forecast window, model label, verification history, or measured-reliability claim. Dynamic worked/QSL status remains authoritative in `QsoDatabase` and is not copied into the spot journal.

The Android-only `neural-dx.sqlite` journal is schema version 3. Its spot rows now retain DXCC, confidence, sample count, reason, and last-update time for future historical analysis. The v2 migration preserves existing spot rows and the 90-day retention rule, seeds `updated_at` from the original spot timestamp, adds the DXCC/band/time index, and removes the obsolete `prediction_result` table. Repeated spot IDs update their latest dynamic ranking fields while blank later enrichment cannot erase a useful country, DXCC, continent, coordinate pair, or comment. Only an inserted ID is considered fresh, so an upsert cannot repeat watchlist/New-DXCC alerts.

## Unified observation-band contract

The shared cluster parser, live spots, activity bands, timelines, watch activity, worked-log classification, and deterministic current opportunities now use one fixed 16-band order: `160m`, `80m`, `60m`, `40m`, `30m`, `20m`, `17m`, `15m`, `12m`, `10m`, `6m`, `4m`, `2m`, `70cm`, `23cm`, `3cm`. The five appended bands preserve every existing bit position. Accepted cluster input is bounded by the shared 1 MHz to 10.5 GHz parser/engine contract, and unsupported gaps such as 100 MHz, 222 MHz, 902 MHz, 2.3 GHz, and 5.7 GHz remain rejected rather than entering analysis as `other`.

Canonical band identity follows ADIF frequency semantics. QO-100 downlink observations are stored, filtered, and matched as `3cm`; Android and iOS may display `3cm · QO-100` for the 10489.500–10490.000 MHz narrowband segment or an explicit QO-100 spot comment. Android local/Wavelog worked-log fallback and iOS local worked-log fallback use the same five added ranges. The existing schema-version-3 journal stores these strings without a migration, retains its upsert and 90-day rules, and stores no tune state.

Android manual cluster posting accepts operator-entered frequencies through 10.5 GHz. That cluster message is distinct from radio control. Android and the smaller iOS DX surface block every Neural DX `FA`/direct-tune action above 54 MHz while preserving spot detail and history access. No transverter offset, radio-capability framework, automatic hardware detection, satellite tuning, or QO-100 uplink control was added. The full seven-page workspace remains Android-only and desktop Neural DX remains unimplemented.

## Android external providers

Current source contains integrations for the configured DX cluster; NOAA SWPC solar products; country-files.com CTY data; Open-Meteo; wspr.live; PSK Reporter; DX-World; DXNews; NG3K ADXO; QO-100 DX Club; CelesTrak; AMSAT orbital elements; SatNOGS transmitters; the dl0tud beacon list; Blitzortung community MQTT; optional Perplexity; and an operator-configured HTTPS ntfy endpoint. Provider availability, terms, credentials, and live-service behaviour are separate from source/build validation.

### Android provider freshness and last-good cache contract

Android uses one visible state vocabulary for the Neural DX providers:

- `LIVE`: a response was fetched, decoded, semantically validated, and atomically committed as the new last-good cache;
- `CACHED`: a valid last-good cache remains within the provider refresh interval, including after a failed forced refresh;
- `STALE`: an expired last-good value is retained after provider failure, invalid content, or an unproven derived startup restore;
- `UNAVAILABLE`: no valid live or cached value exists.

Each status records its source, update epoch, expiry epoch, and a short safe detail. A live or cached status is evaluated as stale after its expiry, and the Compose panels show `Source · STATE · age`. The top-level refresh summary counts every requested core provider; the four briefing feeds count independently, blank-callsign PSK Reporter is excluded, and optional Perplexity enrichment is not a core provider. Local opportunity, log, and world calculations remain available when providers fail.

Raw network text is decoded and validated before it can replace last-good data. Bounded cache reads reject empty or oversized files. Writes use a temporary file in the destination directory, flush and sync it, then move with atomic replace where supported and a narrow replace fallback; the destination is never explicitly deleted first and abandoned temporary files are removed. Malformed successful HTTP responses therefore cannot poison a valid cache, and coroutine cancellation is rethrown rather than converted to fallback state.

Open-Meteo and WSPR caches use locale-independent, rounded station-coordinate keys. The selected station's scoped cache is restored at controller startup; legacy unscoped files are not trusted for another QTH. The same Open-Meteo decoder restores current values, CAPE, 850 hPa temperature, 300 hPa wind, pressure trend, tropo index, and ducting label. WSPR restores separate HF and VHF/UHF lists, with an explicit empty `data` array treated as a valid zero-observation result.

PSK Reporter retains its per-callsign raw cache and recalculates receiver distances for the current QTH whenever raw live or cached data is decoded. Its derived display file is atomic and starts stale until callsign/QTH revalidation. Briefing raw caches retain independent states and require at least one parsed item before commit; the combined display cache is atomic. Satellite catalogues must decode to a non-empty catalogue before commit. The beacon raw cache requires at least ten valid rows, recalculates QTH-relative ranges, and atomically writes its derived display cache. Satellite and beacon catalogue age is shown separately from continuously recalculated local positions.

Blitzortung uses the same vocabulary without disk persistence: a connected session is live, retained strikes during disconnect are stale, and disconnect without strikes is unavailable. The controller owns the active socket, closes it before a materially different QTH listener starts, and closes it during idempotent controller shutdown before cancelling jobs and scope. No reconnect loop survives scope cancellation.

This cache/freshness work is Android-only. It adds no dependency or database migration, leaves `neural-dx.sqlite` at schema version 3, does not change `shackcq.sqlite`, scoring, worked-log logic, observation bands, direct-tune safety, endpoints, or iOS/shared-core code, and does not resolve the Neural-DX-Watcher licence/permission release blocker.

## Future upstream review procedure

1. Retain the approved immutable upstream commit and obtain explicit licence/permission status.
2. Fetch the upstream repository and record the exact commit, tree, licence files, notices, and release/version label.
3. Review behaviour and documentation as a reference; do not copy source or assets without a compatible licence and the repository's required provenance record.
4. Compare concepts against the mapping above and update the capability/deviation matrix truthfully.
5. Keep changes inside existing ShackCQ authorities and storage unless a separate task authorises architecture work.
6. Run focused native, Android, and iOS validation and record source, automated, simulator/device, service, and RF evidence separately.

## Validation record for this remediation

Current-opportunities Android closure on base `73b2f5e997d90a634dfa141fd414131599d2bf56`:

- Android JVM suite passed, including the focused current-opportunity threshold, ranking, deduplication, 12-row cap, direct priority/evidence mapping, and forbidden prediction-field checks.
- Android debug APK assembly passed.
- Android instrumentation APK assembly passed, including compilation of the isolated `NeuralDxStoreInstrumentedTest` v2-to-v3 migration and insert/update preservation test.
- The focused instrumentation test was not installed or run because the only connected target was Oliver's TB373FU operator tablet. No disposable emulator/device was available, and the normal app and its data were left untouched.
- Production-source scans found no pseudo-prediction model, controller state, verification/reliability runtime, percentage display, or forecast heading. The only remaining `prediction_result` reference is the intentional v3 migration drop; the only remaining `probability` wording explicitly states that the live heuristic is not one.
- No shared C++, iOS source, core validation, or iOS build is part of this Android-only task.

- Shared core: configure/build passed; CTest `1/1` passed. Regression coverage includes unloaded, loaded-empty, matching-QSO, reset, score delta, and incomplete-index safety.
- Android JVM suite passed `218/218`; debug APK and instrumentation APK assembly passed.
- Android database instrumentation: the focused authority/station scope, entity/band fallback, and change-token test passed on the connected TB373FU. This is database evidence, not physical UI, RF, or service evidence.
- Android JNI/CTY instrumentation: the focused test APK assembled. A test-only install on TB373FU could not enter the new JNI path because the preserved installed target app predates `featureLoadCty` (`NoSuchMethodError`). The normal app was not reinstalled and its data was not cleared; runtime JNI scoring proof remains pending.
- iOS CTY/worked-log closure: the complete generic iOS Simulator build passed. The installed CTY text is loaded before local worked-log rebuild, current CTY country takes precedence with stored-country fallback, and ADIF batches defer the complete rebuild to one final notification for a changed import.
- No live external provider, authenticated service, physical radio, transmission, or RF validation was required or performed.

Unified observation-band contract on base `76e1e40e8471f142c9349a193c0a62687c79b218`:

- Shared core configure/build passed and CTest `1/1` passed. Coverage includes all five added mappings, 16 band/timeline rows, 64-bit 3 cm frequencies, appended filter bits, explicit high-band modes, unsupported gaps, 2 m worked-log matching, unchanged 22-point new-entity removal, and no HF solar-support reason on 2 m.
- Android JVM tests passed, including canonical frequency mapping, upper-bound/gap rejection, 16-band order, direct-tune safety, QO-100 display-only annotation, and unchanged current-opportunity priority/evidence semantics.
- Android debug APK and instrumentation APK assembly passed. No APK was installed and no instrumentation test was run on Oliver's protected operator tablet.
- The complete generic iOS Simulator build passed for both simulator architectures. The dynamic snapshot decoder and band/pulse views require no static 16-band list.
- Focused source review found no 54 MHz cap in shared parser or engine, no QO-100 stored/filter band identity, and guards on every Android/iOS Neural DX `FA` path. `git diff --check` passed.
- No live service, physical UI, physical device, CAT, radio, transmission, RF, or release validation was performed.

Provider freshness/cache closure on base `45eec41bc3e12abeecf87c9c59cde6012743b342`:

- Android JVM tests passed `234/234`, including focused last-good state transitions, malformed-response preservation, bounded reads, cancellation, atomic replacement, effective ageing, human-readable ages, full weather offline decode, WSPR empty-data semantics, QTH cache keys, and truthful provider-summary counts.
- Android debug APK and instrumentation APK assembly passed. No APK was installed and no instrumentation test was run on Oliver's protected operator tablet.
- Production-source scans found no `cachedFetch`, `All Neural DX sources current`, or `STALE CACHE` wording in the Neural DX controller/UI.
- Changed source is Android-only. No shared C++, iOS, database schema, current-opportunity ranking, worked-log, observation-band, or direct-tune implementation changed. Shared-core and iOS builds were deliberately not run.
- No live provider, physical UI/device, authenticated service, CAT, radio, transmission, RF, release, or store validation was performed.

Validation commands:

```sh
cmake -S core -B /tmp/shackcq-neural-dx-core
cmake --build /tmp/shackcq-neural-dx-core
ctest --test-dir /tmp/shackcq-neural-dx-core --output-on-failure

cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest

xcodebuild -project ios/ShackCQ.xcodeproj -scheme ShackCQ \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```
