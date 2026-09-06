# Task 2B2 — RBN, WSPR, IBP and Band Health closure

Date: 2026-08-20

## Scope and baseline

Implementation is confined to `feature/openhamclock-parity-v1`, based on local and remote SHA `5425cfa85c53ca53fccf33eddf0ab42ce37f5a31`. It does not merge, tag, release, deploy, install on a device, change Apple surfaces, or add radio transmit behavior.

Pinned OpenHamClock commit `d4a50eaaa61d3432a1de5f80cbe61790739930a5` was reviewed for the eleven Task 2B2 paths recorded in `upstream.json`. Upstream code was not copied. ShackCQ retains GPL-3.0-only licensing and uses the audit only for behavior/provider comparison.

## Architecture and truth

- `HamClockSettingsCoordinator` remains the single app-scoped owner of Home settings and profiles.
- cluster enablement controls connection lifecycle outside Home; there is no unconditional startup connection.
- the shared cluster presentation policy remains authoritative for Home, DX Cockpit, map and briefing correlation.
- RBN observations come only from the existing configured retail cluster socket. Disable clears their bounded buffer. No official raw RBN connection exists.
- personal WSPR reuses `PskReporterRepository` with `mode=WSPR`; no second PSK client exists.
- regional WSPR.live is always `UNAVAILABLE_POLICY` in this closure and has no request path.
- IBP is a local NCDXF/IARU schedule manifest: 18 sites, five frequencies, 10-second slots, 180-second cycle and offsets `0/17/16/15/14`.
- Band Health consumes bounded cluster, PSK, RBN, personal WSPR and recent-QSO evidence. It reports evidence/counts/diversity/confidence/reasons and never reports `CLOSED`.

## Settings truth

Versioned settings, named profiles, import/export and reset include RBN enabled/source/view/window/cap/bands/modes/minimum-SNR/skimmer/DX/watchlist/path controls; personal WSPR enabled/direction/window/band/minimum-SNR/cap/path controls; a disabled regional-policy explanation; IBP site/path controls; and Band Health window/mode/live-source/band controls.

DX News source (`ALL`, `DX_WORLD`, `NG3K`) and compact visibility remain typed and profile-persisted. PSK Reporter requires a callsign but not a grid; geometry-only station changes republish cached distance/path state without fetching.

## Presentation, maps and safety

Home and DX RF Evidence surfaces show provider truth, observation age/detail, batched worked status and watchlist state. Exact RBN/WSPR/IBP map identity opens the matching observation or site. Any frequency action enters the existing receive-only review dialog; no observation automatically tunes, keys PTT, sends TUNE, transmits, spots, or logs.

Map limits are RBN 120 points/paths, personal WSPR 100 points/paths, and IBP 18 sites plus five current scheduled paths. Hearing path geometry is remote sender to DE receiver.

## Automated verification

- Task 2B2 focused JVM suite: 12 cases passed.
- full `testDebugUnitTest`: 283 tests, zero failures/errors/skips.
- `assembleDebug`: passed; APK size 113,591,891 bytes; SHA-256 `94f547dba665f2d53d807ad199186301765c9987853e029c0f5ef13d9baf286c`.
- final pinned upstream watcher: exit 0.
- `git diff --check` and `upstream.json` parsing: passed.

## Evidence boundary

No physical Android device, authenticated external service, RF reception, CAT hardware or transmit behavior was exercised. Those layers remain unverified and are not inferred from compilation, JVM tests or APK assembly.

## Task 2B2A correctness correction

- RBN now preserves line UTC separately from receipt time, provides typed views, plots observed DX-to-skimmer geometry, publishes at a bounded off-Main cadence and is excluded from generic-cluster double ingestion.
- Personal WSPR provider changes may fetch; direction, band, SNR, cap and station geometry changes reproject the last loaded snapshot. Regional WSPR.live controls are disabled as `UNAVAILABLE_POLICY`.
- Home DX News renders the merged dated snapshot before applying the source filter.
- The literal IBP manifest uses KH6RS `BL10TS` and a fixed reviewed hash. Schedule projection and observed cluster/RBN evidence are separate.
- QSO history is comparison only and cannot make Band Health live. Source count, call diversity and receiver diversity are distinct, with cross-source event deduplication.
- Physical layout, live-provider and RF acceptance remain external evidence.
