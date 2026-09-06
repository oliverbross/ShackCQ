# DX Chaser upstream and current-authority audit

## Frozen inputs

- ShackCQ base: `b4f12e17fa87df16d2094b518ae187553e370be5`
- MSHV Auto DX Chaser commit: `d960ae22de78940c6be9d95bd4817d233d02ee39`
- MSHV tree: `52d5b2e4d39a8e174000971ada3ac0c9f0442625`
- Upstream licence: GPL-3.0; `LICENSE` SHA-256 `ae8271f05a41a70dc47b89c560724128c3e2007f8f7db6e2c21c7f1360fe79fa`
- `THIRD_PARTY_LICENSES.md` SHA-256: `c6419803337ee76f3e28ed81993b60f945cf782c59f11a71877f74321842f0b0`

The upstream audit covered `LICENSE`, `THIRD_PARTY_LICENSES.md`, `README.md`, `CHANGELOG.md`,
`docs/FEATURES.md`, `docs/SETTINGS.md`, `docs/QUICKSTART.md`, the listed files under
`MSHV_2762/src/HvAutoDxer`, all files under `HvAutoDxSettings` and `HvDxccChaser`, and Auto DX
integration references in `main_ms.*`.

## Existing ShackCQ authorities

| Concern | Existing owner at the frozen base | DX Chaser use |
|---|---|---|
| FT decode and provenance | `DigiDomain.kt`, `DigiController.kt`, `DigiSessionStore.kt`, `DigiFtEngine.kt` | A later adapter converts only `LIVE_CAPTURE` or exact `REDECODE_LIVE_SLOT` rows into the immutable input port. |
| Digi sequence and TX safety | `DigiController.kt`, `DigiFtEngine.kt` | The core emits `PREPARE_FT_CALL`; it has no Digi/controller reference and cannot transmit. |
| Needs/worked/confirmed/watchlist | `FeatureController.kt`, `ProgressController.kt`, `ProgressModels.kt`, QSO projection | The port supplies explicit per-dimension states. Missing data remains `UNAVAILABLE`. |
| Canonical QSO mutation | `QsoMutationCoordinator.kt`, `QsoDatabase.kt`, `QsoProjectionStore.kt` | A later outcome adapter reports committed success/failure. DX Chaser never saves a QSO. |
| Current DX evidence | `FeatureController.kt`, `NeuralDxController.kt` | Bounded corroboration only; it never creates eligibility. |
| Empirical outlook | `NeuralOutlookController.kt` | Remains labelled future empirical support, never probability or live proof. |
| Band Health/RF evidence | `hamclock/HamClockRfEvidence.kt`, `ProgressController.kt` | Bounded state/age summaries only. |
| Operating context and review routing | `FinalConvergenceContracts.kt` | Later adapters provide generation-safe context and receive-review routing. |
| Radio/audio ownership | existing app radio controllers and Digi audio path | No direct dependency is admitted in the new package. |
| Configuration/System Health | `ConfigurationRecovery.kt`, `SystemHealthCentre.kt` | Package-local import/export and diagnostics are exposed for the later central integration. |

No current public type covers the complete Chaser snapshot. The missing adapters are therefore narrow ports, not edits to the
parallel-owned authorities.

## Eligibility truth

The existing `DigiDecodeEvent.automaticFtEligible` already establishes the critical provenance rule: exact timing and matching
mode/frequency, with `LIVE_CAPTURE` tied to the active session and `REDECODE_LIVE_SLOT` tied to the current captured slot.
DX Chaser preserves that rule and adds station/radio/band, message, age, cooldown, operator-call and safety gates. Reference,
companion, history and legacy-timing rows remain non-call-eligible.

## Clean-room decision

The Kotlin/Compose implementation was designed from the authorised behaviour specification and ShackCQ types. No MSHV source,
fixture, Qt architecture, queue/radio code, provider client, DXCC database, Twilio code, or Club Log-derived rarity list was copied
or adapted. `NOTICE` therefore does not require an attribution change.

| Upstream behaviour | Decision |
|---|---|
| local-decode-only calling, bounded cache, explainable value/SNR/repeat scoring | Adopted independently |
| search-and-pounce, cooldown, session statistics, engagement lock | Adopted and made operator-started, typed and finite |
| automatic QSY/non-standard frequency | Replaced with receive-review intent plus mandatory new local decode |
| ATNO/scarce persistence | Replaced with hard finite caps |
| spot-source aggregation and reconnect | Rejected; existing ShackCQ evidence only |
| separate DXCC/ADIF/QSO truth | Rejected; existing ShackCQ authorities only |
| hard-coded Club Log rarity | Rejected; bounded manual import with provenance digest |
| direct MSHV queue/AUTO/TX control | Rejected; typed intent boundary only |
| desktop Qt panel and settings architecture | Rejected; native Compose and package-local inactive settings |
| TX watchdog | Deferred to existing Digi safety; the Chaser itself cannot key TX |

