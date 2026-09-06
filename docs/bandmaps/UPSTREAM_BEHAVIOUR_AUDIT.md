# Intelligent Band Maps upstream behaviour audit

This was a clean-room behavioural review. Source copied, translated, adapted or vendored: **NONE**. No upstream source,
styles, components, assets, data structures or tests were added to ShackCQ.

| Reference | Immutable pin | Licence observed | Behaviour reviewed |
|---|---|---|---|
| [HamLedger](https://github.com/valibali/hamledger) | `24f3ed0ef6b533f5a422d5d7cce3b24a58887bbc` | AGPLv3 plus an additional non-commercial/Commons-Clause-style restriction | frequency-axis display, selectable filters, contest-aware visual states, collision/magnifier behaviour, compact colour-rich presentation |
| [TLF](https://github.com/Tlf/tlf) | `348edf6d9730b68a02d5d32959ad718fe1504f60` | GPL-2.0 repository metadata | keyboard S&P traversal, next/previous navigation, multiplier/dupe emphasis, sparse contest display |
| [Not1MM](https://github.com/mbridak/not1mm) | `95b49e30fe7f374057fd708307c02fbdb892a81a` | GPLv3 | zoomable band map, aged and marked spots, worked styling, radio markers, traversal and operator preparation workflow |
| [Wavelog](https://github.com/wavelog/wavelog) | release commit `af3256140bd05403b7c4a421746c2ea653a4f04f` | MIT | active bands/modes, radio display, worked/confirmed colours, Band Map list behaviour and Contest field preservation |

N1MM behaviour is consumed solely through ShackCQ's integrated Contest/N1MM authority at the frozen Task C base. No
additional N1MM protocol source was reviewed or implemented.

Independent ShackCQ decisions include immutable multi-source observations, source-aware tolerances and ageing,
frequency-bucket indexing, three separate evidence channels, bounded projection-backed Needs truth, editable data
presets, four native Compose layouts, colour plus text/border/icon semantics, and generation-gated receive review.

The reviewed repositories did not become dependencies and did not affect APK package contents or NOTICE obligations.

