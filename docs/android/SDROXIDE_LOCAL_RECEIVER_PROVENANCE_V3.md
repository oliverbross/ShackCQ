# SDRoxide Local Receiver Provenance v3

The v3 behavior review uses `dividebysandwich/sdroxide` release `v1.5.3`, commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`, under GPL-3.0.

Adaptation class: `CLEAN_ROOM_IMPLEMENT` and `ENHANCE_EXISTING`. ShackCQ's new files are independently written C++17/Kotlin/JNI code. No upstream file was directly adapted, so there is no copied path, copyright block or modification patch to enumerate. Package impact is code only: one shared-core translation unit, one JNI bridge and bounded Kotlin/Compose/database code. No dependency, submodule, source archive, media, I/Q, audio, theme, icon, font, model or codec was added.

The read-only watcher tracks demodulation, SSB/CW, SAM, NFM/WFM, CTCSS/DCS, RDS/RBDS, recording, scanner/audio integration and licence changes. It cannot change source, pins, branches or pull requests.
