# ShackCQ final whole-application convergence

## Frozen source and branch

- Original main: `b4f12e17fa87df16d2094b518ae187553e370be5`
- Semantic integration: `98490b6d5234c3f12cc5d00bbea3163c8273c3dc`
- Intelligent Band Maps: `38871cec8a08d3ef2711c8db9c3eda4d45996afa`
- Final branch: `integration/shackcq-final-whole-app-v1`, created directly from the Band Maps tip
- Verified ancestry: main → semantic integration → Band Maps; Keyer, Contest/N1MM and DX Chaser tips are ancestors; semantic…Band Maps count `0 4`

No contained feature branch was merged again, and no rebase, squash, force push, store release, credential access or data clearing is permitted.

## Source convergence

Production retains one construction/lifecycle authority per concern. The typed workspace router now represents every top-level workspace plus contextual Callbook, Chaser and Satellite targets, rejects a stale expected operating-context generation, and exposes no PTT/TUNE/TX/log/post capability. Band Maps can prepare an exact band/callsign/preset and exposes receive review, DX details, history, Callbook, Contest, Digi, Chaser and portable actions while remaining a snapshot-only consumer.

Navigation is Home, Radio, Digi, Panadapter, EQ, Logbook, Log Intelligence, Sync, Contest, Band Maps, Presets, DX, Portable, Operations, Groups.io and Settings. Contest and Band Maps default visible; DX Chaser remains inside Digi; N1MM remains inside Contest/Health; QO-100 remains in Operations/Satellites.

Contest local mutation remains `QsoMutationCoordinator` → canonical local success → Contest link/revision → serial commit → derived state/N1MM receipt. Wavelog delivery remains the single canonical outbox. DX Chaser cannot enable Digi TX and completes only from canonical QSO success. Global Stop remains the sole idempotent stop/RX-request path.

Configuration includes Keyer, Contest/N1MM safe preferences, DX Chaser, Band Maps and destination visibility; unsafe arms, queues, sessions, peers, claims, live observations and radio commands are excluded/reset. Health/support output is bounded metadata only. The protected-tablet acceptance exposed an installed QSO schema 16 database; the final branch therefore preserves the already-reviewed monotonic schema 14–16 / projection 3–5 migrations instead of attempting a destructive downgrade. Current schemas are QSO 16, projection 5, Neural 5, Digi 2, Groups.io 2, Contest 1 and DX Chaser 1; Band Maps adds no database.

## Evidence ledger

This ledger must be updated from actual command results before main is eligible. Source review is not build, device, service, audio or RF evidence.

- Release contract and repository scans: PASS after convergence edits; `git diff --check` clean and prohibited full-log/WSPR.live/P.533/conflict-marker scans empty.
- Android unit tests, APK, AAB, Android-test compile/assemble and lint: PASS (`BUILD SUCCESSFUL`); lint recorded 0 errors and 171 warnings.
- APK: 116,824,560 bytes; SHA-256 `f30a10b88d1dacd9b55840db75b5bb5f4cbdb4d18b5acec92b8d506df5fc363b`.
- AAB: 53,103,038 bytes; SHA-256 `24ed998da0f5085f7715255b45feae968dfd7f28e9a44c6b3025fd4cc736d8d5`.
- Package size and ITU/P.533 payload audit: PASS.
- Rust: PASS, 97 passed / 0 failed / 1 ignored. Shared core CMake/CTest: PASS, 2/2 tests.
- Apple unsigned generic iOS Simulator and generic iOS builds: `BUILD SUCCEEDED`.
- Wavelog: NO CHANGE. MSHV Auto DX Chaser: NO_REVIEW. OpenHamClock stable/release/licence unchanged; preview-only satellite-layer/test movement is documented and not absorbed. Nexus moved from reviewed `57d11fd`/1.7.5 to `f0869a1`/1.7.6 in documentation, Digi workflow, waterfall/audio and UI paths; no source was absorbed and review remains pending.
- Deterministic release soak: PASS for 100k logbook, 180-day Neural, 20k Digi, 30k Groups.io and provider lifecycle profiles; generated data was disposable.
- Hosted workflow runs `32570096778` and `32570818598` passed all seven jobs on their exact pushed tips. The final schema-compatibility tip still requires the same exact-tip workflow before main may move.
- Protected Lenovo TB373FU tablet: PASS at the process/window layer. The existing package was confirmed before mutation, private app data was backed up with SHA-256 manifests, and only `adb install -r` was used. The first installed candidate exposed an immediate `SQLiteException` because it attempted to open the tablet's schema-16 QSO database with schema 13. The monotonic schema 14–16 and projection 3–5 migrations were restored, the fixed APK was installed in place, and a controlled cold launch completed in 2.133 seconds. After 12 seconds the process remained alive and focused with no Android fatal exception. No app data was cleared or uninstalled.
- Physical radio/audio, authenticated Wavelog/Groups.io, live N1MM peer and RF: pending; not claimed by this programme.

## Gate verdict

`CONDITIONALLY PASSING — FINAL WHOLE-APP TIP REQUIRES EXACT-TIP HOSTED GATE BEFORE MAIN`

The integration branch is source/build/device-process complete. Local Android, Rust, shared-core, Apple, contract, soak and package gates pass, and the protected-tablet crash is repaired without destructive state changes. `main` remains frozen until the exact final tip passes the hosted seven-job workflow; that result is not inferred from earlier tips. Physical UI correctness beyond process/window focus, authenticated services, audio and RF remain separate pending evidence layers.
