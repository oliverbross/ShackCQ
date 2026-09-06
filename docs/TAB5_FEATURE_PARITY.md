# Tab5 feature parity

**Status:** Current implementation comparison, not a universal support claim.

ShackCQ carries forward selected owner-authored KX3 remote, logging, DSP, and DX behaviour from the Tab5 project into native Apple and Android clients over a shared C++ core. It does not inject fixture CAT frames, generated spots, fake spectrum, or demonstration QSOs in production paths.

| Area | Shared core | Apple | Android | Evidence boundary |
|---|---|---|---|---|
| KX3/KX2 CAT and safety classes | Implemented | DriverKit/Objective-C++ adapter | USB-serial/JNI adapter | Core tests and both builds pass; physical iPad/KX3 is historical, Android hardware unverified |
| Radio faceplate/state | Parser/state | SwiftUI controls | Compose KX3 console | Client builds; physical evidence differs by client |
| Local log and ADIF | ADIF/identity helpers | SQLite/document-directory import/export | SQLite/app-private import/export and paging | Automated coverage is strongest in core/Android |
| Wavelog | Retry/normalisation helpers | Keychain-backed queue and station/cache flows | encrypted preferences, durable queue and two-way flows | Authenticated current service proof unavailable |
| Cluster/CTY/DX | Parsing, CTY, ranking, worked status | Cluster, spots and compact DX views | Cluster plus expanded Neural DX workspace | Historical iPad real-cluster pass; Android network/hardware proof not repeated |
| Panadapter | Shared DSP | AVAudioSession and SwiftUI | Android audio/native/render paths | Historical iPad physical I/Q; Android physical I/Q unverified |
| CW macros | Safety primitives | Local macro editor/text CAT path | Session-armed macro workflow and abort semantics | Source/build evidence; transmit was not exercised in Phase 0 |

Portable-programme reference fields in Android logging do not constitute POTA/SOTA/WWFF feeds, activation sessions, P2P/S2S, or award support.

Software builds establish implementation readiness only. USB enumeration, DriverKit activation, live CAT semantics, authenticated Wavelog behaviour, real cluster density, and physical I/Q fidelity require separate evidence.
