# Neural DX + HamClock empirical-outlook completion pass

Status: `PASS` on `feature/neural-hamclock-completion-v1`.

## Baseline and watchers

Start SHA: `909328a1c318db9252c626a6e0fccc73b66e22ab`. Final validated source SHA before this completion-record-only commit: `5cc7bd1`. Wavelog 3.1.0 / `af3256140bd05403b7c4a421746c2ea653a4f04f`: `NO CHANGE`.

OpenHamClock stable `d4a50eaaa61d3432a1de5f80cbe61790739930a5`, release `v26.5.0` / `cc2415e70cce5f9a583fa32efaf1c66792d030df`, version and licence digest remain unchanged. The final watcher returned `REVIEW_REQUIRED` only because preview moved from `36e5c1262dfde2057b2b4e6483be8c2215c70ad4` to `99913f2df574b8588ddaff703581b8f341f46761` in two commits affecting `src/plugins/layers/useSatelliteLayer.js` and `src/utils/satelliteTelemetry.test.js`. Review found a preview-only Flat/3D satellite display-derivation consolidation and tests; no stable/provider/outlook input changed. The watcher's security classifier matched escaped-string prose in the commit body, not a changed security path.

## Commits

- `797da4c` — `docs(neural): record empirical outlook ownership`
- `0593cff` — `feat(neural): add empirical outlook store and model`
- `eecfe22` — `feat(home): integrate shared outlook across hamclock and intelligence`
- `5cc7bd1` — `docs: update neural hamclock source truth and parity`
- completion record — `docs: record neural hamclock completion pass` (the pushed branch-head SHA is reported by the final handoff because a commit cannot contain its own hash)

## Source-complete outcomes

- `neural-dx.sqlite` schema 4, preserved spot journal, compact five-minute evidence buckets, restart-safe/resumable 1,000-row backfill, 180-day retention and 50 MB soft target.
- One application-scoped `NeuralOutlookController`; no provider fetch path, service, WorkManager, model runtime or binary asset.
- Station-scoped UTC-quarter-hour ±30-minute / 56-day matched baseline for current anomaly and 30/60/120-minute forecasts.
- Transparent 0–100 support model with explicit weights, confidence, reasons, VHF/microwave evidence gates, candidate attribution and no uncalibrated percentage.
- Bounded verification and model-versioned calibration with outage=`UNVERIFIABLE`.
- Neural Cockpit handoff, `Insight & Outlook`, World `CURRENT/OUTLOOK 30/60/120`, low-data rows, sources/ages/reasons/calibration and optional AI-summary boundary.
- HamClock `NEURAL_OUTLOOK` module and map layer, settings/profile/import/export/reset persistence, propagation separation, Band Health handoff and Log Intelligence operational outlook.
- Canonical 16-band order, direct-CAT guard above 54 MHz and all existing no-automatic-transmit/spot/log boundaries unchanged.

## Validation record

The v3→v4 migration preserves the v3 spot columns/row and creates the four outlook tables and targeted indexes. Its focused Android test also runs a bounded backfill twice and asserts the evidence row count does not grow; Android-test sources compiled, but the test was not executed on the protected tablet. Backfill remains off-main, cancellable, cutoff-bound and limited to 1,000 `rowid` rows per transaction. Live ingestion continues into replace-by-key five-minute station/band/mode/region/source buckets while the baseline is partial.

The baseline batches all 16 bands by each target UTC slot and uses dedicated station/time and station/band/region/time indexes. `ShackCQ Empirical Outlook v1` consumes bounded cluster, RBN, PSK/personal-WSPR, source-state, solar/geomagnetic, grayline, aurora, terrestrial-weather/lightning, calendar/watchlist/Needs, and QSO-summary context. PSK and WSPR retain provider/source identity in storage but form one independence family for agreement. QSO history and candidates do not add live RF support. Ended forecasts verify once in batches of 100; outage is `UNVERIFIABLE`; Laplace-smoothed percentages remain null until the 40-family/15-bin gate.

Neural provides Current evidence; 30/60/120-minute band results; top regions/candidates/reasons/calibration; a Cockpit 60-minute handoff; exact source ages; and World current anomaly labels plus future/low-data cells. HamClock uses the same snapshot in the wide/default, compact/optional `NEURAL_OUTLOOK` module and disabled-by-default typed map layer. Propagation distinguishes remote estimate, empirical observed outlook and local P.533 `LICENSE_BLOCKED`. Band Health remains current evidence; Log Intelligence displays the empirical operational handoff as `NOT AWARD CREDIT · NOT P.533` without changing award logic.

Disposable deterministic host dataset: 64,512 five-minute rows across 56 days, four active bands and three providers. Matched-baseline batch: 12.03 ms; complete 48 global 30/60/120 baseline/current-query recomputation: 31.14 ms; 72-cell regional pass: 9.28 ms; verification batch of 100: 1.99 ms; SQLite file: 20,774,912 bytes, below the 50 MB soft target. No generated database was retained.

Final Android validation: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:bundleDebug`, and `:app:compileDebugAndroidTestSources` completed together with `BUILD SUCCESSFUL in 7m 27s`; JVM result was 363 tests, 0 failures/errors/skips. Package audit passed the ITU/P.533 payload scan. No shared native/core source changed, so shared-core CTest was not run.

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`; 110,222,682 bytes; SHA-256 `60c291fddcfd65aa7fc20abebc101c0a36f4165688260e036f18ad13e93c4430`; +83,520 bytes against the frozen 110,139,162-byte reference and +401,770 bytes against the audit script's combined-integration reference; below 130 MB and +5 MB targets.
- AAB: `android/app/build/outputs/bundle/debug/app-debug.aab`; 51,611,659 bytes; SHA-256 `fa9d1b92e97e475773e03ec69dc8448c5603cede576e5dc1ad23bd85cf50858f`; +71,358 bytes against the frozen 51,540,301-byte reference; below 60 MB.
- HamClock parity ledger counts remain 26 `NATIVE`, 6 `PARTIAL`; this pass closes the empirical-outlook outcome within existing native Home/Neural surfaces instead of inflating the row count. Remaining backlog is physical Android acceptance, live-provider acceptance, calibration accumulation, Apple/desktop parity, optional APRS/Winlink/WWBOTA/hazards, and P.533 only after independent licensing resolution.

No APK was installed. No device data, credentials, CAT/radio state, PTT/TUNE, spot/log mutation, authenticated service, physical UI/audio or live RF/provider behavior was exercised. Those remain external acceptance, not inferred from source/build evidence. Final clean-worktree and local/remote equality are recorded in the final handoff after this document is committed and the feature branch alone is pushed.

## Final empirical-outlook hardening addendum

The follow-up hardening starts from feature SHA `0059b0fa94bd31f018ec9dddc49a1aa49e18a3bc` with frozen `origin/main` `909328a1c318db9252c626a6e0fccc73b66e22ab`; validated source commit `2d8e465` upgrades `neural-dx.sqlite` to schema 5. It limits persistence to supported global forecasts with current contributing sources, assigns one ID per 15-minute station/window/band slot, keeps verified rows 14 days, pending/evidence 180 days, retains calibration aggregates, and caps predictions at 100,000 without deleting unended pending rows.

Verification now unions exact capped call hashes across buckets and requires coverage from a contributing source family. Backfill commits exact call/receiver unions and progress together under one shared cluster-history key, publishes after one batch, and continues one batch per five seconds. The controller uses a recoverable supervisor worker, five-minute local heartbeat, sanitized retry state and selected window/band calibration. Missing source ages render as unavailable rather than fresh.

Eight new high-value cases were added across the existing two focused test files. Final host validation passed 368 JVM tests, `assembleDebug`, `bundleDebug`, Android-test source compilation, package audit, whitespace and conflict scans. The disposable 30-day/all-16-band profile finished at 64,736 prediction rows and 43,061,248 SQLite bytes; it was deleted. APK: 114,649,022 bytes, SHA-256 `7402417fe8533b93daf67b714bf22279ca3367986ab8340a7479dcd6d8a1abe1`. AAB: 51,623,548 bytes, SHA-256 `7f56e9416ea2a54ab00c62f3ac1d3637c46e53bfcb68c6cf6778790dfe8b1376`.

Wavelog remains `NO CHANGE`. OpenHamClock remains `REVIEW_REQUIRED` only for the already-reviewed preview satellite derivation/test delta to `99913f2df574b8588ddaff703581b8f341f46761`; stable `d4a50eaaa61d3432a1de5f80cbe61790739930a5`, release `v26.5.0` / `cc2415e70cce5f9a583fa32efaf1c66792d030df`, package version and licence digest are unchanged. No APK was installed and no physical UI, live provider/RF, CAT, PTT/TUNE, credential or authenticated-service behavior was claimed.
