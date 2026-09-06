# ShackCQ roadmap

This is the normative product order. A phase starts only after separate owner authorisation and the previous gate is reviewed. Dates and effort estimates are intentionally absent.

## Phase 0 — Repository truth and product alignment

- Reconcile active contracts and historical evidence.
- Adopt GPL-3.0-only and record corresponding-source obligations.
- Audit direct dependencies, incorporated material, service terms, and provenance risks.
- Inspect Nexus at an immutable commit without importing code.
- Establish source-backed feature and build baselines.
- Record architecture boundaries and one next candidate phase.

**Gate:** Contracts agree, applicable builds pass or have exact blockers, no Nexus code is imported, distribution risks are bounded, branch is reviewed.

## Phase 1 — KX3/KX2 Studio

### Phase 1A — Panadapter completion and audio-source architecture

- Audit and harden the existing physical-I/Q implementation rather than rewrite it.
- Complete device selection and persistence.
- Measure I/Q orientation, image rejection, and calibration.
- Verify CAT/spectrum alignment and rendering/interaction parity.
- Provide the foundation for operating profiles and later spot overlays.

**Excludes:** EQ, voice macros, portable-programme feeds, generic radio abstractions, and Nexus integration.

### Phase 1B — RX/TX EQ Studio and operating profiles

- Real KX3/KX2 EQ read/write.
- RX/TX profiles and microphone/audio-device association.
- Safe before/after monitoring.
- Restore/rollback semantics.
- Profile foundation shared with macros and portable operation.

### Phase 1C — Voice macros

- Recorded/imported audio, preview, and session variables.
- Digirig-compatible routing only where physically supported.
- Explicit PTT, timeout, abort, and no unattended transmission.

### Android Phase 1 closure status

- Phase 0 is complete.
- Android Phase 1A/1B/1C are implemented and integrated behind one exclusive audio-owner contract.
- Android panadapter software/device behavior is fail-closed; physical KX3 quadrature-I/Q RF acceptance remains deferred.
- iPadOS KX3 Studio parity for EQ Studio and SSB voice macros is the next candidate.
- Phase 2 is not authorised by this closure.

## Phase 2 — Portable Chase v1

- POTA, SOTA, and WWFF only.
- Source-stamped offline reference databases and a safe updater.
- Unified activity stream, local worked status, opportunity ranking, panadapter overlays, and operator-confirmed tune.
- Decide Nexus propagation/needs/provider reuse component by component from the Phase 0 assessment.

**Gate:** Data access/licensing confirmed; no false universal coverage.

## Phase 3 — Portable Activate v1

- Activation sessions, fast logger, multi-reference handling, P2P/S2S, supported spotting, crash recovery, and programme-aware ADIF/export.
- Do not claim a universal upload route.

## Phase 4 — Sync and Progress v1

- **Phase 4A implemented on Android:** local-authority Sync Hub, independent durable QRZ/Club Log/eQSL delivery, explicit historical catch-up, secure credentials, truthful provider attention states, and Wavelog duplicate-prevention gating.
- Upload acceptance is tracked without fabricating remote confirmation. QRZ/Club Log/eQSL confirmation import remains later work.
- **Phase 4B next:** Needs Board and useful activation/award statistics.
- Keep direct LoTW signing later because certificate/key handling is materially different.
- Review any Nexus connector, queue, logbook, or award component before reuse.
- SOTA live still requires written API approval; iPadOS parity remains deferred; no Nexus source is incorporated.

## Phase 5 — FlexRadio SmartLink

### Phase 5A — Legitimate authentication, discovery, and control

- Use an official developer path and legitimate SmartLink authentication.
- Secure token storage, discovery/brokering, recovery, slice/frequency/mode state, tune/log integration.
- Revalidate Nexus protocol candidates against current official interfaces; never reuse another application's client identity or credentials.

### Phase 5B — Spectrum, waterfall, and meters

- Official TCP/UDP/VITA-49 interfaces.
- Panadapter/waterfall streams, meters, and ShackCQ overlays.

### Phase 5C — Remote audio and controlled TX

- Only after 5A/5B physical proof.
- Explicit operator control and safe recovery.
- No attempt to clone every SmartSDR or StationPilot feature.

## Phase 6 — Desktop client

### Phase 6A — Qt 6/QML/CMake foundation

- One Qt/QML desktop UI consuming the shared C++ core.
- Desktop-specific adapters and the Flightline design language.
- Nexus Tauri/React remains reference-only.
- A selected audited Rust static library with a narrow C ABI is permissible only when a real component justifies the extra runtime boundary.

### Phase 6B — macOS

### Phase 6C — Windows

### Phase 6D — Linux

Each platform gate covers packaging, credentials, serial/audio adapters, and physical validation without forking the desktop UI unnecessarily.

## Phase 7 — Broader radio and programme ecosystem

- QMX only when hardware is available.
- rigctld compatibility where useful.
- IOTA, beaches, bunkers, lighthouses, and other programmes only after data/licence review.
- No promise of universal rig or programme parity.
