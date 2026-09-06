# Final whole-application completion matrix

## Multiplatform candidate delta

Hardened Android and Windows Desktop Alpha are integrated without changing either source branch or `main`. Windows has a bounded local service graph, schema-16 semantic contract, fake-service Wavelog coverage and hosted packaging path; full desktop parity and physical/live acceptance remain incomplete. Native SwiftUI iOS and macOS Qt proof are separate build layers.

## Tablet Acceptance Sweep 2 delta

Sweep 2 adds Contest schema-2 staging/merge, validated private SCP, Band Maps v2 and shared Radio ladder, Intelligence presentation/map corrections, typed portable catalogue truth and Groups.io foreground sync settings. Final hosted/device evidence remains governed by `TABLET_ACCEPTANCE_SWEEP_2_LIVE_CHECKLIST.md` and cannot be inferred from source completion.

## Tablet Acceptance Sweep 1 delta

The fix/tablet-acceptance-sweep-1 source closes the 41 owner-observed acceptance items recorded in TABLET_ACCEPTANCE_SWEEP_1.md: Home/Settings convergence, complete Contest workspaces, interactive Band Maps, authority-scoped Log Intelligence, tablet layouts, Portable map/browser corrections, catalog-truth Operations, Groups.io server timestamps, and shared CS/DS presentation. This row is source-complete only; exact-SHA hosted, protected-tablet presentation, authenticated-service, audio and RF evidence remain separate gates.

| Area | Classification | Evidence boundary |
|---|---|---|
| Android production graph and navigation | SOURCE_COMPLETE | One construction graph; each required top-level destination appears once; DX Chaser remains inside Digi |
| Typed operating context and handoffs | SOURCE_COMPLETE | Generation-safe router; Band Maps supports RX review, DX, history, Callbook, Contest, Digi, Chaser and portable handoffs without action authority |
| Band Maps | SOURCE_COMPLETE | Snapshot consumers, 20,000-input cap, bounded projections, preferences-only persistence, no network/spot DB/TX owner |
| Keyer / Contest / N1MM / DX Chaser / Digi | SOURCE_COMPLETE | Canonical mutation path, explicit arms, local-decode eligibility and idempotent global Stop |
| Wavelog / Neural / HamClock / Groups.io / Operations / Satellites | SOURCE_COMPLETE | Existing owners retained; regression gates remain mandatory |
| Configuration / Health / privacy | SOURCE_COMPLETE | Safe-section fixture, previewed transactional restore, metadata-only diagnostics/support bundle |
| Physical tablet, radio, audio, live services and RF | LIVE_ACCEPTANCE_PENDING | Never inferred from source or builds; use `FINAL_INTEGRATED_LIVE_ACCEPTANCE.md` |
| Full Apple parity for Contest/Band Maps/Chaser | PLATFORM_FUTURE | Unified Apple build is still mandatory |
| Local P.533 | LICENCE_BLOCKED | No ITU payload; existing `LICENSE_BLOCKED` truth retained |
| APRS, Winlink, WWBOTA, hazards, Meshtastic/MeshCom, extra Digi modes | OPTIONAL | Explicitly outside this release programme |
| Desktop shells | EXCLUDED | Windows/macOS/Linux shells are not part of this convergence |

Mandatory build, watcher, package, scale, hosted exact-SHA, push and device results are recorded in `SHACKCQ_FINAL_WHOLE_APP_CONVERGENCE.md`; a source-complete row alone is not release evidence.

## Sweep 2 radio and rotator integration

| Area | Verdict | Boundary |
|---|---|---|
| Stable radio profiles and one owner | SOURCE_COMPLETE | Native KX/Flex/QMX/RGO plus dynamic Hamlib profiles; restore is disconnected. |
| QMX/QMX+ | SOURCE_COMPLETE; LIVE_ACCEPTANCE_PENDING | CAT/controller wired; exact UAC/IQ/audio and hardware evidence pending. |
| RGO ONE | SOURCE_COMPLETE; LIVE_ACCEPTANCE_PENDING | V6 requires explicit profile plus ID 006; legacy/unknown is read-only. |
| Hamlib radio and rotator | SOURCE_COMPLETE | One vendored 4.7.2 archive/JNI bridge; physical models pending. |
| Rotator workspace and safety | SOURCE_COMPLETE; MOTION_PENDING | One owner, explicit motion review, session-only automation, immediate STOP. |
| Protected tablet | DEVICE_ACCEPTANCE_PENDING | Backup, `adb install -r`, launch and persistence evidence required. |

Sweep 3 makes Radio, Hamlib, QMX/QMX+, RGO ONE, Rotator and WWFF operator-reachable through Settings and workspace routes. CQ/ITU/state geometry remains `PROVIDER_BLOCKED / UNAVAILABLE_DATA`; physical radio, RF and rotator-motion acceptance remains pending and is never inferred.

## Android lifecycle hardening delta

| Area | Verdict | Boundary |
|---|---|---|
| JNI handle owners | SOURCE_COMPLETE; SANITIZER_PASS | Feature, base CAT, Digi, Flex, Panadapter and Hamlib radio/rotator use checked retirement; stateless satellite/propagation calls remain stateless. |
| Audio, map and browser lifecycle | SOURCE_COMPLETE | Audio callbacks retire before release; seven maps and two WebViews reject late callbacks and dispose deterministically. |
| QSO schema 16 | SOURCE_COMPLETE; REOPEN_FIXTURE_COMPILED | Canonical row, projection relationship and settings metadata survive close/reopen; no downgrade/recreate. |
| Hosted exact SHA | PASS | Release-candidate run `32784249372` passed all seven jobs at `826ba3031d869f12e0c9d37649257f9b2fac1ecf`; the final documentation-only tip is rerun externally. |
| Protected tablet | PROCESS_ACCEPTANCE_PASS; VISUAL_NAVIGATION_BLOCKED | Backup/hash, compatible in-place install, UID/data/schema/QSO preservation, relaunch cycles and locked-state process soak pass. Secure keyguard prevented visible workspace navigation and true unlocked foreground-provider soak. |
| Authenticated services, physical audio/radio/RF/rotator | LIVE_ACCEPTANCE_PENDING | Never inferred from source, package, process or navigation evidence. |

## Windows desktop full-parity v1 addendum

| Layer | Status | Evidence |
|---|---|---|
| Windows navigation/provider/data foundation | SOURCE_COMPLETE | 19 destinations, one owner graph, five versioned domain stores, 17 bounded providers, global Stop. |
| Android feature parity | PARTIAL | 14/31 audited rows are source-complete; 17 remain wired foundations. |
| Local desktop/gallery/scale | PASS | 6/6 tests; 75 distinct frames; 100k QSO and feature scale fixtures. |
| Windows package | HOSTED_PENDING | Exact-SHA Windows ZIP/NSIS workflow added; local macOS host cannot produce acceptance-grade Windows artifacts. |
| Live hardware/services | PENDING | No authenticated service, audio, CAT/PTT/TUNE, RF or movement acceptance performed. |

## Desktop Flightline UI convergence addendum

| Area | Status | Evidence boundary |
|---|---|---|
| Tablet visual source | PASS | 41 new unlocked, non-blank 2944×1840 captures; private raw images remain ignored. |
| Desktop shell/menu/icons | SOURCE_COMPLETE | 48 canonical commands, 19 destinations, 40 packaged SVGs, native macOS/global and Windows/Alt menu structures. |
| Responsive visual gallery | HOSTED_PENDING | Final exact-SHA Windows four-profile and macOS five-profile 58-frame artifacts required: 39 operating and 19 Edit Layout frames per profile. |
| Functional parity | UNCHANGED | UI convergence does not promote the existing 14/31 source-complete total. |
| Physical/live/release | PENDING | No authenticated service, audio, CAT/PTT/TUNE, RF, movement, signing, notarization, publication or deployment claim. |

## Desktop Functional Parity Closure v1

The desktop source matrix is now 31/31 source-complete with zero foundation or missing rows. This does not change the `PENDING` physical/authenticated/release boundary; exact-SHA hosted results and package hashes are recorded separately for the candidate SHA.

## Multiplatform RC1

The RC1 owner and platform matrices extend closure across Android, iOS, Windows and macOS: `FOUNDATION_WIRED = 0` and `MISSING = 0`. Source-complete rows that depend on providers, radio, audio, RF, motion or physical presentation are labelled `SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING`; build evidence does not promote them.

## Android SDRoxide Enhancement Pack v1 candidate

Twelve Android-visible areas are source-complete: TCI, multi-receiver, I/Q, RX audio, Panadapter/waterfall, scanner, band stacks, RX DSP, RF map, RF globe, Digi paths, and spoken announcements. Deterministic tests do not promote physical TCI/RF/audio or protected-tablet acceptance; those rows remain `SOURCE_COMPLETE_LIVE_ACCEPTANCE_PENDING`.

## Android SDRoxide Operational Enhancements v2 candidate

| Layer | Status | Boundary |
|---|---|---|
| TCI control/readback and receiver links | SOURCE_COMPLETE | Receive/control only; spot and diversity unavailable by protocol audit |
| Dual RX mixer, Panadapter v5, time-shift and skimmers | SOURCE_COMPLETE | Bounded queues/frames/candidates; physical audio and decoder quality pending |
| Scanner banks/priority/journal/capture policy | SOURCE_COMPLETE_WITH_AUDIO_CAPTURE_UNAVAILABLE | IQ-display bookmark only; no silent audio capture |
| Per-mode TX audio | SOURCE_COMPLETE_CONFIG_ONLY | Production send and physical acceptance locked |
| Desktop/iOS | UNCHANGED | Shared-code regression only; no UI or feature promotion |
| Hosted/device/live | PENDING | Must be recorded separately on the exact final SHA |

## Android Local SDR Receiver v3 candidate

| Area | Source status | Evidence boundary |
|---|---|---|
| Shared local receiver DSP and two Android virtual receivers | SOURCE_COMPLETE | Physical I/Q/audio/RF remains pending |
| USB/LSB/CW/DIGU/DIGL/DSB/AM/SAM/NFM/SPECTRUM | SOURCE_COMPLETE | Native golden vectors and Android visibility |
| CTCSS/DCS and WFM stereo/RDS | SOURCE_COMPLETE_CAPABILITY_GATED | WFM requires at least 192 kHz; live station accuracy pending |
| Click-to-listen, markers and passbands | SOURCE_COMPLETE | Local NCO only; physical receive review is separate |
| PCM16 recording, pre-roll and Scanner AUDIO hit | SOURCE_COMPLETE_EXPLICIT_ONLY | One recording, quota/retention and visible indicator |
| Settings, Health, privacy and debug lab | SOURCE_COMPLETE | Debug state is `DEMO · NO RADIO` |
| Windows/macOS feature/UI | UNCHANGED | Shared-core regression only |
| Hosted/protected tablet/live | PENDING | Exact-SHA and conditional device evidence recorded separately |

## Android SDR Operator Workbench v4 candidate

| Area | Status | Evidence boundary |
|---|---|---|
| Capture/replay/history | SOURCE_COMPLETE | Atomic float32 I/Q, offline seek/speeds and historical truth; live/audio acceptance separate. |
| Measurements/tracker/monitors/calibration | SOURCE_COMPLETE | Relative until user-calibrated; local follow only; four monitors; no tone invention. |
| Spectrum Survey/Intelligence/scanner | SOURCE_COMPLETE | Schema-2 derived aggregates with retention/caps; history is not current RF. |
| Memories/band stacks/Settings/Health/privacy | SOURCE_COMPLETE | Validated import/export and bounded metadata; private payload exclusions. |
| SDRoxide final parity | SOURCE_COMPLETE | Final classified-family matrix has `IMPLEMENT_V4=0`; excluded/deferred rows remain deliberate. |
| Desktop feature/UI | UNCHANGED | Regression evidence only. |
| Hosted exact SHA/packages | PENDING_UNTIL_FINAL_SHA | Mandatory workflow and final hashes cannot precede the immutable commit. |
| Protected tablet/unlocked visual/live RF/audio | CONDITIONAL_PENDING | Never inferred from debug/build/package evidence. |

### Android TCI Transmit v5

| Surface | State | Boundary |
|---|---|---|
| Single TX authority and audited TX audio | SOURCE_COMPLETE | Digi/SSTV/CW/Voice/Stop route through one adapter; no direct feature writes. |
| PTT/Tune/interlocks/RX recovery | SOURCE_COMPLETE | Readback-gated; ambiguous recovery latches `RX_UNCONFIRMED`. |
| Acceptance/profile/UI/Debug Lab | SOURCE_COMPLETE | Production starts unverified; demo is session-only and labelled no-radio. |
| Physical PTT/Tune/RF | PENDING | Requires exact-device controlled acceptance; not inferred from fake/build evidence. |
| Hosted/package/tablet | PENDING_UNTIL_FINAL_SHA | Recorded only at immutable candidate SHA. |

### Secure Remote Station v6

| Surface | State | Boundary |
|---|---|---|
| Protocol, TLS, pairing, roles, sessions and leases | SOURCE_COMPLETE | TLS 1.3, pinned identity, bounded frames, revocation and generation checks; deployment acceptance separate. |
| stationd and Android Remote Station client | SOURCE_COMPLETE | Windows/macOS/Linux service and native Android backend reuse existing owners. |
| State, spectrum/waterfall and RX audio | SOURCE_COMPLETE | Derived spectrum remains labelled derived; optional IQ and TX media are bounded and capability-gated. |
| TCI, rigctld, Digi/Keyer/Voice, TX and rotator paths | SOURCE_COMPLETE | All mutations pass existing authorities; unsupported capability fails closed. |
| Physical/authenticated/RF/rotator-motion acceptance | PENDING | Never inferred from deterministic fixtures, builds, packages or device launch. |

## Final 0.1.0 RC1 parity closure

| Final domain | Status | Evidence boundary |
|---|---|---|
| Linux full GUI and stationd | SOURCE_COMPLETE | Native x86_64 GUI/package and arm64 stationd hosted gates required. |
| SwiftUI and Qt Remote Station clients | SOURCE_COMPLETE | Pinned TLS, secure identity, safe controls, RX audio/spectrum; WAN/live acceptance pending. |
| Adaptive Opus / PCM fallback / optional raw I/Q | SOURCE_COMPLETE | Protocol tests and packages required; physical audio/IQ pending. |
| Android MIDI/HID controls | SOURCE_COMPLETE | Build/tests complete; specific physical controllers pending. |

Final totals: `FOUNDATION_WIRED = 0`, `MISSING = 0`.
