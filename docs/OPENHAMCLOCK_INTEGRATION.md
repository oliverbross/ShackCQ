# OpenHamClock native integration record

## Provenance

- Source: https://github.com/accius/openhamclock
- Audited commit: `d4a50eaaa61d3432a1de5f80cbe61790739930a5`
- Audited release: `26.5.0`
- Licence: MIT
- Copyright: Copyright 2024-2026 OpenHamClock Contributors
- Audited surfaces: every panel, map plugin, settings tab, layout, theme,
  language, server provider and persistence implementation under `src/` and `server/`.

ShackCQ implements the operating surface natively in Jetpack Compose. It does
not embed the upstream React application or run its Node server. The root
`NOTICE` retains upstream attribution; ShackCQ remains GPL-3.0-only.

## Shared authorities

The integration shares ShackCQ's existing operational state:

- station identity and recovery: `AppController` and the selected Wavelog station;
- CAT/radio state: `RadioState` and the existing radio backends;
- live DX and space weather: `FeatureController`;
- weather, PSK Reporter, WSPR, satellites and hazards: `NeuralDxController`;
- POTA/WWFF/SOTA catalogue and activation state: `PortableController`;
- QSOs and worked/confirmed status: `QsoDatabase` and `WavelogController`;
- entity and approximate geometry: `CtyController`;
- presentation state: the credential-free, versioned `HamClockSettingsStore`.

No second callsign, grid, radio, log, cluster endpoint, Wavelog credential or
callbook credential is introduced. HamClock presentation profiles exclude
secrets by schema and are included in ShackCQ recovery data.

## Implemented and functional

- persistent native Home dashboard with wide-tablet and compact phone layouts;
- safe drawing insets above Android taskbars and navigation bars;
- stable native map instance with keyed marker/path/area diffs and no empty
  overlay refresh frame;
- live DX spots and band-coloured great-circle reporting paths;
- sun position, grayline/night polygon and equinox-safe terminator geometry;
- PSK Reporter paths, portable markers, satellites, logged QSOs and lightning;
- live cluster status plus persistent, combined multi-select Band, Mode, CS and
  DS filters; the filtered set drives the table, map and DX target;
- geometry-backed DX-target distance and bearing;
- cached propagation API integration with explicit model/provenance truth; a
  non-P.533 response is labelled `DX path estimate`, never VOACAP;
- structured NG3K DXpeditions and WA7BNM contests;
- NOAA solar indices and GOES X-ray class, NASA SDO image metadata, moon phase
  and illumination, and local sunrise/sunset calculations;
- local weather, PSK Reporter summary, POTA/WWFF activity, band activity,
  personal WSPR evidence, local IBP schedule, explainable Band Health and satellite-pass panels;
- versioned settings with panel position/size state, map state and opacity,
  provider view preferences, DX target, named profiles, validated import/export
  and legacy migration.

Public providers keep an atomic last-good cache and expose `LIVE`, `CACHED`,
`STALE` or `UNAVAILABLE`; a failed refresh never replaces good data with empty data.

## Function-by-function audit gaps

These upstream functions are audited but are not claimed as complete. They must
stay visibly unavailable or labelled as estimates until their real authority exists:

- native ITU-R P.533/VOACAP engine and map heatmap;
- real-time PSK Reporter MQTT/SSE Heard/Hearing stream;
- MUF, regional WSPR-grid, Winlink and contest-QSO map layers; personal WSPR, retail-cluster RBN and IBP schedule layers are implemented;
- aurora/radar/storm/wildfire/earthquake/aircraft/ATC/user map layers;
- satellite tracks, footprints and Doppler controls;
- rendered solar product selector, N0NBH panel, analog clock, DX ticker, On-Air
  and ID-timer panels;
- complete theme, language, azimuthal projection and map-style matrix;
- APRS, Meshtastic, MeshCom, rotator, Ambient Weather and external contest
  logger bridges, which require actual configured hardware or services;
- live SOTA spots, which require provider authorisation;
- WWBOTA, which needs a stable, authorised provider contract.

This is an acceptance ledger, not an exclusion list. A feature moves to the
implemented section only after source integration, failure handling, tests and
tablet/phone validation. Build success or a visible placeholder is not parity.

## Verification record

- Android compile, complete debug unit suite and native APK assembly: passed.
- Lenovo TB373FU: installed in place with `adb install -r`; app data preserved.
- OM0RX/JN88TQ, Wavelog, CAT and cluster state retained.
- Refresh capture: basemap, markers and paths remained present in every sampled frame.
- Safe-area capture: dashboard content terminates above the Android taskbar.
- Filter capture: centered multi-select Band modal; Mode/CS/DS share the component.
