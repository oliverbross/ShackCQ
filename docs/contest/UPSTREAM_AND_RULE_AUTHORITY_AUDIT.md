# Contest upstream and rule-authority audit

Audit date: 2026-08-22. ShackCQ is GPL-3.0-only. Official sponsor rules, not another logger, are scoring authority.

## Immutable comparison baselines

| Project | Commit | Licence | Reviewed surface | Boundary |
|---|---|---|---|---|
| Wavelog 3.1.0 | `af3256140bd05403b7c4a421746c2ea653a4f04f` | MIT | `Contesting.php`, `Contesting_import.php`, `Cabrillo.php`, `Bandmap.php`, `Contesting_model.php`, `Cabrilloformat.php`, contest UI/JS and API-v2 QSO fields | Behaviour/data/export comparison only; no PHP/UI architecture copied |
| TLF | `348edf6d9730b68a02d5d32959ad718fe1504f60` | GPL-2.0 | keyboard Run/S&P, rules, serial, dupe/multiplier, Cabrillo, band-map ergonomics | Clean-room behaviour only; no C/rule source adapted |
| Not1MM | `95b49e30fe7f374057fd708307c02fbdb892a81a` | GPL-3.0 | `Working_Contests.md`, selected `not1mm/plugins`, `bandmap.py`, packet output, export/rate/score behaviours | Independent Kotlin design; no Python/PyQt transplant and no fixture copied |
| HamLedger | `24f3ed0ef6b533f5a422d5d7cce3b24a58887bbc` | AGPLv3 plus Commons Clause/non-commercial restriction | README/tutorial-visible contest sessions, statistics, keyer and hotkey-strip behaviour | Strict clean-room visual/behaviour observation; no source, CSS, state, fixtures or logic copied |
| N1MM protocol reference | `2decc5adbdffedf1138fd4b75c65f811f6a21064` | MIT | all requested docs, `n1mm_protocol/**`, examples and tests | Protocol/fixture authority; unofficial reverse-engineered reference, not certification |

No non-trivial external algorithm or fixture was copied into production. Frame examples were independently encoded from the MIT specification. The protocol pin is recorded in `docs/n1mm/UPSTREAM.json`.

## Official rule and Cabrillo authority

Retrieved bytes were SHA-256 digested on 2026-08-22.

| Rule family | Edition used | Official source | SHA-256 | Status |
|---|---|---|---|---|
| CQ World Wide DX CW/SSB | 2025 current published rule text | <https://cqww.com/rules.htm> | `a018cd6734604d37f7452927cc28c1fbc80098a52f8e5373bcdce8fb139f08c4` | `REVIEW_REQUIRED` before a 2026 live session because a 2026 rules edition was not published at the audited endpoint |
| CQ WPX CW/SSB | 2026 | <https://cqwpx.com/rules/2026_cqwpx_rules.pdf> | `38dead919408c47483dfdde6a9bd3a9a5e2ae4a54f24b6beba7b4aa2a7f1f624` | implemented |
| ARRL International DX CW/SSB | 2026 event rules | <https://www.arrl.org/arrl-dx> | `6d9fd23c937c34361e1bc3b841a614bcd5728f77841d91858475d1ef0544d3c8` | implemented with W/VE/DX entrant-side semantics |
| IARU HF World Championship | 2026 | <https://www.arrl.org/iaru-hf-world-championship> | `37c00aab938f29e5d50b521bf2cfa9f96d1dafd312bab40e5c22c7e176bf3362` | implemented |
| ARRL Field Day | 2026, revised 2026-03-01 | <https://www.arrl.org/files/file/Field-Day/2026/2026-Field-Day-Rules.pdf> | `9d89311f7911fd65a62348c587bd8718e03e7e0ea41e4ec451a035dbb19e5197` | QSO scoring implemented; bonus claims remain explicit external inputs |
| CQ 160-Meter CW/SSB | 2026 | <https://cq160.com/rules/rules_cq160_2026.pdf> | `442b5cfeec04538cc7c4af01364968a558214e2f1e2c955455e54567e52fa45c` | implemented |
| Oceania DX CW/SSB | 2026 sponsor page | <https://www.oceaniadxcontest.com/rules> | `6f352037e6422db86bc050f6f98531ae9e6706cda5e6223d3a5c5b1bf9056711` | `REVIEW_REQUIRED`: the embedded downloadable PDF did not resolve reliably; 2026 sponsor announcement and current sponsor page were cross-checked |
| Cabrillo | WWROF Cabrillo V3, V2 deprecated | <https://wwrof.org/cabrillo/> and <https://wwrof.org/cabrillo/cabrillo-qso-data/> | page digest `4ca7212eaacdf35a29a10034e8fdd047b3546475a939288ed4c9675600a53a56` | `START-OF-LOG: 3.0`; sponsor-specific QSO exchanges remain authoritative |

## Behaviour comparison decisions

| Area | Adopted/improved | Rejected or bounded |
|---|---|---|
| Rule architecture | Declarative immutable identity/exchange metadata plus typed Kotlin scoring families | universal expression language and remote executable rules |
| Workflow | dedicated setup/log/review/network workspace, Run/S&P state and visible score/rate | fields appended to the general logger; hidden transmit/log coupling |
| ESM | pure transition engine emitting typed keyer intents | direct keyer, CAT, PTT, audio or Digi access |
| QSO storage | canonical `Qso` plus `QsoMutationCoordinator`; contest DB stores links/derived state only | duplicate QSO body or direct Wavelog writer |
| Networking | bounded codecs, loopback default, monitor-first, explicit peer trust | broadcast-derived trust, auto deletes/edits, system-time/file/radio side effects |
| Exports | streamed sequences, explicit blocking/warning validation and typed rule formatter selection | Wavelog/Not1MM/TLF output as normative truth or one contest-name switch maze |

Known limitations: the initial UI is standalone and awaits central navigation/configuration integration; live N1MM+, authenticated Wavelog, physical radio/TX and sponsor-upload acceptance were not performed; Apple and desktop contest work is deferred.
