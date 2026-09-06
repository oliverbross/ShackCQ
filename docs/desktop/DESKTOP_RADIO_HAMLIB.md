# Desktop radio and Hamlib

ShackCQ uses only the repository-pinned Hamlib 4.7.2 source and verifies its manifest with `scripts/check_hamlib_upstream.py`. The Windows workflow produces one static `libhamlib` with configured supported backends; it does not build or package `rigctl`, `rigctld`, `rotctl`, tests or command-line utilities.

`HamlibModelRegistry` enumerates compiled capabilities and exposes manufacturer, model, backend, status and transport for search. `DesktopRadioController` starts disconnected, requires an explicit route and Connect action, polls observable frequency/mode, and accepts bounded receive-review CAT changes. The generic surface must hide unsupported controls.

PTT and TUNE are compile-time unavailable in Windows Alpha. No startup, configuration restore, provider, keyboard shortcut or receive-review action can transmit. Native KX/Flex/QMX/RGO extension interfaces remain a future path; the mandatory backend is Hamlib generic and native deep parity is not claimed.

`DesktopRotatorController` uses the same Hamlib library, starts disconnected/disarmed, and supports MANUAL/PROMPT preparation. A prepared grid/spot/manual target does not move. Movement requires an explicit confirmation; STOP is direct and park is explicit. AUTO_SELECTED_TARGET and tracking remain disabled until physical Windows acceptance.
