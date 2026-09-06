# Windows desktop portability audit

Frozen source: `integration/radio-hamlib-rotator-sweep2-v1` at `d1e956d2c21eefc905a5ecab086a8f467b7a03c4`. Frozen remote `main`: `fb04d52df0c9ccc305125449bb188ef8e3f0185e`.

The established architecture is retained: native SwiftUI and Compose clients remain untouched; the Qt 6/QML desktop client consumes the shared C++17 core through its narrow C ABI and directly uses portable C++ types only where the ABI does not expose an operation.

| Domain | Mobile owner reviewed | Portable core | Desktop owner/store | Boundary/status |
|---|---|---|---|---|
| Shell/context/routing | `AppController`, Compose navigation | No | `DesktopApplication`, QML shell, safe config | `WINDOWS_ALPHA_COMPLETE` |
| Credentials | Android Keystore stores | No | Windows Credential Manager; alias only in SQLite/JSON | `WINDOWS_ALPHA_COMPLETE` |
| QSO database/projection | `QsoDatabase` schema 16 | ADIF identity helpers | `QsoDatabase`, schema 16 semantic mirror | `WINDOWS_ALPHA_COMPLETE` |
| ADIF | `QsoDatabase` import/export | `shackcq_adif_serialize` | bounded streaming import/export; unknown fields | `WINDOWS_ALPHA_COMPLETE` |
| Wavelog | API-v2 client/store/engine | retry policy and URL helpers | Qt Network endpoint, binding/outbox/link/conflict/checkpoint | `WINDOWS_ALPHA_COMPLETE`; authenticated proof pending |
| DX Cluster | `FeatureController` | `kx3::parse_cluster_spot`, feature context | one `ClusterController` and `SpotRepository` | `WINDOWS_ALPHA_COMPLETE`; live endpoint pending |
| Band Maps | Android Band Maps controller/models | spot/worked analysis | Qt Quick layouts over one repository | `WINDOWS_ALPHA_COMPLETE` |
| Worked/confirmed/Needs | QSO projection | worked feature context | indexed desktop projection | `READ_ONLY_COMPLETE` |
| Home/HamClock summary | Home/HamClock controllers | propagation/solar APIs | local health and observed summaries | `READ_ONLY_COMPLETE` |
| Hamlib radio | `RadioPlatformController`, Hamlib JNI | pinned source 4.7.2 | desktop registry/controller | `WINDOWS_ALPHA_COMPLETE`; hardware pending |
| Rotator | `RotatorPlatformController` | pinned Hamlib | desktop controller, MANUAL/PROMPT | `WINDOWS_ALPHA_COMPLETE`; movement pending |
| Panadapter | QMX/Android audio controller | full `shackcq_panadapter_*` ABI | exact-route Qt Multimedia capture | `WINDOWS_ALPHA_COMPLETE`; live audio pending |
| Digi | `DigiController` | WSJT-X parser | inert companion foundation | `FOUNDATION_COMPLETE` |
| Contest | contest core/session store | selected domain rules | definitions/session-reader foundation | `FOUNDATION_COMPLETE` |
| Groups.io | Groups.io feature/store | No | vault/offline-store contract only | `FOUNDATION_COMPLETE` |
| Portable/Operations | provider registries | selected geo/domain helpers | status/list foundation | `FOUNDATION_COMPLETE` |
| Satellite/QO-100 | mobile pass controllers | SGP4 C ABI | shared API linked; page foundation | `FOUNDATION_COMPLETE` |
| Health/support bundle | `SystemHealthCentre` | No | health graph and stored-only privacy-safe ZIP | `WINDOWS_ALPHA_COMPLETE` |
| About/provenance | `NOTICE`, mobile About | shared repository notices | About/Licences page and packaged notices | `WINDOWS_ALPHA_COMPLETE` |

## Compiler and operating-system corrections

`core/CMakeLists.txt` now selects `/W4 /WX /permissive-` for MSVC and the existing strict warning set for GCC/Clang, and makes the static core position-independent for desktop linkage. No source behavior or mobile build file changed.

## Qt/toolchain audit

- Qt: exactly 6.11.2, stable official package `qt6_6112_mingw` / aqt architecture `win64_mingw`.
- Windows: x86-64 MinGW-w64 13.1, supported by Qt 6.11 on Windows 10 1809+ and Windows 11.
- macOS proof: the same source, Qt 6.11.2 `clang_64`, AppleClang, unsigned.
- Modules: Core, Gui, Quick, Quick Controls, QML, Network, SQL/SQLite, SerialPort, Multimedia, WebSockets, Concurrent, SVG, Positioning, Location and Test. No WebEngine and no proprietary-only module.
- Map provider: no acceptable provider is configured in Alpha, so the UI shows geometry/list truth without invented tiles or a baked API key.

Qt references: <https://doc.qt.io/qt-6/windows.html>, <https://download.qt.io/online/qtsdkrepository/windows_x86/desktop/qt6_6112/>.
