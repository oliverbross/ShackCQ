# SDRoxide Selective Integration Provenance

Repository: `https://github.com/dividebysandwich/sdroxide`

Immutable commit: `312b27ec4303d3fe0ef3a70d3aa99ff615e460a4`

Immutable tree: `09b6e8ed95bcf586b59f00f1c7c4d4ef8173bd11`

Observed licence: repository `LICENSE` contains GPL version 3; workspace metadata declares `GPL-3.0-or-later`. ShackCQ is `GPL-3.0-only`. No SDRoxide source, shader, image, icon, screenshot, data file, voice model, or binary is incorporated by Phase 0.

## File-by-file record

| Original path | Classification | Reviewed behavior and ShackCQ treatment |
|---|---|---|
| `crates/sdroxide-tci/src/protocol.rs` | CLEAN_ROOM_REIMPLEMENTATION | Text commands, status bursts, mode mapping, 64-byte header and LE `float32`; behavior reimplemented in C++ without copying source/comments/tests |
| `crates/sdroxide-tci/src/net.rs` | CLEAN_ROOM_REIMPLEMENTATION | Handshake, ready timeout, lifecycle, subscriptions and mutations; Qt WebSockets implementation follows ShackCQ ownership/safety rules |
| `crates/sdroxide-tci/src/lib.rs` | REVIEWED_NOT_USED | Rust public API and channel graph are not linked or reproduced |
| `crates/sdroxide-tci/tests/handshake.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Deterministic stalled/fragmented handshake coverage informs independent fake-server cases |
| `crates/sdroxide-tci/tests/loopback.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Loopback lifecycle coverage informs independent ShackCQ tests |
| `crates/sdroxide-tci/tests/multi_rx.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Two-receiver routing/attach-detach cases inform independent tests |
| `src/tci_source.rs` | REVIEWED_NOT_USED | SDRoxide application adapter is not imported |
| `src/panadapter_source.rs` | REVIEWED_NOT_USED | SDRoxide application Panadapter owner is not imported |
| `crates/sdroxide-dsp/src/spectrum.rs` | REVIEWED_NOT_USED | ShackCQ already has shared FFT/DSP |
| `crates/sdroxide-dsp/src/spectrum_paint.rs` | REVIEWED_NOT_USED | Rendering source is not copied |
| `crates/sdroxide-dsp/src/wbspectrum.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Wide-spectrum boundedness informs requirements only |
| `crates/sdroxide-ui/src/app/spectrum.rs` | REVIEWED_NOT_USED | egui application integration is rejected |
| `crates/sdroxide-ui/src/waterfall_gpu.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Texture/ring behavior informs a public Qt scene-graph reimplementation; shaders are not copied |
| `crates/sdroxide-ui/src/shaders/waterfall.wgsl` | REVIEWED_NOT_USED | Shader is not copied, translated, bundled, or used at runtime |
| `crates/sdroxide-ui/src/shaders/waterfall_remap.wgsl` | REVIEWED_NOT_USED | Shader is not copied, translated, bundled, or used at runtime |
| `crates/sdroxide-ui/src/colormap.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Piecewise LUT concept only; ShackCQ defines independent Flightline stops |
| `crates/sdroxide-ui/src/widgets/spectrum_view.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Interaction/FIT/passband concepts only; no egui code or layout copied |
| `crates/sdroxide-ui/src/widgets/wide_spectrum.rs` | REVIEWED_NOT_USED | Not imported |
| `crates/sdroxide-ui/src/widgets/bandplan.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Overlay concept only; ShackCQ uses its existing JSON band plan |
| `crates/sdroxide-types/src/bandplan.rs` | REVIEWED_NOT_USED | Upstream band data/types are not copied |
| `crates/sdroxide-types/src/propagation.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Evidence/control-point concepts inform independent standard-geometry implementation; no source/data copied |
| `crates/sdroxide-ui/src/basemap.rs` | REVIEWED_NOT_USED | Raster/assets and renderer are not copied |
| `crates/sdroxide-ui/src/digi_map.rs` | REVIEWED_NOT_USED | Digi history map is outside this integration |
| `crates/sdroxide-ui/src/prop_map.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Evidence heat and filtering concepts only |
| `crates/sdroxide-ui/src/login_globe.rs` | REVIEWED_NOT_USED | Login globe, shaders, textures and theme are not copied |
| `crates/sdroxide-ui/src/widgets/worldmap.rs` | CONCEPT_ONLY_NO_SOURCE_COPIED | Projection/interaction concepts only; Qt/Flightline implementation is independent |

## Modification and dependency impact

There is no direct or material source adaptation in Phase 0, so no upstream copyright header is transplanted. The planned production implementation is new GPL-3.0-only ShackCQ code and adds no third-party runtime dependency. SDRoxide is not vendored, submoduled, linked, packaged, or fetched at build time. Names identify an interoperability/reference source only and imply no endorsement.

## v1.6.6 watcher review

The read-only watcher was advanced on 2026-09-12 to release `v1.6.6`, commit
`d96f39d00aeb36b6e5eb412917aa04eee96f0105`, tree
`0d9c5fffc82d7bbfda21f7b5911985a67153b863`, after an immutable
`v1.5.3..v1.6.6` comparison. The range contains 284 commits and 300 changed
files. The seven changed TCI implementation/test paths preserve the existing
semicolon command framing and status parser. They widen the WebSocket upgrade
read budget for Windows, improve refusal/timeout diagnostics, add mode mappings,
and add TX pacing probes; no passband/filter, RIT, or XIT setter was added.
ShackCQ's independently implemented parser and stricter TX-readback ownership
therefore remain compatible and unchanged.

Large DSP, receiver, skimmer, relay, and UI additions in the same range are not
linked, vendored, or copied by ShackCQ. They do not expand this selective
integration. The repository `LICENSE` remains byte-identical GPL-3 with SHA-256
`3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`.
No radio, network TCI session, TX, or installed-Agent mutation was used for this
compatibility review.
