# SDRoxide Selective Integration v1

## Frozen inputs

ShackCQ work is isolated on `feature/tci-multirx-waterfall-globe-v1`, created directly from `a03e04bb6734b3fbdc1c1ba19bdd6db17eacc947`. Protected local `main` remains `27c70d0c2ab0ae21ef18d7fd9b39f8878b0940ea`; `origin/main` remains `fb04d52df0c9ccc305125449bb188ef8e3f0185e`. The source parity branch remains `a03e04bb6734b3fbdc1c1ba19bdd6db17eacc947`.

The only SDRoxide source reviewed for this programme is `https://github.com/dividebysandwich/sdroxide` commit `312b27ec4303d3fe0ef3a70d3aa99ff615e460a4`, tree `09b6e8ed95bcf586b59f00f1c7c4d4ef8173bd11`. The checkout was detached and clean. SDRoxide supplies GPLv3 text and declares the workspace `GPL-3.0-or-later`; ShackCQ remains `GPL-3.0-only`.

## Existing ShackCQ audit

| Concern | Existing authority and evidence | Integration decision |
|---|---|---|
| Composition | `DesktopApplication` creates and exposes the only desktop service graph | Add objects beneath this root; do not create another application shell |
| Radio | `DesktopRadioController` is the only QML-facing radio owner and currently owns Hamlib lifecycle, polling, frequency and mode mutations | Add a TCI backend and receiver model inside this controller |
| Emergency cancellation | `DesktopApplication::globalStop()` fans out to parity, rotator and Panadapter owners | Extend the same entry point to cancel TCI mutations and issue at most one safe de-key/tune-off request |
| Panadapter | `DesktopPanadapter` owns Qt audio capture and one `shackcq_panadapter_context`; shared C++ already provides FFT, waterfall row, peak hold and coherent snapshots | Preserve this owner; add bounded receiver-indexed contexts and a public Qt Quick renderer |
| Radio DSP ABI | `core/include/shackcq/core.h` exposes the stable C ABI used by Android and Apple | Keep existing ABI compatible; add portable C++ TCI contracts without forcing a new native UI |
| Spots | `SpotRepository` and `ClusterController` own live cluster observations | RF visualization consumes snapshots; it does not create a new cluster or canonical spot store |
| QSOs and sync | `QsoDatabase` and `WavelogSyncEngine` own QSO truth and Wavelog mutation | Map/globe handoffs may select or prefill only; never save or sync implicitly |
| Providers | `DesktopParityPlatform` and its bounded provider/cache platform own provider work | RF observations are derived from those owners and bounded; no second provider stack |
| Current Intelligence map | `IntelligencePage.qml` truthfully declares a mapless foundation | Replace the foundation with one reusable RF observation/filter model used by Intelligence and future Home/DX consumers |
| Configuration | `DesktopConfigurationManager` and the platform vault own persisted configuration and secrets | TCI profiles store non-secret endpoint/preferences only; auto-connect defaults off |
| Build | Desktop CMake already requires Qt 6.11.2 including `WebSockets`, `Positioning`, `Location`, `Quick`, and `QuickTest` | No new runtime framework or package dependency is needed |
| Validation | Desktop unit/integration/QML tests, deterministic demo gallery, Windows/macOS CI and packaging already exist | Extend these exact seams with deterministic TCI and RF fixtures |

Android `RadioPlatformContracts`/`PanadapterController`, Apple native clients, the shared C ABI, the Rust Flex FFI pattern, desktop packaging, and CI were also audited. This programme adds no Android or Apple TCI/globe surface. Those clients remain regression targets only.

## Pinned SDRoxide behavior audit

The pinned tree contains the real paths listed in `docs/upstream/SDROXIDE_SELECTIVE_INTEGRATION.md`. The TCI crate models a text-command handshake plus a 64-byte, sixteen-`u32`, little-endian binary header and interleaved little-endian `float32` payloads. It exercises fragmented status, ready/timeout behavior, per-receiver I/Q and RX audio, attach/detach, reconnect, duplicate subscription handling, malformed payloads, and multi-receiver routing. Its production implementation also contains transmit facilities; ShackCQ does not adopt that TX policy.

The pinned UI/DSP tree demonstrates bounded FFT/spectrum history, waterfall texture remapping, percentile-style display fitting, piecewise color lookup tables, band-plan overlays, world-map projection, a textured globe, great-circle/propagation evidence, source/time filtering, and multi-receiver Panadapter state. ShackCQ will reimplement the selected behavior in its Qt/Flightline architecture without importing egui, wgpu, shaders, assets, icons, theme, workflow, provider stack, logbook, settings, or application runtime.

## Selected architecture

`docs/architecture/ADR_TCI_MULTI_RECEIVER_SDR.md` selects programme Option A:

- portable protocol/parser/domain logic in shared C++17;
- Qt WebSockets transport owned below `DesktopRadioController`;
- one receiver list model exposed by that controller;
- bounded receiver-indexed I/Q rings and Panadapter contexts;
- one derived RF observation/filter model below existing desktop authorities.

A Rust static library is rejected because Qt WebSockets is already linked and the existing C++ owner graph and test surfaces are sufficient. No Boost, egui, wgpu shell, second UI framework, or application runtime is introduced.

## Safety and evidence boundaries

TCI restore is always disconnected; a saved legacy auto-connect preference is not dispatched by desktop startup. The operator must use explicit Connect. No production path sends `trx:true` or `tune:true`. Frequency and mode writes are bounded, capability-aware, coalesced, and never replayed after an ambiguous disconnect. Global Stop cancels pending writes, disarms streams, and may send one de-key/tune-off request only when a live connection makes that safe.

Fake-server, unit, renderer, gallery, hosted build, and package evidence are software evidence. They are not physical-radio, authenticated-provider, audio-device, PTT, TUNE, RF, signing, deployment, or release evidence.

## Phase 0 conclusion

All required owners and integration seams exist. The selected work can proceed without a parallel authority or new dependency. Direct source copying is unnecessary; production code will be a ShackCQ-owned clean-room implementation informed by the recorded protocol behavior and standard geometry/rendering techniques.

## Implementation result

Option A is implemented. Portable C++ TCI contracts feed a receive-only Qt WebSockets backend owned by DesktopRadioController; ReceiverListModel projects explicit roles; DesktopPanadapter owns bounded per-receiver DSP contexts and the public Qt Quick renderer; RfObservationModel and RfMapItem provide derived, filtered flat-map/globe evidence beneath existing canonical data owners. The build adds no SDRoxide runtime, Rust FFI, private Qt API, WebView, commercial map key, duplicate radio/QSO/provider/settings owner, or production TX-on path. See TCI_CLIENT.md, MULTI_RECEIVER_MODEL.md, PANADAPTER_WATERFALL_V3.md, RF_MAP_GLOBE.md, and TCI_PHYSICAL_ACCEPTANCE.md for the implemented contracts and remaining physical boundary.
