# Desktop Iconography

ShackCQ uses an original, repository-owned Flightline SVG family. It is not copied from the tablet APK or a third-party library.

- Location: `desktop/resources/icons/`
- Count: 40 SVGs
- Geometry: 24×24 viewBox, no fill, 1.8 px rounded stroke, authored for 20–24 px display
- Packaging: Qt resource prefix `qrc:/ShackCQ/App/Icons/`; CMake includes every SVG in Windows and macOS packages
- Rendering: `FlightlineIcon.qml` requests device-pixel-aware source sizes
- Dependencies: no emoji, icon font, proprietary font, network asset, attribution, or added licence
- States: `QtQuick.Effects.MultiEffect` tints source SVGs from the Flightline semantic colour passed by the palette, Settings category or workspace action; focus and disabled state retain written labels
- Application icons: the existing original 1024 px ShackCQ brand asset produces a seven-size Windows `.ico`, a macOS `.icns`, runtime window icon and bundle metadata

Coverage includes all 19 workspaces plus Shack, Connect, Disconnect, Global Stop, import/export, edit actions, full screen, search/command palette, reset, support, help and keyboard shortcuts. `desktop_ui_contract_tests` rejects a missing registry icon and verifies the SVG viewBox.
