# Phase 0 audit

**Verdict: PASS WITH NOTES**

## Execution

| Field | Value |
|---|---|
| Execution time | 2026-08-17T04:24:52Z |
| Repository | /Users/oliver/Documents/Projects/ShackCQ/shackcq-mobile-phase0 |
| Remote | https://github.com/oliverbross/ShackCQ.git |
| Baseline | origin/main at f2bfcb450161c9a8cc6a6d595ccf699fb76af66c |
| Feature branch | codex/phase-0-repository-truth |
| Final commit | Recorded by the branch tip/final Codex response; a Git commit cannot contain its own SHA |
| Initial worktree | Clean isolated worktree |

## Executive summary

The active product, design, agent and surface contracts now agree with source: ShackCQ has native iPad-focused Apple and Android clients over a shared C++17 core, with KX3/KX2 as the current radio family. GPL-3.0-only is adopted. Historical device evidence is labeled, current build evidence is separated from physical/service evidence, and future features are not described as shipped.

Nexus was inspected externally at an immutable clean commit. Candidate components and component-level risks are recorded; no Nexus material was imported.

PASS WITH NOTES is required because public Apple distribution needs qualified review, existing owner-code provenance needs immutable origin records before distribution, a release needs a transitive notice/provider-terms pack, Android physical radio/audio is unverified, and authenticated Wavelog/service proof is unavailable. These do not block this documentation/licensing phase.

## Contradictions resolved

| Contradiction | Resolution |
|---|---|
| PRODUCT and AGENTS were Android-only | Current Apple/Android/shared-core contract replaces the stale prohibition |
| PRODUCT said panadapter absent | Active contracts now describe existing shared/Apple/Android source and evidence boundaries |
| DESIGN was Android-only | Flightline language is platform-aware; native platform interaction remains |
| Neural DX document implied broad parity | Explicit Android implementation coverage and provider/evidence limits added |
| Impeccable surface said no panadapter/spots and claimed current Lenovo proof | Surface aligned; physical claim classified as historical/unverified |
| Completion report looked current | Labeled as a historical first-iPad snapshot |
| README mixed current builds with physical/service claims | Replaced by an evidence matrix and links to canonical inventory |
| No root licence | COPYING and NOTICE added; GPL-3.0-only adopted |
| Nexus repo-level licence could be overgeneralised | Per-component candidate/licensing matrix and exclusions added |
| Nexus SECURITY says or-later while Cargo/NOTICE say only | Recorded as an upstream inconsistency; no broader permission inferred |

## Validation

| Area | Command/result | Evidence boundary |
|---|---|---|
| Shared core | CMake 3.22.1, Apple clang 21.0.0; configure/build PASS; CTest 1/1 PASS | Host Debug build/tests |
| Android | Gradle testDebugUnitTest and assembleDebug PASS; 51 tasks, all configured ABIs | JVM unit suite and APK assembly; no emulator/device |
| Apple | Xcode 26.6; project schemes listed; generic iOS Debug BUILD SUCCEEDED with existing app/DriverKit profiles | Compile/link/embed/sign only; no install/device run |
| Physical/service | Not executed | Historical iPad KX3/KXUSB/IQ/cluster evidence retained; Android hardware and authenticated Wavelog remain unverified |

The initial Android attempt accurately failed because ANDROID_HOME was unset; the existing SDK path was then supplied through environment variables without changing the project or accepting licences. A duplicate build client from a timed-out wrapper was isolated; the final no-daemon run passed.

## Changed files

- Root contracts: README.md, PRODUCT.md, AGENTS.md, DESIGN.md.
- Existing contracts/evidence: docs/COMPLETION_REPORT.md, docs/PANADAPTER_DESIGN.md, docs/PORTING_NOTES.md, docs/TAB5_FEATURE_PARITY.md, docs/neural-dx-watcher-parity.md, .impeccable surface contract.
- Legal: COPYING, NOTICE.
- Normative roadmap: docs/ROADMAP.md.
- Phase 0 evidence: this audit, ACTUAL_FEATURE_INVENTORY.md, ARCHITECTURE_BOUNDARIES.md, NEXT_AUTHORISED_PHASE.md, LICENSING_AND_NEXUS_ASSESSMENT.md.

No source, project configuration, schema, identifier, entitlement, signing setting, or operator-facing behaviour changed. Build and derived output remain untracked.

## Risks and owner actions

- Obtain qualified review/additional permission before public Apple distribution.
- Resolve immutable first-party origin/provenance records for previously copied owner modules.
- Produce a release-specific resolved dependency, transitive notice, provider/data/tile terms, privacy and corresponding-source pack.
- Keep Wavelog/service success unclaimed until authenticated isolated read/write-back/duplicate/cleanup evidence exists.
- Authorise and physically equip Phase 1A separately.

## Safety confirmation

No feature, deployment, release, app-store submission, remote mutation, QSO creation, cluster post, hardware installation, or transmit operation occurred. No Nexus source, dependency, binary, submodule, or derived implementation was introduced.
