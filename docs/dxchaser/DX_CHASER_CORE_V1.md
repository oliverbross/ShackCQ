# ShackCQ DX Chaser Core v1

## Provenance and ownership

- Immutable base: `b4f12e17fa87df16d2094b518ae187553e370be5`
- Branch: `feature/dx-chaser-core-v1`
- Upstream: `kd9taw/mshv-auto-dx-chaser` commit `d960ae22de78940c6be9d95bd4817d233d02ee39`, tree
  `52d5b2e4d39a8e174000971ada3ac0c9f0442625`, GPL-3.0.
- Implementation: independent Kotlin/Compose design. No upstream source, fixture, Qt/provider/radio code or Club Log-derived data was
  copied or adapted; `NOTICE` is unchanged.
- Core ownership remains confined to `android/app/src/main/java/app/shackcq/mobile/dxchaser/`. The semantic integration adds only
  narrow adapters, production lifecycle/navigation wiring, focused tests and integration documentation outside that package.

## Product architecture

- `DxChaserModels`: versioned immutable snapshot, candidate, target, action, integration and Band Maps read-only contracts.
- `DxChaserScorer`: deterministic `ShackCQ DX Chaser Score v1` reduction and stable tie-break ordering.
- `DxChaserEngine`: pure event/state reducer for Assist, explicit Chase Session and Dry Run.
- `DxChaserController`: the single future controller type, single-thread off-main reduction, generation rejection and idempotent close.
- `DxChaserPorts`: narrow input/output, future Digi/review/QSO adapters and bounded journal contract.
- `DxChaserStore`: private schema-v1 SQLite journal and inactive settings store.
- `DxChaserSettings` / `DxChaserRarityParser`: clamped settings plus bounded provenance-bearing manual JSON rarity import.
- `DxChaserScreen`: native responsive Compose workspace, integrated as the DX Chaser subpage of the existing Digi destination.

## Eligibility and score truth

Eligibility requires FT8/FT4, selected mode/band, exact timing, `LIVE_CAPTURE` in the current Digi session or exact current-slot
`REDECODE_LIVE_SLOT`, matching station/radio/band/mode/dial frequency, bounded age, valid non-operator callsign, CQ/directed CQ or
operator-addressed message, no known bystander exchange/cooldown, foreground state and a safe radio/route/audio snapshot.

Cluster, RBN, PSK, personal WSPR, Band Health and Neural evidence can only add bounded context. They cannot create a candidate.
Current observed, future empirical and historical personal value remain separate fields; no probability is claimed. Missing Needs
or rarity data stays `UNAVAILABLE` and contributes no fabricated need/rank.

The v1 score combines bounded award/Needs value, local SNR/age/repeat/stability, current corroboration, empirical outlook, personal
history, optional reviewed rarity and explicit penalties. Ordering is eligible first, score, need class, local evidence, SNR, repeat,
recency and callsign. Display and operational lists are capped at 50 and 12.

## State, persistence and safety

Sessions distinguish monitoring, selection, pending preparation, sequence start, calling, response wait, engagement, QSO outcome,
cooldown and terminal states. Before engagement, pre-emption requires the configured dwell and percentage hysteresis and no active
TX/sequence. After an attributable response, engagement lock prevents all pre-emption. Background/context/radio/route/mode/frequency/
station loss and close stop the session and clear the pending intent.

Defaults are 3 normal, 6 scarce and 10 ATNO attempts; hard maxima are 10, 12 and 20. Pre-engagement, engaged and whole-session hard
timeouts are 10 minutes, 10 minutes and 2 hours. Recent attempt, completed QSO and cross-band review cooldowns are finite. Active
session, target, engagement, pending intent, TX enable/arm and sequencer/PTT state are never persisted.

`shackcq-dxchaser.sqlite` schema 1 contains `dxchaser_session`, `dxchaser_attempt`, `dxchaser_cooldown`,
`dxchaser_rarity_source`, `dxchaser_rarity_entity` and `dxchaser_meta`. It is not a QSO log. Attempt detail defaults to 30 days,
session summaries to 180 days, and attempts have a 10,000-row hard cap; active sessions and unexpired cooldowns survive compaction.
Database work runs behind the Chaser journal port and cannot block normal Digi/logging/radio operation. Reset affects this store only.

## Cross-band and integration truth

Cross-band intelligence requires material need, enabled/receivable band, current agreement from at least two independent spot-source
kinds, no unavailable Band Health/outlook truth, no engaged QSO and no active review cooldown. It emits receive review only. Review
acceptance still requires an exact new local decode and normal Digi safety; the core never sends CAT or changes TX state.

The semantic integration routes exact current-slot candidates through Digi preparation, receive review and canonical QSO outcomes.
It revalidates foreground, FT8/FT4 mode, live RX, local modem, operator TX enable, RX confirmation, session/decode/slot/callsign and
dial frequency before using the existing Digi selection/call path; it never enables TX. Contest remains read-only context and separate
scoring, blocking incompatible bands/modes, duplicates and trusted claims while adding only a bounded multiplier bonus. Band Maps
later consumes the immutable bounded read-only snapshot without calling the engine or database.

## Compose workspace

The workspace shows session mode/state and explicit start/stop controls, immutable safety wording, ranked local candidates with
eligibility/reasons, current target and engagement lock, receive-review opportunities, policy/rarity controls, session/history
summary and sanitized diagnostics. It contains no direct transmit or tune control.

## Validation record

The completion branch was validated with:

- `:app:testDebugUnitTest`: PASS, 23 focused JVM cases in three files.
- Focused instrumentation structure: one high-value SQLite/retention case; its source compiled successfully but it was not executed on
  an emulator or physical device. Total focused structure is 24 cases in the authorised three JVM plus one instrumentation files.
- `:app:assembleDebug`: PASS.
- `:app:bundleDebug`: PASS.
- `:app:compileDebugAndroidTestSources`: PASS. No emulator/device test execution was claimed.
- `:app:lintDebug`: PASS; 0 errors, 161 warnings and 34 hints across the existing application. Five warnings point into the new
  package (one Compose parameter-order suggestion and four KTX-style suggestions); none is a correctness or safety finding.
- Package audit: PASS, including the ITU/P.533 payload scan.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk`, 111,358,087 bytes,
  SHA-256 `5872d96079077a37c131a5a05cd4c8090f3323d43db7193096481498aa4a465c`.
- AAB: `android/app/build/outputs/bundle/debug/app-debug.aab`, 52,559,780 bytes,
  SHA-256 `45ff8e3f2e114d0c71e0fd1d6c6af97fa1f3dd93f5ae4e4670c1dcf345f82a38`.
- APK delta versus the combined-integration reference: +1,537,175 bytes, within the 2 MB branch target. APK is below 130 MB and
  AAB is below 60 MB.
- Provider-endpoint, direct CAT/PTT/TUNE/TX/QSO-mutation and complete-log-materialisation scans: no matches.
- `git diff --check`: PASS.

Watcher results at completion:

- Wavelog: exit 0, no change (`3.1.0`, `af3256140bd05403b7c4a421746c2ea653a4f04f`).
- OpenHamClock: exit 2, review required because preview changed in a sensitive area; the watcher made no changes.
- Nexus: exit 2, review required (`57d11fd...` / 1.7.5 to `f0869a11...` / 1.7.6) with documented digital/audio/UI paths;
  the watcher made no changes.
- MSHV Auto DX Chaser: exit 0, reviewed commit/tree and both licence digests unchanged.

The commit containing this completion record is the branch-final documentation commit; resolve its immutable identity with
`git rev-parse feature/dx-chaser-core-v1`. All branch commits are listed by `git log b4f12e17..feature/dx-chaser-core-v1` and are
pushed only to `origin/feature/dx-chaser-core-v1`.

Physical device, live decode, authenticated service, physical audio, physical radio and RF/TX acceptance are deliberately not
claimed. Follow `LIVE_ACCEPTANCE.md` during the later semantic integration. The APK was not installed.
