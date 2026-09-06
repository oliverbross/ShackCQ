# Licensing, provenance and Nexus assessment

## Scope and limits

This is technical licence due diligence, not legal advice. It records declarations and practical release gates; it does not decide ownership, trademark rights, App Store compatibility, or provider redistribution terms.

## ShackCQ baseline

- Before Phase 0, the repository had no root COPYING or LICENSE and no project-wide SPDX declaration.
- Phase 0 adopts GPL-3.0-only and adds the unmodified GNU GPL version 3 text in COPYING.
- First-party source generally lacks file-level copyright/SPDX headers. Do not manufacture a sole-copyright claim.
- PORTING_NOTES records owner-code reuse from kx3-tab5-remote and earlier Apple/DriverKit work, but immutable origin commits and per-file provenance are not consistently recorded.
- No Swift Package Manager dependency is configured. Apple links system frameworks/SQLite and the in-repository C++/DriverKit source.
- The shared C++ core uses the standard library and in-repository code; no external C/C++ library is declared by CMake.
- No bundled font, map dataset, media, model, or icon pack is tracked beyond platform/project resources and Gradle wrapper artefacts.

**Distribution gate:** capture immutable origin commits/copyright for copied owner modules, generate a resolved dependency/notice bundle, review runtime provider/data/tile terms, and retain complete corresponding source.

## Direct dependency inventory

| Dependency family | Scope | Declared licence evidence | Phase 0 assessment |
|---|---|---|---|
| AndroidX Activity/Core/Compose/Lifecycle/Test | Android runtime/test | Apache-2.0 in upstream/POMs; core-ktx cached POM omitted a licence node but AndroidX source is Apache-2.0 | Compatible in principle; ship required notices |
| Kotlin coroutines 1.10.2 | Android runtime | Apache-2.0 | Compatible in principle |
| usb-serial-for-android 3.11.0 | Android runtime | MIT | Compatible in principle; retain notice |
| MapLibre Android SDK 13.0.2 | Android runtime | BSD-2-Clause | Compatible in principle; retain licence/attribution and review transitive native notices |
| JUnit 4.13.2 | test only | EPL-1.0 in cached POM | Not distributed in the APK path; keep test-only and document |
| Gradle wrapper | build tool | embedded Apache-2.0 notices; wrapper jar tracked | Build-time; retain notices/source route as applicable |
| Apple SDK frameworks, SQLite3, DriverKit | platform/runtime | Apple SDK/platform terms | REVIEW_REQUIRED for distribution path; not an open-source dependency conclusion |
| C++ standard library/toolchain | build/runtime | platform toolchain terms | Release-specific toolchain/source obligations must be recorded |

No dependency lockfile fixes the complete Android transitive graph. A release must preserve the exact resolved graph and all transitive notices rather than relying on this direct-only table.

## Runtime data and services

Source references Wavelog, QRZ, HamQTH, country-files.com CTY data, NOAA/SWPC, Open-Meteo, WSPR.live, PSK Reporter, DX news feeds, CelesTrak, AMSAT, SatNOGS, ntfy, Perplexity, Esri, CARTO, and OpenStreetMap attribution.

These endpoints are interoperability/data-service references, not proof of redistribution or commercial-use permission. ESRI/CARTO/OSM attribution strings exist in the Android map style, but public distribution still requires a current provider/tiles/data/privacy/attribution review. CTY and cached/downloaded datasets require their own notice and redistribution analysis. Authenticated-service tests were not performed in Phase 0.

## Apple distribution risk

GPLv3-covered source development and local device builds may continue. Distribution through Apple-controlled channels presents a material unresolved licence/platform risk, including downstream installation/usage restrictions and third-party upstream permissions. Phase 0 does not claim compatibility and authorises no submission. Obtain qualified legal review and/or suitable additional permission from relevant copyright holders before public Apple distribution.

## Release/source policy

Every distributed covered binary must map to an immutable source commit/tag and retain:

- complete corresponding source for ShackCQ and incorporated covered components;
- reproducible build instructions and required tool versions;
- exact dependency manifests/resolution data and source offers/access;
- applicable patches, generated inputs, build scripts, notices and attribution;
- release binary/source checksums and a supported access period;
- a readily accessible GPL/third-party notices surface.

GPLv3 permits charging for binaries, support, and services.

## Nexus inspection baseline

| Field | Value |
|---|---|
| Repository | https://github.com/kd9taw/Nexus.git |
| Default/inspected branch | main |
| Inspected commit | 41e06a5bd0bb90aa96a7e2f5fb8b04fe3ba0a3a2 |
| Commit time | 2026-08-16T23:45:40Z |
| Inspection time | 2026-08-17 UTC |
| Worktree | clean |
| Declared workspace/shell licence | Cargo.toml and src-tauri/Cargo.toml: GPL-3.0-only |
| Primary notices | COPYING, NOTICE, ARCHITECTURE.md, deny.toml, crate manifests and vendored-resource notices |

Nexus NOTICE consistently explains a GPL-3.0-only combined work and extensive upstream lineage. SECURITY.md line 120 at the inspected commit says GPL-3.0-or-later; this conflicts with Cargo/NOTICE and is treated as a stale upstream document, not as permission to ignore component-level terms.

Architecture: React/TypeScript UI → Tauri shell → Rust domain crates → vendored/native libtempo. The Tauri/React shell is reference-only for ShackCQ.

## Nexus candidate matrix

| Candidate | Exact upstream paths | Evidence/coupling | Preferred later strategy | Risk/phase |
|---|---|---|---|---|
| Flex discovery/control/VITA | crates/tempo-net/src/flexdisc.rs, flexcat.rs, flexvita.rs | Pure protocol parsers/encoders with unit tests; official API facts claimed; SmartLink auth is not established for ShackCQ | Audit direct Rust reuse behind narrow C ABI or GPL-attributed C++ adaptation; revalidate with official Flex docs | High; Phase 5A/5B |
| Flex spectrum/DAX | crates/tempo-audio/src/flexspectrum.rs, flexdax.rs | Depends on tempo-app Engine/global state, sockets/audio/device features; physical verification comments are upstream-specific | Extract only rendering-independent protocol/data pieces; reject wholesale audio orchestration | High; Phase 5B/5C |
| Propagation/opportunity ranking | crates/propagation/src/advisor.rs, needalert.rs, model.rs, spot.rs and related tests | Mostly pure Rust with serde; large tested domain surface | Focused crate/C ABI evaluation or behavioural reimplementation | Medium; Phase 2/4 |
| POTA/SOTA spot parsing | crates/propagation/src/pota.rs and live/pota.rs | Pure parsers separated from reqwest live adapter; no WWFF equivalent established | Prefer focused parser/domain assessment; independently confirm provider terms/data | Medium; Phase 2 |
| Awards/progress | crates/propagation/src/awards.rs, achievements.rs, journey.rs | Coupled to Nexus models/catalogues; data terms vary | Behavioural/reference-only until a bounded model is selected | Medium/high; Phase 4 |
| QRZ/Club Log/eQSL/LoTW | crates/propagation/src/live/qrz.rs, clublog.rs, eqsl.rs, lotw.rs plus tempo-core helpers | Network transports include redirect/error safeguards; credentials and provider contracts remain app-specific | Reuse pure formatting/validation only after authority/secret review; likely adapt behaviour | High; Phase 4 |
| Spectrum DSP | crates/tempo-core/src/spectrum.rs | Rendering-independent pure DSP, many tests, intentionally no serde model | Compare against existing ShackCQ DSP first; reference-only unless a measured defect justifies derived reuse | Medium; Phase 1A |
| Audio/CAT/PTT safety | crates/tempo-audio and tempo-core/src/tx.rs/qso.rs | Desktop/audio/Hamlib/global-engine coupling and digital-mode assumptions | Reject wholesale; extract safety ideas only with independent ShackCQ design | High; later radio phases |
| Logbook/durable queues | tempo-app/tempo-core store/logbook/outbox-related modules | Significant Nexus domain/global-state coupling; some queues are digital-message rather than upload outboxes | Behavioural/reference-only until an exact connector/outbox component is located | High; Phase 4 |
| Offline catalogues/updaters | crates/propagation/data and live modules | Numerous separate data copyrights/terms documented in NOTICE | Reject copying data wholesale; independently source licensed programme data | High; Phase 2/4 |
| Tauri/React UI | ui and src-tauri | Desktop shell with roughly 240 commands and global glue | Reject for ShackCQ clients | Outside settled stack |
| WSJT-X/vendored modems/DeepCW/SSTV | libtempo, digital-mode crates, vendored resources | Mixed GPL/AGPL/MIT/native/data notices; unrelated to approved near roadmap | Reject/defer unless separately authorised | Out of scope |

## Integration rules

For a selected future component record the exact file/symbol, inspected commit, per-file and crate licence, copyrights, NOTICE entries, dependencies, target platforms, tests, modifications, update strategy, and corresponding source.

Possible strategies are:

1. direct audited Rust crate reuse behind a narrow C ABI;
2. focused extraction from a well-isolated component;
3. GPL-attributed C++ adaptation (still derived reuse);
4. behavioural/reference-only independent implementation;
5. rejection.

No Nexus source, binary, submodule, subtree, dependency, copied notice, or derived implementation was added in Phase 0.

## Unresolved owner/legal actions

- Qualified Apple distribution review and/or additional permission.
- Confirm first-party ownership/provenance and immutable origin commits for existing copied owner modules.
- Approve a release-specific transitive dependency/notice and provider-terms review before public binaries.
- Select and authorise each Nexus component and strategy separately.
- Verify current official FlexRadio developer/authentication terms before Phase 5.
- Review names/trademarks and portable-programme/data-service terms before marketing or distribution claims.
