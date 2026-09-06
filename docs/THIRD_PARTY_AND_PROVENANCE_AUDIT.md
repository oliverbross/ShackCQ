# Third-Party and Provenance Audit

The integration retains exactly one Hamlib 4.7.2 source at upstream commit `40f63488fe0bd751b147f48d62fd217bf53713a0`. Android, Windows and macOS desktop build from that reviewed source; generated platform configuration may differ. No `rigctl`/`rigctld` executable or duplicate Hamlib tree is added.

ShackCQ remains GPL-3.0-only. `NOTICE`, the Wavelog/OpenHamClock/Nexus provenance records, and `core/third_party/iturhfprop/SOURCE_MANIFEST.json` remain the authoritative manifests.

Wavelog behaviour was reviewed at 3.1.0 / `af3256140bd05403b7c4a421746c2ea653a4f04f` under MIT terms. OpenHamClock behaviour was reviewed at stable `d4a50eaaa61d3432a1de5f80cbe61790739930a5`; no upstream implementation was copied in this convergence. Nexus was reviewed at `57d11fd55f098dc9302b6aafed39e6cd4b6db216`, with the later 1.7.6 delta reviewed only for durable mode-visibility lessons.

ITU-R P.533 remains `LICENSE_BLOCKED`. No source, binary, numerical result, or implied calibrated forecast from the blocked component is present. Existing empirical outlooks retain their actual provider/provenance labels.

The release audit checks manifests, schema constants, configuration fixtures and forbidden privacy markers. Legal attribution files must be included unchanged in packaged source distributions.

Sweep 2 reuses the same byte-preserved Hamlib 4.7.2 archive for both radio and rotator APIs. It does not add a second Hamlib build, downloaded backend, proprietary ARCO source or copied firmware table. Native QMX behavior derives from the reviewed QMX branch and published CAT/USB behavior; RGO ONE is bounded to the reviewed V6 source and conservative unknown-generation behavior. microHAM ARCO is supported only through its published GS-232/EasyComm compatibility modes; no proprietary protocol claim is made.

Sweep 3 exposes a bounded in-product acknowledgement registry derived from `NOTICE` and these manifests. It introduces no new third-party source. WWFF Spotline public JSON is attributed to WWFF and refreshed no faster than official guidance; the protected WWFF Directory is neither scraped nor bundled. CQ/ITU/state overlays remain unavailable because no legally reviewed pinned geometry is packaged.

## Windows full-parity v1 provenance

The candidate adds Qt/C++/QML source, tests, workflows and documentation only. It introduces no new vendored source, binary, firmware, map geometry, P.533 payload, `rigctl`/`rigctld` executable or duplicate Hamlib runtime. Windows and macOS builds continue from the single pinned Hamlib 4.7.2 source tree. Provider URLs and protocol names are compatibility metadata; live downloads are bounded, disabled by default and are not redistributed in source artifacts.

Functional Parity Closure v1 links the existing in-tree `rust/shackcq-flex` static library for Flex, Digi and SGP4 behavior and reuses the existing core WSJT-X parser. It adds no vendored source or redistributable provider dataset. Qt Multimedia, SerialPort, Network and Widgets are already declared desktop dependencies.

## Multiplatform RC1 authority

RC1 preserves one Hamlib 4.7.2 source authority, one TCI implementation contract, the audited SDRoxide provenance record, the checked-in `mfsk-core`/`tempo-sstv` Rust sources, one SGP4 authority, and the existing CTY/band-plan snapshots. No duplicate runtime, downloaded backend, model, geometry, firmware, font or media payload is introduced. Exact-SHA source distribution includes `NOTICE`, upstream manifests and an SPDX 2.3 SBOM.

## Android SDRoxide Enhancement Pack v1

The reviewed SDRoxide production pin is v1.5.3 / `a680935b10f33768a499435e8bd37f779fa640ae` / tree `4697195080495da4a727b14234b85af89c10ecda`, GPL version 3. No upstream source or asset is incorporated. OkHttp 5.3.0 is the sole new runtime dependency and supplies the Android WebSocket transport; TLS trust validation is not bypassed.

## Android SDRoxide Operational Enhancements v2

The v2 audit reverified `v1.5.3` at commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`, release timestamp `2026-08-26T20:02:33Z`, GPL-3.0 licence digest `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`. The upstream checkout remained outside the product tree and no upstream file or payload was incorporated.

## Android Local SDR Receiver v3

The v3 start audit found no newer stable SDRoxide release. Receive modes, tone/data decode, recording and Scanner behavior were reviewed at the same immutable pin. All new DSP/JNI/Kotlin/Compose/storage code is independently written and adds no third-party dependency or payload. The adaptation ledger is in `docs/android/SDROXIDE_LOCAL_RECEIVER_PROVENANCE_V3.md`.

## Android SDR Operator Workbench v4

The v4 final-family audit again found stable SDRoxide `v1.5.3` at commit `a680935b10f33768a499435e8bd37f779fa640ae`, tree `4697195080495da4a727b14234b85af89c10ecda`, GPL-3.0 licence digest `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`. All new Kotlin/Compose/SQLite code is independently written. No upstream source, asset, recording, I/Q fixture, model, binary, package or runtime dependency is incorporated. The read-only watcher cannot mutate code or pins.

## Android TCI Transmit v5

V5 reverified SDRoxide v1.5.3 at the same commit/tree/license digest and audited the official ExpertSDR3 TCI reference repository at `b081213ff97150fd29f669c633f060f93c81a286`, tree `21a77eb5187da3c7e46c43ba3423b307a05d3047`. No code or payload was imported. The original implementation adds no dependency; classifications and intentional deltas are recorded in the v5 audit, protocol matrix and provenance ledger.

## Secure Remote Station v6

V6 reuses the same immutable SDRoxide and ExpertSDR3 reference audits for behavioral comparison. No upstream code or payload is incorporated. The remote protocol, station service, Android client, TCI/rigctld adapters, mDNS records and deterministic fixtures are original ShackCQ code; the detailed classification and watcher boundaries are in `docs/remote/REMOTE_PROVENANCE_V6.md`.

## Final RC codec and Linux credential dependencies

Remote RX uses official Xiph.Org libopus v1.5.2 pinned to peeled commit `ddbe48383984d56acd9e1ab6a090c54ca6b735a6`; no media or model payload is imported. Linux uses the system libsecret/Secret Service ABI for credential persistence. Licences and source provenance are carried into the source/compliance bundle and `NOTICE`.
