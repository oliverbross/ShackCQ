# Windows packaging

The exact-SHA Windows workflow uses Qt 6.11.2 `win64_mingw`, MinGW-w64 13.1, CMake/Ninja and CPack with open-source NSIS 3.11. `qt_generate_deploy_qml_app_script` performs QML scanning and deploys the executable, Qt runtime, platform/plugins, QML modules, SQLite driver and selected modules.

Artifacts:

- `ShackCQ-Windows-x64-portable-v0.1.0-rc.1.zip`
- `ShackCQ-Windows-x64-setup-v0.1.0-rc.1.exe`
- `artifact-measurements.json` with byte counts and SHA-256 values

The installer runs as the user under LocalAppData, creates a Start Menu entry, has no service or auto-start behavior, and is clearly an unsigned release candidate. Uninstall removes installed application files and shortcuts but does not delete user QSO databases, configuration, cache, logs, exports or support bundles because those are stored through `QStandardPaths` outside the install directory.

The workflow gates the portable ZIP at 150 MiB, the installer at 110 MiB and the unpacked package at 350 MiB; it measures rather than removing required Qt modules.
