# Final whole-application ownership

This record audits production construction in `ShackCQApp`; tests and documents are not treated as authorities.

| Concern | Sole production authority / construction | Storage | Safe close or restore |
|---|---|---|---|
| QSO body, projection, mutation | `QsoDatabase.shared`, `QsoMutationCoordinator` in `ShackCQApp` | `qso.sqlite` schema 16, projection contract 5 | monotonic migration; local-first; no destructive fallback |
| Wavelog binding/outbox/sync | `WavelogController`, `WavelogNativeController` | QSO outbox/binding tables | paused/retryable; credentials excluded from support/config |
| Groups.io | `GroupsIoController` | separate schema 2 database | closes background work; drafts retained |
| Neural / empirical outlook | `NeuralDxController` and its one outlook controller | `neural-dx.sqlite` schema 5 | last-good retained; no invented forecast |
| Cluster and RBN | `FeatureController` | bounded in-memory snapshots | Band Maps consumes snapshots and owns no client |
| HamClock public providers/settings | `HamClockPublicProviders`, `HamClockSettingsCoordinator` | provider cache/settings | last-good and disabled states preserved |
| Satellites | `OperationsController.satellites` (`SatelliteOperationsController`) | provider cache/native SGP4 state | receive-review only |
| Portable / activation / operations | `PortableController`, `PotaActivationController`, `OperationsController` | domain-private state | inactive restore |
| Digi | one `DigiController` and its `DigiFtEngine`/`DigiSessionStore` | Digi schema 2 | foreground loss and global Stop request RX |
| Voice/CW keying | `VoiceMacroTransmitController`, `KeyerController`, `KeyerQueueController` | private profiles/macros | queue/audio/PTT stopped idempotently |
| Contest / N1MM | one `ContestRuntime`, `ContestSessionController`, `N1mmNetworkController` | Contest schema 1 | restored paused; network unarmed |
| DX Chaser | one `DxChaserRuntime` / `DxChaserController` | DX Chaser schema 1 | restored inactive; cannot enable Digi TX |
| Band Maps | one `BandMapController` / `BandMapStateStore` | preferences schema 1; no spot DB | closes only its rebuild scope; read-only restore |
| Operating context / typed routes | `OperatingContextAuthority`, `WorkspaceActionRouter` | no runtime persistence | stale generation rejected; route cannot transmit/log |
| Configuration / health | `ConfigurationRecovery`, `buildSystemHealthSnapshot`, `SanitizedSupportBundle` | explicit safe preferences only | transactional restore clears unsafe runtime state |
| Radio/audio transport | `UsbRadioTransport`, `AudioMonitorController` plus explicit TX owner | app-private settings | one TX workflow; global Stop latches RX uncertainty |

No screen constructs a second authority for these concerns. Contest writes canonical QSOs only through `QsoMutationCoordinator`; Band Maps, Contest, N1MM and Chaser have no CAT/PTT/TUNE authority.
