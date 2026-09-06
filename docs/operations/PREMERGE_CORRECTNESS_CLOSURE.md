# Pre-Merge Correctness Closure

- Starting SHA: `cce411b81cabe8fd025b0089204bb66c1372670b`
- Final implementation SHA: `8a92499c768e734f0f3b2c9fdd1a3327260fb5a2` (the final pushed branch SHA, including this note, is reported in the handoff).
- Corrected production behaviours: satellite uplink is now `FREQ/BAND`, downlink is `FREQ_RX/BAND_RX`, receive tuning remains explicit and downlink-only, missing sides are not invented, CelesTrak offset-less UTC epochs parse strictly, manual TLE validation is native and transactional, every Logbook sort advances and restores pages deterministically, exposed filters retain exact semantics, Intelligence confirmation filters and spot refresh identity are complete, projection repair/rebuild completes in-session, AMSAT renders a bounded report timeline, and unsupported Timers are labelled honestly.
- Schema/projection changes: Android database version 13; projection contract version 2; added separate Comment/Notes, exact paper-QSL status, WPX prefix and region fields; multi-reference POTA uses `qso_reference`; migration resets only derivative projection/reference state and resumes bounded rebuild.
- Provider contract result: a live CelesTrak OMM CSV row with an offset-less fractional UTC epoch was retained and accepted by native SGP4, including a six-digit NORAD path. The reviewed DF2ET timer URL returned human-facing `text/html` with no stable JSON/fetch/XHR contract, so Timers are unavailable and no HTML scraping or network adapter remains.
- Focused validation: native CMake/CTest `1/1` passed; deterministic temporary SQLite profile passed all 12 sorts in both directions with distinct adjacent pages and exact Previous; focused satellite Fast Entry regression passed; `compileDebugKotlin` passed.
- Full JVM result: `235` tests passed, `0` failed, `0` errors, `0` skipped.
- APK: `/Users/oliver/Documents/Projects/ShackCQ/shackcq-mobile-wavelog-native-v1/android/app/build/outputs/apk/debug/app-debug.apk`; `124154591` bytes; SHA-256 `fa2f5fbafdd489dd9ae9de1ab51003668992c0f11ff7c3e7b9ad6a7804cd40ff`.
- Remaining external evidence only: physical Android-tablet migration/rebuild progress, GPS/share-target behaviour, live Wavelog mutation/round-trip, and radio/RF interaction were not run. No Apple build was run.
- `docs/wavelog/Archive.zip`: untouched, unopened, uninspected, untracked and uncommitted.
