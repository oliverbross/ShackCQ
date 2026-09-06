# Porting and provenance notes

**Status:** Current provenance register with historical source paths. Local paths are evidence clues, not redistribution grants; future release review must resolve immutable source commits and applicable notices.

| Origin/reference | ShackCQ destination | Relationship | Phase 0 disposition |
|---|---|---|---|
| Owner repository kx3-tab5-remote, components kx3_core | core/portable kx3 protocol/parser and core C ABI | Recorded as owner-authored portable reuse | Keep; record the exact origin commit before public distribution |
| kx3-tab5-remote ADIF, DX analysis, operator intelligence, panadapter and sync components | core/portable/include/kx3 and core/portable/src | Recorded as copied/adapted owner-authored code | Keep file layout; provenance is REVIEW_REQUIRED until immutable origin commits/copyright are captured |
| QMX Panadapter, AetherSDR, and Thetis research | shared DSP and native client presentation | Behavioural/reworked research; no copied source asserted | Retain as reference-only unless a later component review proves otherwise |
| OM0RX KX3/Wavelog Apple DriverKit work | ios/CP210xDriver and ios/ShackCQ | Superseded/continued owner code | Preserve bundle/profile identity; record immutable origin before distribution |
| Tab5 KX3-style control and logbook behaviour | Apple and Android native clients | Native parity implementation | Preserve working behaviour; no speculative namespace relocation |
| Neural DX Watcher product research | Android Neural DX surfaces and shared analysis | Behavioural parity contract | Current Android scope is documented in neural-dx-watcher-parity.md |
| Nexus at the commit recorded in phase-0/LICENSING_AND_NEXUS_ASSESSMENT.md | None | External upstream/reference only | No source, binary, dependency, or derived implementation imported in Phase 0 |

Some radio-neutral modules remain under the mixed kx3 directory. This is historical layout, not proof that they are KX3-specific. Extract by touch only when a real second implementation requires it.
