# Keyer, Contest/N1MM and DX Chaser semantic integration

## Frozen inputs and history

The integration branch `integration/keyer-contest-dxchaser-v1` was created in the isolated worktree
`shackcq-keyer-contest-dxchaser-integration-v1` from exact base
`b4f12e17fa87df16d2094b518ae187553e370be5`. Before every merge the corresponding remote tip was
fetched and rechecked. All three ancestry checks against the base returned exit 0.

| Input | Exact SHA | Merge commit |
|---|---|---|
| Keyer/Hotkeys | `ecba146f064e57e7ebb8a48d897b9ad4bb4cdf43` | `0bf3aa3` |
| Contest/N1MM | `d3f2a3b1f182a98d442e97182aa91e5c873f0e67` | `727a5f8` |
| DX Chaser | `b30ee05ad9231627afc9854e7182555ef229f50c` | `606e343` |

Each input was merged once with `--no-ff`; none was rebased, squashed or amended. `main` was not
checked out or changed.

## Conflict and decision ledger

Git reported no textual conflicts. Semantic conflicts still existed because all three branches
stopped at core contracts rather than production wiring.

| Surface | Decision |
|---|---|
| Production graph | `MainActivity` creates one `ContestSessionController`, `ContestSessionStore`, `N1mmNetworkController`, `DxChaserController` and `DxChaserStore`; the existing one `KeyerController`, `DigiController` and `QsoMutationCoordinator` remain authoritative. |
| Contest to Keyer | `ContestKeyerAdapter` maps RUN/S&P and CW/SSB into `KeyerDispatchPort`; DIGITAL/MIXED has no fallback. Context generation, active profile, radio/mode, foreground and existing arming are revalidated. |
| Contest QSO | `ContestQsoMutationAdapter` validates exchange, saves through `QsoMutationCoordinator`, links the canonical revision, then commits the serial and rebuilds derived score. Failure releases the reservation and never updates score or N1MM acceptance. |
| N1MM | Defaults OFF, loopback, untrusted and unarmed. Runtime requires a running foreground Contest plus explicit arm. Incoming policy has no CAT, Keyer, Digi, Chaser or silent-log authority. |
| DX Chaser input | `DxChaserInputAdapter` admits only Digi events that pass `automaticFtEligible`: current `LIVE_CAPTURE` session or exact `REDECODE_LIVE_SLOT`. Reference/companion/history evidence is never call-eligible. |
| DX Chaser to Digi | `prepareExactLiveCall` revalidates mode, session, exact decode ID, slot, callsign, dial frequency, local modem, audio, TX enable and RX truth before using the existing selected-call path. Chaser never enables Digi TX. |
| QSO feedback | Chaser completion is relayed only when the existing Digi canonical mutation exposes a new logged QSO ID matching the engaged target. FT completion alone is insufficient. |
| Contest to Chaser | Contest validity, dupe, multiplier and trusted-claim context is read-only. Invalid mode, duplicate or trusted claim is visibly ineligible; new multipliers add a bounded transparent reason. |
| Cross-band | `REQUEST_RECEIVE_BAND_REVIEW` enters the existing receive-review dialog. It cannot issue CAT/PTT/TUNE or start a sequence, and a fresh eligible local decode remains required. |
| Stop/lifecycle | `OperatorStopRouter`, Esc, background and close are idempotent. They stop Digi sequence, Keyer/repeat CQ, Chaser and N1MM; restored Contest is paused and all arms remain cleared. |
| Storage/backup | Contest and Chaser remain schema-1 separate SQLite files with no QSO body/truth. Both operational databases are excluded from Android backup/device transfer; safe settings use the central hashed configuration bundle. |

No whole-file ours/theirs resolution was used. No Band Map UI, provider client, second FT state machine,
direct Wavelog call, direct QSO write, CAT/PTT/TUNE path or credential handling was added.

## Product placement

- `Contest` is a native top-level destination in compact and wide navigation.
- `DX Chaser` is a dedicated subpage of the existing Digi destination; opening it is inactive.
- Keyer remains in Radio/Macros settings and the existing visible hotkey strip; Contest displays its
  active role/profile/queue context.
- Contest exposes explicit Radio/Fast Entry, Logbook, Log Intelligence and Settings handoffs.
- Workspace routing includes stable `CONTEST` and `DX_CHASER` destinations; every route remains
  navigation/receive-review only.

## Ownership and lifecycle

| Authority | Single owner | Integration consumers |
|---|---|---|
| Canonical QSO/Wavelog outbox | `QsoMutationCoordinator` / existing QSO and Wavelog stores | Contest mutation adapter; Digi existing logger; Chaser outcome observer |
| Keyer transmission | `KeyerController` / `AndroidKeyerRuntime` | Contest typed intents and existing physical-hotkey dispatcher |
| Digi FT/audio/TX/WSJT-X | `DigiController` and existing FT engine/session store | Chaser exact-call adapter and read-only event observer |
| Contest rules/score/dupe/serial | `ContestRuntime` plus merged Contest authorities | Contest UI, Chaser read-only context, future Band Maps |
| N1MM networking | one `N1mmNetworkController` | active Contest session only |
| DX Chaser target state | one `DxChaserController` / `DxChaserStore` | Digi subpage, Health and future Band Maps |

Background never restores active TX, Keyer arm, repeat CQ, running Contest, N1MM arm or Chaser session.
Radio identity/disconnect, material frequency, mode, station, foreground generation, audio route and
Contest changes invalidate pending actions.

## Configuration, Health and privacy

The configuration bundle adds safe `contest_n1mm` and `dx_chaser` sections. Runtime arms, active
sessions/targets, serial reservations, claims, peer sessions and Digi TX state are excluded. System
Health adds metadata-only Keyer, Contest, N1MM and Chaser cards plus schema/file byte counts. The
sanitized support bundle contains no credentials, raw QSO/XML, callsign, decode transcript, provider
body or private path.

## Validation and evidence boundary

Final local evidence on 2026-08-22:

- `:app:compileDebugKotlin`: PASS.
- `KeyerContestDxChaserIntegrationTest`: PASS.
- `:app:compileDebugAndroidTestSources`: PASS.
- Full Android unit/APK/AAB/lint gate: PASS; lint has zero errors.
- Rust `cargo test --locked`: PASS, 97 passed and one intentionally ignored.
- Required Debug CMake build and CTest: PASS, 2/2 targets.
- Package audit: PASS; APK and AAB are below their 130 MB/60 MB ceilings with no prohibited payload.

Artifact hashes and watcher dispositions are recorded in `RELEASE_CANDIDATE_READINESS.md`; the hosted
exact-SHA result is external evidence recorded after the branch-final push. No APK installation,
physical keyboard/audio/radio, live N1MM+, authenticated Wavelog, live FT8/FT4 QSO, PTT/TUNE/RF or
on-air acceptance is performed or claimed.
