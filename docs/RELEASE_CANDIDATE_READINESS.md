# Release Candidate Readiness

## Android hardened + Windows Alpha integration

Candidate promotion is gated by the exact-SHA multiplatform workflow, Android APK/AAB audits, native/sanitizer tests, unsigned iOS builds, and Windows/macOS Qt build/test/package jobs. Physical Windows, authenticated Wavelog, live cluster and hardware/RF evidence remain pending and are not inferred from hosted builds.

## Tablet Acceptance Sweep 2 candidate

The Sweep 2 candidate must pass all local commands, exact-SHA hosted validation, package/hash gates and protected in-place installation before PASS. Provider/licence-blocked catalogue programmes are acceptable only with explicit registry truth and no scraping/bundling. Source/build success is not device, service, audio or RF evidence.

## Tablet Acceptance Sweep 1 candidate

The Sweep 1 feature branch passes the local 557-test Android JVM suite, APK/AAB and Android-test packaging, lint with zero errors, Rust (97 passed), native core (2/2), both unsigned Apple build targets, package/ITU payload audit, release-candidate privacy/provenance contract, and disposable scale/soak profile. These are pre-final-tip local gates; the exact pushed SHA must still pass the hosted seven-job workflow before a PASS verdict.

## Mandatory automated gates

- Watcher review for Wavelog, OpenHamClock and Nexus.
- Python contract/provenance/privacy scan.
- Rust suites and native CMake/CTest.
- Android JVM tests, lint, APK, AAB and Android-test compilation.
- Android package audit against the frozen-baseline evidence.
- Unsigned Apple generic-device build.
- Fresh/current/legacy schema migration matrix and soak exercises.

## Current upstream disposition

- Wavelog 3.1.0 at `af3256140bd05403b7c4a421746c2ea653a4f04f`: no change.
- OpenHamClock stable `d4a50eaaa61d3432a1de5f80cbe61790739930a5`: unchanged. Preview `99913f2df574b8588ddaff703581b8f341f46761` contains an already-reviewed satellite display/test delta and is not absorbed.
- Nexus current `f0869a11…` (1.7.6): watcher review remains required for digital/audio/UI changes. Excluded modem/radio/desktop/notch/compressor/WinKeyer code is not imported.

The workflow's watcher job is advisory because upstream movement requires human semantic review. Release readiness cannot be inferred from that job alone.

## External boundary

No device install, credential use, authenticated-service mutation, signing, deployment, store action, CAT/PTT/TUNE or RF transmission is part of this candidate.

## Keyer, Contest/N1MM and DX Chaser integration candidate

The isolated `integration/keyer-contest-dxchaser-v1` branch preserves all three frozen histories and
adds production navigation, controller lifecycle, typed Contest-to-Keyer and canonical-QSO adapters,
default-off N1MM runtime, exact local-decode Chaser-to-Digi preparation, canonical QSO feedback,
configuration/Health/privacy integration and the read-only Band Maps contract. The local source/build
gates and artifact audit below pass. No physical or live evidence is implied.

Local integration validation on 2026-08-22:

- Frozen base and three exact-source ancestry checks: PASS; three exact non-fast-forward merges, no textual conflicts.
- Watchers: Wavelog exit 0/no change; MSHV exit 0 with commit/tree/licence digests unchanged; OpenHamClock and Nexus exit 2/review required, read-only, with no upstream code absorbed.
- Rust `cargo test --locked`: PASS, 97 passed and one intentionally ignored.
- Required Debug CMake build and CTest: PASS, 2/2 targets.
- Android unit tests, APK, AAB, instrumentation-source compilation and lint: PASS. Instrumentation sources compiled but were not executed on a device/emulator.
- Lint: 0 errors, 170 warnings and 37 hints; warnings/hints are non-blocking.
- Package audit and repository authority/privacy scans: PASS.

## Disposable scale profile (2026-08-22)

The deterministic host profile passed and deleted its temporary databases. Observed on this host: 100k-QSO insert 382.31 ms, keyset page 0.19 ms, aggregate 83.19 ms, streamed export 161.24 ms; 30k-message all-groups FTS 1.54 ms. Database sizes were 9,605,120 bytes (logbook), 1,085,440 (Neural), 602,112 (Digi), and 2,723,840 (Groups.io). These are host simulation timings, not device performance claims.

## Final host artifacts

- Universal debug APK: 111,637,568 bytes; SHA-256 `7f03589575cc88849fd923f1381fc149c85cdd27f21f0ee7873030c67c53a25e`.
- Debug AAB: 52,909,771 bytes; SHA-256 `94718a085e1de6ee2bc52dcf466ca7819bad0eff85673a8f9176657fedf6d0f8`.
- Package audit: P.533 payload scan PASS for both archives.

These are unsigned/debug host outputs and were not installed or distributed.

## Sweep 2 delta

Sweep 2 adds the integrated radio profile/platform and rotator runtime. The required release shape is an arm64-only tablet debug APK built with `-PshackcqAbi=arm64-v8a` (ceiling 130 MB) and a four-ABI release AAB (ceiling 60 MB). Exact artifact sizes, hashes, hosted jobs and protected-tablet install evidence must be refreshed at the final integration SHA; older artifacts above are not evidence for Sweep 2.

Physical QMX, RGO ONE and rotator behavior remains pending and must not be inferred from package or install success.

Local Sweep 2 package evidence is now available: the arm64 tablet APK is 58,293,188 bytes (`00b3c2eb7c6143d65e970d030ca096a48830d770c1cde76adc530a396d054be8`) and the four-ABI debug AAB is 55,606,070 bytes (`29cab575b7d403a1876780806a397f5a73b69ec1ac11136e23a0da4bca8b414f`). Both are below their Sweep 2 ceilings and pass the prohibited-payload audit. Android passed 688 JVM tests, instrumentation-source/test-APK packaging, and lint with 0 errors. Rust passed 97 tests with one intentional ignore, Debug core CTest passed 2/2, and the unsigned iOS Simulator build passed. The protected tablet preserved UID 10352, 146 non-cache hashes, schema 16 and 67,223 QSO/projection rows; process, relaunch, crash-buffer and safe Radio/Settings/Rotator rendering checks passed. Exact-SHA hosted status is reported with the immutable external run rather than embedded in the commit it validates.

## Sweep 3 candidate gate

Sweep 3 repairs the app-scoped cluster/Band Map flow, Settings reachability, Contest/map presentation, WWFF state split and foreground Groups.io alerts. It does not inherit Sweep 2 artifact, hosted or device evidence: sizes, hashes, exact-SHA CI and protected install must be recorded again at the final Sweep 3 SHA. See `TABLET_ACCEPTANCE_SWEEP_3.md` and its live checklist.

## Android native lifecycle hardening v1

The Sweep 3 source now has a complete JNI/long-lived-resource ownership audit and systematic checked-handle/generation repair. Local gates pass: 711 JVM tests; four-ABI bundle and instrumentation packaging; lint; arm64 APK; Rust 98 passed/one ignored; normal and ASan+UBSan CTest 3/3; unsigned generic iOS Simulator and iOS builds; release contract and package scans. The arm64 APK is 58,426,676 bytes (`f99b529f43e28bc16834fd80cd488293234d5399e04a972d2d87ae83240896b9`) and the four-ABI AAB is 55,739,195 bytes (`e43aeb115149899d19d95060464fd5274cb74612d8a60c66a8fe3976aee8f053`). Hosted run `32784249372` passed all seven jobs at exact SHA `826ba3031d869f12e0c9d37649257f9b2fac1ecf`. The protected tablet preserved UID 10352, private data, schema 16 and 67,223 canonical/projection rows through in-place install, relaunch cycles and a bounded 30-minute locked-state process soak. Full lifecycle PASS remains blocked by the secure keyguard because safe visible workspace navigation and a true unlocked foreground-provider soak were not performed.

## Windows desktop full-parity v1 candidate

Local candidate gates pass for Qt desktop (6/6), Rust (98 passed, one ignored), normal and ASan/UBSan native CTest (3/3 each), Android (713 JVM tests, lint, APK/AAB and Android-test sources), arm64 package audit, both unsigned iOS targets, 75-frame gallery and unsigned macOS packaging. The Windows verdict remains PARTIAL because 17/31 parity rows lack production-equivalent controllers. Windows artifacts, hosted exact-SHA results and physical/live acceptance must be recorded at the final pushed SHA before any stronger release claim.

## Desktop Flightline UI convergence v1

Source now includes a canonical command/menu model, 40 original packaged SVGs, responsive grouped sidebar navigation, platform-correct macOS/Windows menus and 58 deterministic frames per profile: 39 operating plus 19 Edit Layout frames. Release readiness remains pending until the final pushed SHA passes the hosted Windows/macOS, shared-core/sanitizer, Android, Apple and audit gates. No signing, notarization, publishing, deployment or physical/live acceptance is authorized by this UI work.

## Desktop Functional Parity Closure v1

Source closure is 31/31 with fail-closed external boundaries. Candidate readiness additionally requires the exact pushed SHA to pass the multiplatform workflow, including Windows/macOS packages, Android/iOS regression, migrations, fake protocols, scale, visual and privacy gates. Signing, notarization, publication and deployment remain out of scope.

## Multiplatform RC1

The whole-repository RC branch adds immutable ancestry proof, singular owner/platform matrices, converged schema/configuration contracts, privacy/provenance audits and deterministic exact-SHA source/SBOM/digest generation. Readiness is granted only after the final pushed SHA passes the authoritative workflow and every mandatory artifact is verified. Local builds, protected-device process evidence and hosted galleries remain distinct from physical/authenticated/signing acceptance.

## Android SDR enhancement candidate gate

This candidate adds no merge or release authorization. Readiness additionally requires the read-only SDRoxide watcher, Android unit/instrumentation/native gates, package-size/hash proof, exact-SHA hosted workflow, and protected-tablet in-place verification when the named device is available. Physical TCI/RF/TX remains pending without explicit operator authority.

## Android SDRoxide operational v2 gate

The v2 candidate additionally requires unit/lint/instrumentation compilation, arm64 APK and AAB limits, native/Rust sanitizers, iOS/desktop regressions, ownership/privacy/package audits, and an exact-SHA hosted run. TCI spot/diversity and record-on-hit audio are explicit unavailable states, not release defects. No merge, signing, publication, deployment, or live radio operation is authorised by this gate.

Local final-source gates pass: 728 JVM tests, Android lint, instrumentation compile/test APK, four-ABI AAB, arm64 APK, package/prohibited-payload audit, native normal/ASan/UBSan 5/5, Flex 98 passed/one ignored, Tempo SSTV 160 passed, MFSK 407 passed/28 ignored, both unsigned iOS targets, and macOS desktop 10/10. Hosted exact-SHA and conditional protected-tablet evidence remain separate.

## Android Local SDR Receiver v3 gate

V3 additionally requires the shared local-receiver CTest, Android mode/recording/lifecycle tests, instrumentation compilation, unchanged one-owner audits, package caps, the expanded read-only SDRoxide watcher and exact-SHA hosted workflow. Local demodulation is receive-only. Physical I/Q, audio quality, live tone/RDS accuracy, protected-tablet visual proof, signing and release remain separate and cannot be inferred from the debug lab or build.

Local final-source gates pass: 736 JVM tests, Android lint, instrumentation test-APK packaging, four-ABI AAB, arm64 APK, package/prohibited-payload audit, native normal/ASan/UBSan 6/6, Flex 98 passed/one ignored, Tempo SSTV 160 passed, MFSK 407 passed/28 ignored, Apple Fast Entry, and both unsigned iOS targets. Exact final-SHA package sizes and hashes are generated after the immutable candidate commit exists. Local desktop configuration is limited by the absent Qt 6.11.2 TaskTree module; the exact-SHA hosted macOS/Windows jobs remain required. Protected-tablet and physical/live evidence remain separate.

## Android SDR Workbench v4 gate

V4 requires the fail-closed source/parity checker, recording/replay/storage migration tests, deterministic scale profile, complete Android unit/lint/instrumentation packages, arm64 APK/four-ABI AAB audits, existing native/Rust/iOS/desktop regressions, unchanged protected refs and the authoritative exact-SHA hosted workflow. Final package hashes, hosted run and conditional tablet evidence are recorded only after the immutable candidate SHA exists. Demo fixtures establish deterministic behavior and UI reachability only; physical I/Q, audio quality, calibrated dBm, authenticated services, CAT/PTT/TUNE, RF, signing and release remain separate.

## Android TCI Transmit v5 gate

V5 requires the immutable upstream/protocol audit, one-owner TX checker, native framing tests, focused authority/interlock tests, deterministic fake stress, complete Android/native/Rust/iOS/desktop regressions, package caps, protected refs, clean pushed exact SHA, and all mandatory hosted jobs. Production acceptance remains identity-bound and unverified unless separately evidenced. Physical PTT/Tune/RF may remain pending without converting source-complete fake/bench acceptance into RF proof.

## Secure Remote Station v6 gate

V6 requires the frozen protocol/security/media contracts, Windows/macOS/Linux stationd packages without private identities, Android client compilation and packages, protocol/security/scale sanitizers, unchanged platform regressions, a clean pushed exact SHA and the authoritative hosted workflow. Protected-tablet install is conditional and preservation-gated. Signing, deployment, authenticated public-internet operation, physical audio/CAT/PTT/Tune/RF, real WSJT-X/fldigi and rotator motion remain separate.

## Final 0.1.0 RC1 publication gate

Publication additionally requires the full Linux GUI packages, native arm64 stationd, iPhone/iPad simulator and unsigned XCArchive, SwiftUI/Qt remote clients, Opus/raw-IQ tests, Android control-surface compilation, all exact-SHA hosted jobs, the protected-tablet in-place gate, fast-forward main promotion, release asset re-download/hash verification, and safe cleanup. Failure of any hard gate prevents main promotion and prerelease publication.
