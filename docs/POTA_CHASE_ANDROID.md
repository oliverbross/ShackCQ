# Android POTA Chase

Phase 2A remains the POTA source-specific record. The unified Phase 2B workspace and its current SOTA API approval gate are documented in [PORTABLE_CHASE_ANDROID.md](PORTABLE_CHASE_ANDROID.md).

## Purpose

Portable → POTA Chase is a read-only hunter workspace joining a current activator spot, a useful local worked-state decision, receive-only CAT tuning, and an editable draft in ShackCQ's existing logger. Saving continues through the normal local SQLite and optional Wavelog outbox path; hunter credit still comes from activator-submitted POTA logs.

## Data and update behaviour

- Live spots: `https://api.pota.app/spot/activator`, fetched on entry and at most every 60 seconds while the workspace is visible and the app is foregrounded.
- Park catalogue: `https://pota.app/all_parks_ext.csv`, downloaded only on operator request into a staged app-private SQLite database. Headers are matched by name; the active database changes only after row, uniqueness, coordinate, parse-loss, reopen, and sampled-lookup validation.
- Spot and catalogue requests use bounded timeouts/retries and the `ShackCQ/0.1` user agent. Conditional headers are retained when supplied.
- The last normalized spot snapshot remains available after a transient failure as `CACHED`; expired/QRT/invalid rows are never ranked as active. The last valid park database survives every failed or cancelled update.
- Catalogue metadata records source, bytes, UTC timestamps, `ETag`, `Last-Modified`, row count, source SHA-256, and the last failure. A foreground-day conditional check can flag an update; it does not silently download the catalogue.

## Operator workflow

Recommended sorting is deterministic and exposes reasons such as new park, new on band, new mode, freshness, not worked today, and current radio band. Worked labels are derived from the existing local/Wavelog-synchronised QSO database and mean local history only, never official POTA confirmation.

List and Map share one selection. Selecting a row or marker does not tune. `Tune` and `Tune & Log` show the proposed frequency/mode, require live CAT, send only VFO A plus an unambiguous receive-mode command, and never invoke TX, TUNE, a macro, or automatic save. `Tune & Log` sets the other station's `potaRef`, leaves `myPotaRef` empty for the Chase draft, preserves station-profile context, and triggers the normal callbook flow. Every draft field is editable before save.

Parks supports offline reference/name/location search and distance ordering from the configured station grid, an explicit foreground device-location request, or a manual grid. Coordinates are planning aids; operators must verify official park boundaries. Map tiles remain online and the list stays usable when tiles fail.

## Attribution and limits

Park and spot data are provided by Parks on the Air. ShackCQ is independent and does not use the POTA logo. Phase 2A does not implement authentication, official-credit claims, self-spotting, activation sessions, POTA log upload, SOTA, WWFF, notifications, panadapter overlays, iPadOS, or transmission.

## Validation

- Focused JVM suite: spot normalization/expiry/QRT/deduplication; worked state and deterministic ranking; CAT-offline no-command; `potaRef` logger draft; quoted UTF-8/header-based CSV; safe catalogue activation; park search and nearby ordering.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug`: 96 tests, zero failures; debug APK assembled.
- Lenovo TB373FU: `adb install -r` passed with app data preserved. The live source loaded 51 usable real spots; list selection, joined map selection, CAT-offline retention, background/resume, and refresh were exercised without an app crash.
- The real 9.3 MB catalogue imported 94,492 parks. `AU-0002` and `DE-0001` searches passed; Nearby from manual grid `JN88TQ` returned `DE-0001` at 371.4 km / 251°. Cancelling a subsequent update retained the ready database.
- Physical CAT was offline during this smoke run, so no radio command or logger handoff was attempted on the tablet. Unit coverage proves the offline no-command rule and draft field mapping; receive-only physical tuning remains unverified for this build.
- Evidence: `docs/pota/evidence/pota-live.png`, `docs/pota/evidence/pota-map.png`, and `docs/pota/evidence/pota-parks-search.png`.
