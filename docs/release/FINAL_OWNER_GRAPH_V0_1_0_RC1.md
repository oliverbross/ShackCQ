# Final Owner Graph — ShackCQ 0.1.0 RC1

| Domain | Android owner | Apple owner | Desktop/Linux owner |
|---|---|---|---|
| QSO persistence/mutation/projection | QsoDatabase / QsoMutationCoordinator | QSOStore | QsoDatabase / QsoTableModel |
| Wavelog | WavelogController / native v2 | WavelogSync | WavelogSyncEngine |
| Groups.io | GroupsIoController | GroupsIoController | DesktopParityPlatform |
| Contest/N1MM | ContestRuntime | shared portable/core projection | DesktopParityPlatform |
| Digi/Keyer/Voice | DigiController / KeyerController / voice owners | FeatureModel | DesktopParityPlatform / DesktopKeyerController |
| DX/Neural/HamClock | FeatureController / NeuralDxController | FeatureModel | DesktopParityPlatform |
| Radio/Hamlib/TCI | RadioPlatformManager | RadioModel | DesktopRadioController |
| Panadapter/audio/local RX | PanadapterController / local receiver owners | FeatureModel | DesktopPanadapter |
| Scanner/survey/band maps | SdrOperationalV2 / BandMapController | FeatureModel | DesktopParityPlatform |
| Portable/operations/satellite | PortableController / OperationsController | FeatureModel | DesktopParityPlatform |
| Rotator | AndroidRotatorRuntime | unavailable | DesktopRotatorController |
| Remote Station host | not exposed | not exposed | RemoteStationService |
| Remote Station client | RemoteStationBackend | RemoteStationModel | RemoteStationClient |
| TCI/rigctld compatibility | client adapters | unavailable | RemoteStationService |
| Configuration/vault | app settings / Android Keystore | UserDefaults / Keychain | DesktopConfigurationManager / SystemCredentialVault |
| Health/support | controller health graph | native status views | DesktopApplication / SupportBundle |
| Global Stop | OperatorStopAuthority | RemoteStationModel receive stop | DesktopApplication |

There is one canonical owner per platform and domain. UI surfaces are projections and do not fabricate service state. Final matrix result: FOUNDATION_WIRED=0, MISSING=0.

