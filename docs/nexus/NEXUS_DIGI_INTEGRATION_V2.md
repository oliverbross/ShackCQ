# Nexus Digi completion integration v2

## Scope and provenance

This Android-only programme was implemented from frozen ShackCQ main SHA
`ebabe98967bfe24e53e16f33f839d711ec237f03` on
`feature/nexus-digi-integration-v2`. The current Nexus review is pinned in
`UPSTREAM.json`; no current Nexus source was copied. Historical imports remain
attributed in `rust/shackcq-flex/UPSTREAM.md`.

Excluded: desktop/Tauri/React architecture, Hamlib, WebView, TempoFast/Deep,
QPSK31, APRS, Fox/Hound, contest workspaces, Nexus chat/presence/HARQ/Roam,
serial FSK and new mode families.

## Authorities

| Concern | Single authority |
|---|---|
| mode truth | `DigiCapabilities` |
| USB/Flex audio ownership | existing audio/Flex controllers |
| CAT/PTT/RX confirmation | existing transport/Flex controllers |
| canonical QSO writes | `QsoMutationCoordinator` |
| Wavelog delivery | existing Wavelog outbox |
| CTY/worked/confirmed/Needs/watchlist | existing ShackCQ repositories |
| satellite pass prediction | existing satellite operations controller |

## Implemented contract

- Native spectrum: 384 bins, 10 Hz publication, selectable window and exact
  shared decoder cursor. Waterfall: LIVE/PAUSED/snap-to-live, 900 rows.
- Typed audio health covers route/source, format, levels, clipping, frames,
  route loss and owner. Exact USB route loss stops RX and clears TX.
- Separate `shackcq-digi.sqlite` holds bounded sessions, decodes, drafts,
  gallery metadata and meta schema. SSTV pixels and raw audio are files only.
- FT8/FT4 provide Classic/Roster views, filters, enrichment, CQ/answer
  sequencing, base-call lock, bystander rejection, explicit TX enable and
  one-shot arm. Auto-log defaults off.
- Other slotted modes are manual. RTTY click-to-net sets the consumed
  mark/space centre. BPSK31 click-to-net sets the consumed carrier and resets
  acquisition. CW pitch/WPM are bounded.
- SSTV previews the exact center-cropped/offset/overlay pixels sent, saves PNGs
  atomically and supports share/pin/delete. HEIC success is never claimed unless
  Android decodes it.
- ISS SSTV is an explicitly enabled receive-only pass session using the existing
  pass source, reviewed 145.800 MHz FM tune request and AOS/LOS lifecycle.
- WSJT-X UDP is disabled by default, loopback `127.0.0.1:2237` by default,
  emits Heartbeat/Status/Decode/QSO Logged and accepts only bounded safe command
  types. Companion mode disables local modem/PTT authority.
- Raw WAV capture is operator-started, mono PCM16/12 kHz, 10 minutes maximum,
  seven-day retention and 100 MB quota.

## Safety and persistence

Settings never persist TX arm, PTT, active transmit, sequencer-transmitting
state, selected SSTV pixels or temporary audio. Background, route/radio changes,
mode changes and close clear TX. Every transmit rechecks mode, frequency and
session TX enable immediately before PTT, has a finite cap, and requests RX on
completion/stop.

No APK was installed, no app data was cleared, no protected tablet setting was
changed, and no feature branch was merged to `main`.

## Evidence boundary

Source/build/unit/native/watcher evidence is recorded by the completion run.
Physical USB audio, decoded RF, PTT/TUNE, SSTV safe-load TX, Flex RF,
authenticated Wavelog delivery and live UDP peer interoperability require the
separate acceptance checklist and are not inferred from builds.

## Completion record

### Git

- Starting SHA: `ebabe98967bfe24e53e16f33f839d711ec237f03`.
- Final implementation SHA: `3ad99d1271102bd2d351f7887bbbfd5882925187`.
- Commits:
  - `8d32273ac517eed4dc51a06e6dd0c14416f3ae5f` — Nexus provenance,
    watcher, native spectrum and exact RTTY/PSK tuning bridge.
  - `3ad99d1271102bd2d351f7887bbbfd5882925187` — Android cockpit,
    settings/storage/UDP/gallery, workflows and focused tests.
  - documentation-only completion commit containing this record.

The pushed documentation commit and final local/remote equality are reported in
the final handoff because a Git commit cannot contain its own SHA.

### Watchers and provenance

- Nexus: **NO CHANGE**, exit 0. Reviewed/current commit
  `57d11fd55f098dc9302b6aafed39e6cd4b6db216`, tree
  `ed7ae002f93d996afaf4184cc572138ad1346b17`, package `1.7.5`.
- Wavelog: **NO CHANGE**, exit 0. Release `3.1.0`, commit
  `af3256140bd05403b7c4a421746c2ea653a4f04f`; pinned paths reachable.
- OpenHamClock: **REVIEW REQUIRED**, expected exit 2. Stable `main` is
  unchanged at `d4a50eaaa61d3432a1de5f80cbe61790739930a5` / `26.5.0`.
  Preview `Staging` advanced from `36e5c1262dfde2057b2b4e6483be8c2215c70ad4`
  to `99913f2df574b8588ddaff703581b8f341f46761` in satellite telemetry
  paths; this does not authorize a pin or product-source update.
- Original Nexus imports: `6ec4a7925f1550cc364c7fd95967ce38c696ad3f`;
  earlier parity audit: `750407eafd60905550e561be2eacec642751fc51`.
- Current Nexus licence: GPL-3.0-only; COPYING SHA-256
  `b4bbff835ca86b6051284a77138b7b31db215fb3e8aa70221464126cc9ad60fa`;
  NOTICE SHA-256
  `40d10b6cf7a0bdb9e10ed34f4719539dd433aceb6cf844347f189d1c8b362a88`.

### Mode capability matrix

| Modes | RX/TX engine | Fixture truth | Sequencer | Boundary |
|---|---|---|---|---|
| FT8, FT4 | RX + TX | RX/TX verified | FT8/FT4 automatic | explicit operator start, TX enable and one-shot arm |
| FT2 | RX + TX | RX verified; TX manual | manual | no automatic QSO state machine |
| FST4 15/30/60/120/300 | RX + TX | RX verified; TX manual | manual | no automatic QSO state machine |
| Q65 15/30/60/120/300 A-E | RX + TX | RX verified; TX manual | manual | exact chosen variant; no automation |
| MSK144 5/10/15/30 | RX + TX | RX verified; TX manual | manual | no automatic QSO state machine |
| JT65 A/B/C | RX + TX | RX verified; TX manual | manual | no automatic QSO state machine |
| WSPR | RX + TX | RX verified; TX manual | manual | beacon text only; no contact sequencer |
| CW | RX + TX | RX verified; TX manual | manual | pitch/WPM, one-shot TX |
| RTTY | RX + TX | RX verified; TX manual | manual | exact 170 Hz centre, reverse, transcript limit |
| PSK31 | RX + TX | RX verified; TX manual | manual | BPSK31 only, bounded reacquire |
| SSTV | RX + TX | RX verified; TX manual | manual | supported existing variants; exact-image preview |

No capability is `MISSING` or `UNAVAILABLE_ENGINE`; hiding is controlled by
the same typed registry/settings document. QPSK31 and every frozen excluded
product/mode domain remain absent.

### Architecture and storage

Added `DigiDomain.kt`, `DigiSessionStore.kt`, `DigiRawRecorder.kt`,
`DigiWsjtInterop.kt`, the native spectrum/tuning ABI, one upstream watcher and
its weekly workflow. `shackcq-digi.sqlite` schema v2:
`decode_event`, `digi_session`, `qso_draft`, `sstv_gallery`,
`digi_meta`. Bounds: 3,000 live rows; seven days/20,000 durable decodes;
90-day completed sessions/drafts; gallery 100 MB default/250 MB maximum.

The 384 x 900 float waterfall is about 1.32 MiB, publishes at most 10 Hz and
shares the selected frequency with each decoder. Audio health/recovery covers
USB/Flex source, route identity, format, level/clipping, frame age/count,
ownership and route loss. Returning an exact route may resume RX only; TX never
resumes.

FT8/FT4 supply Classic/Roster, batch enrichment, base-call station lock,
bystander rejection, deterministic exchange states and reviewed QSO drafts.
Other slotted modes remain manual. RTTY, BPSK31 and CW expose their documented
manual tuning/TX limits. SSTV supplies exact prepared pixels, atomic private PNG
gallery, metadata/share/pin/delete and receive-only ISS AOS/LOS handling.

QSO writes use `QsoMutationCoordinator`; delivery uses the existing Wavelog
outbox. Worked/confirmed/Needs/watchlist/CTY enrichment uses existing
repositories in batches. UDP emits canonical Heartbeat/Status/Decode/QSO Logged,
accepts bounded Halt/Clear/Replay only, defaults off/loopback, and Companion mode
removes local decoder/PTT authority.

Settings are versioned and never persist arm/PTT/active TX. Setup is reachable
from Digi and Settings. Diagnostics retain 20 sanitized state/error rows.
Compose semantics are present; physical keyboard/accessibility acceptance
remains pending. Background, route/radio changes, mode change and close clear TX
and request RX; all sends use finite watchdogs and pre-PTT revalidation.

### Validation and artifacts

- Nexus/Wavelog/OpenHamClock watchers: run as described above.
- Rust: `cargo test --locked` — 97 passed, 0 failed, 1 ignored.
- Android: `:app:testDebugUnitTest`, `:app:assembleDebug`,
  `:app:bundleDebug`, `:app:compileDebugAndroidTestSources` — build
  successful. Focused coverage is exactly 3 Android files / 24 cases.
- Shared core: SDK CMake 3.22.1 configure/build and CTest — 2/2 passed.
- Package audit: ITU/P.533 scan PASS for APK and AAB.
- `git diff --check`: clean before commits.

APK:
`android/app/build/outputs/apk/debug/app-debug.apk`, 110,437,257 bytes,
SHA-256 `41887ea94b24fc795db4038f9dd11618cc46ff9576b1354820da4afc3bbbeaba`.

AAB:
`android/app/build/outputs/bundle/debug/app-debug.aab`, 51,797,260 bytes,
SHA-256 `fbb02d4b7ae9cae68217a3bff828c38c7a14574097da763e4c73c275d7ad9940`.

Final parity counts: NATIVE 10, DELEGATED_TO_SHACKCQ 3, PARTIAL 3,
DESKTOP_NOT_APPLICABLE 1, EXCLUDED_MODE 1, MISSING 0.

Physical USB/audio/RF/PTT/TUNE, live Flex, SSTV safe-load transmit,
authenticated Wavelog delivery, real WSJT-X peer interoperability and tablet
accessibility were not performed. No APK install or deployment occurred.

## FT8/FT4 sequencer hardening

The FT operator workflow now uses `DigiFtExchangeEngine`, a pure deterministic
CQ-runner and search-and-pounce state machine. Every outgoing message is an
explicit action, and a state transition occurs only after a typed TX outcome
confirms encoding, PTT, complete audio delivery and return to RX. Refused PTT,
audio failure, clock/context change and RX-unconfirmed outcomes stop automation.

The scheduler uses UTC wall time to select FT8/FT4 parity and monotonic time for
the wait. It permits only a 120 ms late-start window, revalidates clock, radio,
mode, frequency and TX enable immediately before PTT, and exposes the selected
FIRST/EVEN or SECOND/ODD slot plus countdown. CQ parity has one persisted
authority. Search-and-pounce parity is derived as the opposite of the selected
captured decode slot. The earlier hardening statement that captured slot timing
was retained end-to-end was incomplete: the production model and schema still
rounded it to whole seconds. Final closure replaces that field with exact
`slotStartMillis`, including FT4 `.500` boundaries, through decode, schema-v2
storage, UDP, selection, sequencing and re-decode.

Reports are derived from the associated decode SNR and clamped to FT syntax.
Sent and received reports remain distinct, and automatic draft creation requires
a completed standard exchange with both reports. Retry limit defaults to 3 and
is constrained to 0–10. Auto-CQ defaults off, requires explicit operator start,
and stops at its configured transmission limit. ISS enable stops TX first;
ISS AOS/LOS owns and stops only the receive session it started, while ordinary
SSTV auto-arm is suppressed during an ISS session.

Hardening validation: 408 Android host unit tests passed; debug Android-test
sources compiled; APK and AAB assembled; Rust passed 97 with 1 ignored; shared
core CTest passed 2/2. Package scans passed. The debug APK is 115,051,218 bytes,
SHA-256 `c074531cdc0c993c6337a6c0c35cd5ba700c0e1a7d79d8f92b029927a8ff32d6`.
The debug AAB is 51,817,294 bytes, SHA-256
`734e30ae166f50287b6b057e9afc7afa438fccdf054e0e487561d25d3cfc1c1d`.
Physical radio/audio/RF and device UI acceptance remains pending; no install or
deployment was performed.

## Final production-path closure

Schema v2 and the production adapters now preserve exact FT4 milliseconds,
type decode sources, use monotonic runtime countdown, require confirmed Flex
post-stop receive state, latch RX-unconfirmed recovery, and expose truthful
automatic/manual sequencing. Final closure validation passed 422 Android host
unit tests, Android-test source compilation, APK/AAB builds, 97 Rust tests
(1 ignored), shared-core CTest 2/2, and both package scans. Exact evidence and
artifact hashes are in `NEXUS_DIGI_FINAL_PRODUCTION_CLOSURE.md`.
