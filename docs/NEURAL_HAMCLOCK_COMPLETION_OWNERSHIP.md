# Neural DX and HamClock empirical-outlook ownership freeze

## Frozen baseline

- Repository: `https://github.com/oliverbross/ShackCQ`
- Starting commit and verified `origin/main`: `909328a1c318db9252c626a6e0fccc73b66e22ab`
- Branch: `feature/neural-hamclock-completion-v1`
- Isolated worktree: `/Users/oliver/Documents/Projects/ShackCQ/shackcq-mobile-neural-hamclock-completion-v1`
- Wavelog watcher: release `3.1.0`, commit `af3256140bd05403b7c4a421746c2ea653a4f04f`, `NO CHANGE`.
- OpenHamClock watcher at the initial freeze: stable/release pins unchanged. The final rerun returned `REVIEW_REQUIRED` because preview advanced by two satellite-telemetry display/test commits; the exact two paths were reviewed and do not change this pass's stable pin, provider contracts, empirical model or independent-implementation boundary.
- Frozen universal debug APK reference: 110,139,162 bytes. Frozen debug AAB reference: 51,540,301 bytes.

## Existing owners preserved

| Capability | Authority |
|---|---|
| Configured DX cluster and RBN | `FeatureController` |
| DX News and calendar | shared `DxNewsRepository` / `DxpeditionScheduleProvider` |
| Bidirectional PSK and personal WSPR | shared `PskReporterRepository` / HamClock WSPR repository |
| Regional WSPR.live | `UNAVAILABLE_POLICY`; no request path |
| Satellites | `SatelliteOperationsController`, `SatelliteProviderRepository`, pinned `NativeSatellite` |
| Lightning and terrestrial weather | `NeuralDxController`; QTH-scoped Open-Meteo last-good cache |
| Solar, space weather and aurora | existing HamClock/Finish-Line owners |
| IBP | shared typed schedule/evidence model |
| QSO/log/Wavelog | `shackcq.sqlite`, schema 13, projection v2, `QsoMutationCoordinator` |
| Neural observations at the frozen baseline | `neural-dx.sqlite`, schema 3; this pass owns the scoped v4 migration |
| Groups.io | `shackcq-groupsio.sqlite` |

`NeuralOutlookController` is application-scoped through the single `NeuralDxController` instance. It receives immutable snapshots from these owners and performs no provider request. Neural DX, Home, the Home map, RF Evidence and Log Intelligence consume the same published snapshot.

## Expected change boundary

Production changes are limited to the Android Neural controller/model/store, Neural Compose screens/maps, Main application wiring, HamClock Home/map/typed registries/settings, Log Intelligence presentation, focused Android tests, and the completion/parity/provider/performance/size documents named by the programme. Shared C++, iOS, desktop, provider transports, credentials, CAT/audio/RF, Wavelog mutation ownership and unrelated modules are unchanged.

## Explicit exclusions and provenance

No ITU-R P.533 implementation, APRS, Winlink, WWBOTA, hazards, aircraft, Meshtastic/MeshCom, multi-cluster aggregation, new provider, WSPR.live request, Apple/desktop parity, transverter, hardware, deployment, dependency upgrade, model runtime, downloaded executable model, or broad framework work is authorized here. Local P.533 remains `LICENSE_BLOCKED`.

The empirical model is independently designed for ShackCQ. No Neural-DX-Watcher predictor source, model, database, weights, or assets are copied. The unresolved upstream permission remains recorded; this work claims behavioural integration, not licensed upstream predictor parity.
