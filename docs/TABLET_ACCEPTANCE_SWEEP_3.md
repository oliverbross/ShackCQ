# Tablet Acceptance Sweep 3

Source: `integration/radio-hamlib-rotator-sweep2-v1` at `d1e956d2c21eefc905a5ecab086a8f467b7a03c4`. Frozen remote `main`: `fb04d52df0c9ccc305125449bb188ef8e3f0185e`.

## Operator finding matrix

| # | Finding | Outcome | Production path |
|---|---|---|---|
| 1 | Cluster controls ignore connection state | FIXED | Settings uses `ClusterConnectionTruth`; connected hides Connect and exposes status/Disconnect. |
| 2 | No explicit bounded SH/DX | FIXED | Connected-only `SH/DX` requests 10–200 rows, one request at a time, through the existing socket. |
| 3 | Cluster spots absent from Band Maps | FIXED | The app-scoped `BandMapController` is now fed above navigation; all Band Map surfaces are read-only consumers. |
| 4 | Radio backends cannot be found/configured | FIXED | Radio Profiles cards and Add-radio backend chooser expose KX3/KX2/Flex/QMX/QMX+/RGO ONE/Hamlib without auto-connect. |
| 5 | Rotator platform cannot be found/configured | FIXED | Rotator Profiles, safe creation, persistence, connect/disconnect, delete, band-assignment route, workspace route and optional navigation are visible. |
| 6 | Day/Night/Field selector is inert | FIXED | Versioned per-profile values apply immediately and are included in app configuration recovery. |
| 7 | Global Contest preferences live in session setup | FIXED | Global defaults/policies are persisted in Settings; session identity/exchange/control stays in Contest. |
| 8 | Band Map autosave is not explained | FIXED | Debounced persistence reports Saving, Saved/time, or Save failed/Retry. |
| 9 | Groups.io overrides are ambiguous text | FIXED | Per-group rows use switches, labelled numeric fields, Reset and Edit Details; bulk apply/reset is explicit. |
| 10 | Health cards waste tablet width | FIXED | Cards use stable two-column layout at 700dp and one column below it. |
| 11 | About lacks acknowledgements | FIXED | Bounded registry derived from NOTICE/provenance separates incorporated software, behavioural references and providers. |
| 12 | SCP rows show MATCH/POSSIBLE | FIXED | Rows show callsigns only and highlight actual matched character positions. |
| 13 | Contest spots/Band Map are shallow | FIXED | Both read the shared snapshots; cluster table exposes operational fields and Band Map remains multi-band through the existing controller. |
| 14 | Contest status is duplicated | FIXED | One authoritative session row owns contest/role/state/radio/temp/network truth. |
| 15 | Contact Map repeats entity labels | FIXED | One representative label per visible entity is selected at camera idle; default omits DXCC wording/count. |
| 16 | Portable map callout drifts/routes away | FIXED | Map-native selected GeoJSON label remains anchored and a same-workspace detail surface replaces route jumping. |
| 17 | Operations map snaps home | FIXED | User camera movement is retained; map-centre/visible-bounds scopes update the planning origin and Return to Station is explicit. |
| 18 | WWFF live state conflated with directory | FIXED | Public Spotline spots/agendas retain last-good cache and 30-second minimum cadence; directory state is separate. |
| 19 | CQ/ITU/States toggles silently do nothing | PROVIDER_BLOCKED | No reviewed polygon bundle is packaged; disabled controls say `UNAVAILABLE_DATA` rather than pretending availability. |
| 20 | Groups.io initial sync can alert/spam | FIXED | Initial/backfill rows are suppressed; new rows use a bounded deduped app-level queue with exact-thread suppression and mute. |

## Safety invariants

Profile selection and restore remain disconnected. No Settings path arms TX, sends PTT/TUNE, enables QMX Digi TX, sends RGO setters, moves/parks a rotator, or arms automation. The authoritative QSO, cluster, Band Map, Groups.io, radio and rotator owners remain singular.

## Evidence boundary

Source/unit/build evidence does not prove authenticated services, physical UI/audio/RF, CAT/PTT/TUNE, or rotator movement. Those evidence layers remain in the live checklist.

## Local validation

- Android JVM tests, `bundleDebug`, instrumentation-source compilation, instrumentation APK assembly and `lintDebug`: PASS.
- The programme's named `assembleArm64Debug` task is not registered by the frozen source. The existing supported single-ABI mechanism, `assembleDebug -PshackcqAbi=arm64-v8a`, passed and produced the tablet APK.
- Four-ABI AAB: 53 MB in the pre-delivery local gate, below the 60 MB ceiling. The final exact-SHA artifact hash belongs in delivery evidence rather than a self-referential source commit.
- Arm64-only APK: 56 MB in the pre-delivery local gate, below the 130 MB ceiling. Its final exact-SHA hash is recorded after the branch identity is frozen.
- Rust: 97 passed, zero failed, one intentionally ignored. Debug shared core: 2/2 CTest targets passed.
- Unsigned generic iOS Simulator and generic iOS device regression builds: PASS. Apple feature parity was not claimed.
- Package audit: all four required AAB ABIs are present; the arm64 APK contains only `arm64-v8a`; no `rigctl`, `rigctld` or packaged `libc++_shared.so` payload was found.

Hosted exact-SHA validation and protected-tablet evidence are recorded only after those stages complete.
