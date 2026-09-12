# ShackCQ Nexus-native desktop architecture

Status: Accepted for the `feature/nexus-native-desktop-shared-digi-v1` implementation.

## Decision

ShackCQ Desktop is one installed product with two bundled processes:

1. A Tauri v2 shell renders trusted, bundled ShackCQ React assets and exposes only a typed `DigiSessionClient` command/event surface.
2. A supervised, per-user station runtime owns the Nexus audio/DSP/decoder/encoder/sequence lifecycle, durable local session state, operating leases, clock-quality decisions and pending reviewed-contact delivery.

The runtime links the selected Nexus source at immutable upstream commit `7618390658f8f92431dec0ac65979b84f2c0fb76` (`v1.10.3`) through a small ShackCQ adapter. It does not rebrand `mfsk-core`, run an external WSJT-X/JTDX process, or silently fall back to the legacy Digi engine. Nexus's upstream Tauri shell is not embedded wholesale: its unrelated loggers, propagation/network features, updater identity and radio auto-discovery are outside this product boundary.

## Ownership map

| Responsibility | Sole owner |
|---|---|
| Selected input/output, sample conversion and Digi decoder/encoder | Nexus-native station runtime |
| CAT/serial/network radio route and readback | Existing `shackcq-hamlib-helper` through `DesktopRadioController` |
| Active Digi session, sequence, clock checks and STOP state | Nexus-native station runtime |
| Desktop/browser/cloud arbitration | Runtime lease and generation controller |
| Agent pairing and outbound cloud socket | Existing `CloudAgentClient` identity |
| Canonical contact and Local/Wavelog delivery | Existing hosted Phase-2 ingress/outbox |
| Shared Digi presentation and transport-neutral client contract | `ShackCQ-Web/packages/shared-digi-ui` |

The active Nexus session disables the legacy Qt Digi audio/decoder path. The adapter cannot start `rigctld`, native CI-V, OmniRig, Flex or another CAT owner. Radio requests cross the existing bounded helper boundary and require its generation/readback rules.

## Frontend revision boundary

`ShackCQ-Web/packages/shared-digi-ui` is the source of truth. Desktop packaging consumes a deterministic generated snapshot and records the exact Web commit plus content digest in its build manifest. A verification script fails when the generated snapshot does not match that pin. Website and desktop releases therefore remain independently deployable while their versioned contract stays explicit.

Three adapters implement the same narrow client interface:

- Tauri local IPC is the primary low-latency path.
- An explicitly enabled, loopback-only browser bridge uses origin/host validation, a local approval challenge and short-lived scoped capabilities.
- The existing outbound cloud relay is capability-limited monitoring and reviewed-request transport; it never carries PCM or schedules transmissions.

Transport changes are explicit. A disconnected local transport never silently becomes a cloud control path.

## Safety and persistence

The package starts inert and RX-safe. Selecting devices changes preferences only; opening input requires `Start receiving`. Output, PTT, TUNE and RF remain unavailable while production `SHACKCQ_DIGI_TX_ENABLED=false`. Encoder and sequencer tests use only null/file sinks. STOP is local, idempotent, independent of the normal command queue and latches `RX_UNCONFIRMED` when Hamlib readback cannot prove receive state.

Armed state, executable waveforms and control leases are never restored. Account/station-partitioned pending contacts are durable, reviewed before upload and removed only after a durable canonical receipt. Nexus-native contact provenance is adapted directly to Phase-2 ingress; Nexus's own external logbook uploaders remain disabled.

## Lifecycle and migration

The Tauri shell supervises the runtime and bundled helper with bounded restart attempts. It refuses hardware ownership while a legacy ShackCQ Agent owns the same route and offers only an explicit, rollback-preserving handover. Closing a window leaves no invisible radio owner by default: active receive prompts for `Stop and quit` or an explicit background/tray choice. A UI reload reconnects to a runtime snapshot rather than opening devices again.

The package remains a software/RX review candidate until owner device checks are complete. Physical audio/CAT acceptance is pending; RF transmission is not authorized; production Digi TX remains disabled.
