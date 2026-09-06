# ShackCQ product contract

## Positioning

The 0.1.0 RC1 integration provides hardened Android, adaptive native SwiftUI iPhone/iPad, and one local-first Qt/QML desktop application for macOS, Windows, and Linux. Source parity is fail-closed and does not imply physical, authenticated-WAN, RF, signing, or store acceptance.

> ShackCQ is a radio-native portable operating cockpit that connects discovery, tuning, operating, logging, synchronisation, and progress without requiring fabricated state or permanent network access.

ShackCQ is local-first. Radio control and local logging must remain useful without a cloud service. Online enrichment may improve the operating loop but must degrade independently and honestly.

## Current product

- Native adaptive Apple mobile client using SwiftUI for iPhone and iPad; simulator evidence is complete while signed physical-device acceptance remains separate.
- Native Android client using Jetpack Compose, including a dedicated KX3 receive-I/Q panadapter with explicit stereo-route proof, shared native DSP, CAT-synchronized spectrum/waterfall, calibration, diagnostics and bounded replay. Its software/device path is integrated and fail-closed; physical KX3 quadrature-I/Q RF acceptance is deferred.
- Shared C++17 core exposed through a C ABI.
- Deep Elecraft KX3/KX2 integration: observed CAT state, radio controls, transport adapters, safety classification, logging, real spectrum paths, and DX intelligence.
- Local SQLite journals and ADIF workflows.
- Android Local authority includes a durable Sync Hub for independently enabled QRZ Logbook, Club Log, and eQSL.cc delivery. Wavelog authority pauses direct services to prevent duplicates; upload acceptance never implies remote confirmation.
- Optional Wavelog, QRZ/HamQTH, CTY, cluster, NOAA/solar, and other Android Neural DX data sources where implemented and configured.
- Android Home is a native OpenHamClock-inspired operating dashboard that combines shared station identity, UTC/local time, live CAT truth, solar and band conditions, world DX paths, cluster spots, PSK Reporter reception, portable activity, propagation predictions, local weather, and satellite passes without duplicating settings or service credentials.
- CW text macros with explicit operator safety controls. Android also implements six operator-controlled SSB voice-macro slots with explicit CAT/PTT ownership and fail-closed audio routing; this is not claimed for Apple.
- Android Digi implements native streaming CW, 45.45-baud ITA2 AFSK RTTY, and fifteen SSTV modes (PD, Robot, Scottie, and Martin) with real encode/decode DSP. DigiRig audio on KX2/KX3 is explicitly routed and CAT TX/RX-confirmed; Flex digital TX reuses the existing remote-audio interlock. Physical DigiRig/KX3 and Flex DAX acceptance remain separate hardware gates.

The clients do not have identical surface coverage. Android currently contains the larger Neural DX workspace plus the implemented KX3 EQ Studio and SSB voice macros; Apple contains the physically proven iPad KXUSB and stereo-I/Q path. Cross-platform claims must name the client and evidence level.

- Compact destinations: Home, Radio, Digi, Logbook, Presets, DX, and Settings. Home is the native ham-clock operations overview. Expanded navigation also exposes EQ and Portable as first-class destinations; compact layouts open EQ Studio from Radio or Settings → Audio and Portable Chase from Home.
- Android EQ Studio reads exact KX3 RX/TX curves, keeps radio/draft/profile state separate, records one finite local audio sample, previews an approximate eight-band response with matched/blind A/B, and applies only through an exclusive CAT transaction with exact readback verification. It never keys the transmitter or claims to reproduce Elecraft's undocumented DSP topology.
- Panadapter is implemented on Android as an expanded destination and compact Radio subview. Android Digi includes native CW/RTTY/SSTV plus the exposed FT8, FT4, FT2, Q65, FST4, MSK144, JT65, and WSPR paths. Those digital modes have source/build/test evidence only where recorded; physical DigiRig/Flex audio and RF acceptance remains a separate gate and is not implied by their presence in the UI.
- The consolidated DX destination owns live cluster browsing and analyzed DX views.
- Android Portable → Portable Chase preserves the POTA vertical slice and adds unified programme filtering, WWFF Spotline/agendas, a safely replaceable offline SOTA summit catalogue, the authorised receive-only SOTA Cluster feed at cluster.sota.org.uk:7300, conservative grouping, programme-correct worked intelligence, coordinate-anchored MapLibre labels, receive-only CAT tuning, and an editable multi-reference draft in the existing logger. It does not use the deprecated private SOTA API or expose a SOTA posting path.
- Android Portable → Activate provides one recoverable local POTA session, explicit boundary acknowledgement, CAT-optional fast logging into the existing journal, editable P2P handoff, per-UTC-day progress, multi-own-park/P2P ADIF expansion, file sharing, and official-site browser handoffs. It does not post spots, upload logs, or initiate transmission.
- Radio state must be observed truth with explicit live, stale, disconnected, pending, and failed states.
- TX, TUNE, ATU TUNE, and CW macro transmission are disabled by default, explicitly armed, never started automatically, and never blindly retried.
- Local QSO durability outranks network synchronization; service failure degrades only that service.
- Logbook follows the configured source: the complete tablet log in Local mode, or the selected station's two-way cached Wavelog log including offline queued QSOs. New QSOs are entered only from Radio.
- Logbook filtering covers date presets, station and award fields, propagation, comments, numeric distance/duration expressions, QSL and online-service states, sorting, quick filters, and bounded result counts.
- No demo radio state, fixture spots, fabricated worked state, credentials, or automatic test QSO.

## Approved direction

1. KX3/KX2 Studio: preserve and harden the implemented Android KX3 EQ Studio, profiles, voice macros, and dedicated KX3 panadapter; extend other clients and KX2 write or wideband-I/Q claims only after their platform and hardware gates.
2. Preserve the integrated Android Portable Chase extension and its authorised receive-only SOTA Cluster source; continue to keep deprecated private SOTA API access disabled.
3. Preserve the implemented Android POTA Activate workflow; SOTA/WWFF activation remains later work.
4. Preserve Android Phase 4A local-log delivery; Phase 4B Needs Board and statistics is next.
5. FlexRadio SmartLink through legitimate official interfaces and authentication.
6. One Qt 6/QML/CMake desktop client for macOS, Windows, and Linux.
7. QMX, rigctld, additional radios, and additional programmes only after hardware/data/licence gates.

See [docs/ROADMAP.md](docs/ROADMAP.md). Roadmap items are not current capabilities.

## Product principles

1. **Observed radio truth.** No simulated radio, fabricated spectrum, fixture spot, demonstration QSO, invented service success, or hidden fallback in production paths.
2. **Local durability before network convenience.** Local logging remains authoritative until a configured authority accepts a durable outbox item.
3. **Explicit transmit safety.** Transmit-capable actions are operator-initiated, bounded, abortable, and never blindly retried.
4. **Authority-aware synchronisation.** Wavelog mode and local-log mode remain distinct; direct connectors must not duplicate uploads when Wavelog is authoritative.
5. **Graceful per-service degradation.** One unavailable provider must not falsify or disable unrelated local work.
6. **Platform-native interaction.** SwiftUI and Compose remain native; future desktop UI is Qt/QML.
7. **Lean shared core.** Put portable protocol, DSP, parsing, ranking, logging-domain, provider-neutral, and retry-policy logic in C++ when that is genuinely useful. Keep hardware, secure storage, document picking, lifecycle, networking orchestration, and UI platform-specific.
8. **Preserve the working KX3/KX2 path.** Do not replace proven code with a theoretical abstraction.
9. **Evidence before support claims.** Source, tests, builds, simulator/emulator, physical device, real radio/audio, and authenticated service are distinct evidence levels.

## Licence and reuse

ShackCQ is GPL-3.0-only. Distributed covered binaries require complete corresponding source and retained notices; charging for distribution or services remains permitted.

Future third-party reuse must record the source URL, immutable upstream commit, original path, licence, copyright/provenance, ShackCQ modifications, applicable notice entries, dependencies, and corresponding-source obligations.

Nexus is an evaluated external upstream. The Android Digi implementation incorporates only the component-audited CW/RTTY DSP and the MIT `tempo-sstv` crate at immutable commit `6ec4a7925f1550cc364c7fd95967ce38c696ad3f`, with notices retained in-tree. Its name or licence does not imply endorsement or make every remaining vendored/dependency component automatically reusable.

Public distribution through Apple-controlled channels remains an unresolved legal/platform risk. No App Store submission or compatibility claim is part of this contract.

## Windows full-parity candidate contract

The Qt 6.11.2 desktop candidate now has the full Windows workspace shell and one-owner provider/data/safety platform. Feature labels remain truthful: native radio/rotator, Digi, Contest/N1MM, Groups.io, Portable, Operations, Satellite/QO-100, awards and several HamClock modules are `FOUNDATION_WIRED` until their production controllers are integrated and tested. The product must display disabled, receive-review, `PROVIDER_BLOCKED` or `LICENCE_BLOCKED` state rather than presenting fixture-backed UI as complete behavior.

## Desktop Flightline UI

Windows and macOS share a Flightline desktop shell derived from the unlocked 41-screen tablet reference atlas. All 19 workspaces, Shack Display, the grouped collapsible sidebar, native system menus, shortcuts and the command palette resolve through one action model. Platform conventions remain native: global menus and Command shortcuts on macOS; a Win32 menu in window chrome with Alt access and Ctrl shortcuts on Windows. The sidebar collapses to icons at compact widths or by explicit operator preference. This is a usability and presentation convergence; it does not convert any `FOUNDATION_WIRED`, provider-blocked, licence-blocked or live-acceptance-pending capability into a completed service.

### Desktop Deep Convergence v2 candidate

The v2 candidate defines a locked official operating layout for every workspace and isolates freeform panel manipulation behind explicit Edit Layout mode. Home, Radio, Digi, EQ and Panadapter receive screen-specific hierarchy and density improvements while preserving fail-closed capability, provider, readback and transmit states. The candidate remains isolated until exact-SHA hosted Windows/macOS, package and live acceptance evidence exists.

### Desktop Functional Parity Closure v1

Windows and macOS now share real owners for native radio/rotator profiles, presets/EQ/keyer review, receive-only Digi, Chaser, Contest, Band Maps, empirical outlook, planning/Satellite, Groups.io and notifications. Source completion never converts missing credentials, capability readback or physical proof into authority: TX, automatic Doppler and rotator movement remain locked.

### Multiplatform RC1

The whole product now has a single RC source contract across Android, iOS, Windows and macOS with zero `FOUNDATION_WIRED` and zero `MISSING` completion rows. Release identity is channel `RC`, exact SHA, UTC build time, schema set and platform. This is source/package readiness only: authenticated services, visual hardware, audio, CAT/PTT/TUNE, RF, rotator movement, signing and distribution retain explicit acceptance gates.

## Android SDRoxide Enhancement Pack v1

The tablet now exposes a receive-only TCI cockpit, two-receiver I/Q and audio, Panadapter/Waterfall v4, scanning and band stacks, native RX DSP, RF map/globe evidence, Digi path context, and system speech. These are additive Android surfaces under existing owners. Debug fixtures, physical radio evidence, tablet evidence, signing, and release remain distinct.

## Android SDRoxide Operational Enhancements v2

Android adds receiver-targeted TCI readback/control, explicit receiver links, a dual-receiver audio mixer, Panadapter v5 review and bounded time-shift, PSK31/RTTY candidate skimmers, scanner banks/priority/journal, and per-mode TX-audio configuration. Spot exchange and diversity remain protocol-unavailable; all transmit controls remain locked pending separate physical acceptance.

## Android Local SDR Receiver v3

Compatible TCI, QMX/QMX+, KX3/KX2 external stereo and generic proven I/Q sources can feed up to two receive-only local virtual receivers. Shared C++ demodulation provides USB/LSB/CW/DIGU/DIGL/DSB/AM/SAM/NFM/WFM/SPECTRUM, CTCSS/DCS and validated RDS/RBDS metadata. Local audio reuses the existing two-input mixer and RX DSP. Panadapter click-to-listen, bounded PCM16 recording, time-shift pre-roll, Scanner audio hit, Settings, Health and `DEMO · NO RADIO` fixtures are Android-visible. No local action owns or implicitly changes a transmitter or physical radio.

## Android SDR Operator Workbench v4

Android adds production portable float32 I/Q capture/offline replay, Panadapter v6 history and Marker A/B tools, calibrated/relative measurements, signal tracking with local-receiver-only follow, bounded Spectrum Survey, historical scanner ordering, four channel monitors, memory/band-stack metadata and receive calibration. Replay is receive-only, detaches live TCI, never moves a physical VFO and disables audio outside 1×. Derived survey/capture storage is app-private and separate from canonical QSO ownership.

## Android TCI Transmit Control v5

Android adds an acceptance-gated TCI transmit authority for existing Digi/SSTV, reviewed CW audio-keying and immutable Voice Macro plans. PTT, Tune, TX audio, interlocks, Global Stop and RX recovery have one owner. New/restored profiles remain unverified; physical PTT/Tune/RF acceptance is pending. Debug TX is labelled `DEMO · NO RADIO` and cannot upgrade production acceptance.

## Secure Remote Station v6

ShackCQ Desktop and the standalone `shackcq-stationd` expose a versioned TLS 1.3 Remote Station service for Windows, macOS and Linux. The Android app discovers or manually pairs with a pinned station, then reuses its existing Radio, Panadapter, Digi, Keyer, Voice and Rotator owners through explicit observer/operator/admin roles and exclusive writer/TX/rotator leases. Global Stop and local pre-emption remain authoritative; debug media is labelled `DEMO · NO RADIO`, and source/build evidence is not public-internet, audio, motion or RF acceptance.

The RC1 closes client parity with native SwiftUI and Qt clients, adaptive Opus RX with PCM16 fallback, and optional host-disabled one-client raw I/Q. Device identities live in Android Keystore, Apple Keychain, Windows Credential Manager, macOS Keychain, or Linux Secret Service. Remote PTT, TUNE and movement remain unavailable without explicit policy and physical acceptance.
