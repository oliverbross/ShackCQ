# Android app size and module policy

The Windows integration adds no Android production module and preserves the four-ABI AAB ceiling of 60 MB and arm64 tablet APK ceiling of 130 MB. Desktop Qt/Hamlib runtime packaging is audited separately: Windows portable/installer each remain capped at 120 MiB and unpacked content at 350 MiB.

## Sweep 2 package decision

No SCP, IOTA, WWFF, WWBOTA, Castle or Lighthouse directory is packaged. All accepted catalogues are bounded app-private runtime caches or user-selected imports. Sweep 2 adds Kotlin/Compose and schema code only; final APK/AAB sizes are recorded from final-tip artifacts.

## Sweep 1 package decision

Sweep 1 adds no embedded provider catalogue, model, binary payload, or P.533 material. POTA/SOTA catalogues remain bounded runtime caches in app-private storage and SOTA cluster rows are transient. The pre-final local debug artifacts remain below the 130 MB APK and 60 MB AAB ceilings; final sizes and SHA-256 values are recorded only after the final source commit is built.

ShackCQ remains one coherent product. Neural DX, HamClock, Wavelog, and Groups.io are not separate user-facing applications or dynamic feature modules.

Keyer, Contest/N1MM and DX Chaser remain Kotlin/Compose code and small schema-1 private stores in the
same base module. They add no model/runtime payload, upstream desktop binary, provider database,
P.533/ITU data, test screenshot family or dynamic feature.

Internal gates:

- universal debug APK: at most 130 MB;
- debug AAB compressed estimate: at most 150 MB;
- the Neural/HamClock empirical-outlook phase adds no runtime, executable model, downloaded model or data asset; its APK delta target is at most 5 MB and debug AAB phase target is at most 60 MB total.
- no new optional asset family above 10 MB without a manifest and decision;
- no ITU/P.533 implementation source or coefficient payload.

The deterministic audit is `scripts/audit_android_package_size.py`. It reports archive totals, the largest 40 entries, dex/resources/assets/native-library totals, ABI totals, repeated basenames and identical payloads, files above 1 MB, reference APK deltas, and the ITU/P.533 payload scan.

Size remediation order is duplicate/obsolete artefact cleanup, preserved App Bundle splitting, runtime caches outside the package, and—only if useful—an optional arm64 physical-test APK property. Dynamic features are justified only if the consolidated base remains above 150 MB after cleanup or a future optional content family exceeds 20 MB.

The final hardening artifacts remain within the same single-module policy: debug APK 114,649,022 bytes (`7402417fe8533b93daf67b714bf22279ca3367986ab8340a7479dcd6d8a1abe1`) and debug AAB 51,623,548 bytes (`7f56e9416ea2a54ab00c62f3ac1d3637c46e53bfcb68c6cf6778790dfe8b1376`). The audit reports a +4,828,110-byte APK delta against the combined-integration reference, below the +5 MiB phase target, and passes the ITU/P.533 payload scan. Detailed evidence is in `NEURAL_OUTLOOK_FINAL_HARDENING.md`; the earlier consolidation record remains in `UNIFIED_NEURAL_HAMCLOCK_CONSOLIDATION.md`.

## Nexus Digi v2 package evidence

The Nexus Digi completion remains in the same base module and adds no bundled
model, upstream desktop runtime, WebView asset family, Fortran/FFTW payload or
dynamic feature. Final debug artifacts:

- universal APK: 110,437,257 bytes,
  SHA-256 `41887ea94b24fc795db4038f9dd11618cc46ff9576b1354820da4afc3bbbeaba`;
- AAB: 51,797,260 bytes,
  SHA-256 `fbb02d4b7ae9cae68217a3bff828c38c7a14574097da763e4c73c275d7ad9940`.

The deterministic audit reports APK delta +616,345 bytes versus the combined
integration reference and +330,767 bytes versus Finish-Line. Both artifacts
pass the ITU/P.533 payload scan and remain below the existing package ceilings.

## Sweep 2 package strategy

`shackcqAbi` is the single-ABI tablet build property. `-PshackcqAbi=arm64-v8a` narrows Android JNI, Rust and Hamlib outputs together; the default release bundle retains `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Native compilation enables hidden visibility, section splitting and linker garbage collection. No dynamic feature, asset pack or second Hamlib archive is introduced.

Sweep 2 gates are: arm64 debug APK at or below 130 MB, four-ABI release AAB at or below 60 MB, no developer tools/test corpora/source archives in either artifact, and no ITU/P.533 payload. Exact current sizes belong in release evidence only after the final SHA build.

The final local Sweep 2 package inputs produce an arm64-only debug APK of 58,293,188 bytes (`00b3c2eb7c6143d65e970d030ca096a48830d770c1cde76adc530a396d054be8`) and a four-ABI debug AAB of 55,606,070 bytes (`29cab575b7d403a1876780806a397f5a73b69ec1ac11136e23a0da4bca8b414f`). The AAB contains `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; the APK contains only `arm64-v8a`. Both audits pass the executable, duplicate runtime, private-evidence, manual/firmware and ITU/P.533 exclusions. The universal APK was not rebuilt because it is unnecessary for the protected tablet.

Sweep 3 adds Kotlin/Compose state and no new native archive, dynamic feature, geometry bundle, protected WWFF directory or executable. Its final artifacts retain the same 60 MB four-ABI AAB and 130 MB arm64 APK ceilings and require a fresh package audit.

## Android lifecycle hardening package evidence

The hardening adds a small C++ host-only lifecycle test target, Kotlin ownership helpers and no production payload family. The final local arm64 APK is 58,426,676 bytes (`f99b529f43e28bc16834fd80cd488293234d5399e04a972d2d87ae83240896b9`). The four-ABI AAB is 55,739,195 bytes (`e43aeb115149899d19d95060464fd5274cb74612d8a60c66a8fe3976aee8f053`). ABI membership, size ceilings, prohibited payloads, private evidence, rigctl/rigctld and duplicate libc++ checks pass.

## Windows parity candidate measurements

The desktop source adds no bundled dataset or third-party payload. The local unsigned macOS ZIP is 79,173,790 bytes with SHA-256 `ff65aa017af44f13c0c8e576f503eee351de1d17020863c54109333e604ca6fe`. The Windows exact-SHA workflow enforces 120 MiB ceilings for both portable ZIP and NSIS installer and 350 MiB unpacked. Current regression artifacts: arm64 APK 58,425,796 bytes; four-ABI AAB 55,730,956 bytes. Final Windows byte counts/hashes remain hosted evidence.

The closure links one existing Rust static library and adds no model, map, audio or catalogue payload. The 120 MiB Windows ZIP/installer and 350 MiB unpacked ceilings remain enforced; exact-candidate measurements supersede older example hashes and are reported only from hosted artifacts.

## Multiplatform RC1 package policy

The final RC SHA must produce an arm64-only Android APK, a four-ABI AAB, a Windows x64 portable ZIP and per-user setup executable, and an unsigned macOS arm64 ZIP. Every filename carries the exact SHA and every file appears in `SHA256SUMS.txt`. Existing Android and Windows ceilings remain gates; source/SBOM/manifests are additional artifacts, not production payload. No package may contain private evidence, credentials, databases, rig control utilities or duplicate native runtimes.

## Android SDR enhancement budget

Hard gates remain 130 MB for the arm64 APK and 60 MB for the four-ABI AAB, with a preferred AAB delta of at most 3 MB from the 55,757,902-byte RC1 baseline. The enhancement may add OkHttp bytecode and one small native DSP translation unit; it must not package SDRoxide assets, map textures, neural voice/noise models, test I/Q, recordings, or private evidence.

## Android SDRoxide operational v2 budget

V2 adds Kotlin/C++ code and a small SQLite schema only. It adds no bundled upstream source, recording, raw IQ, decoder model, map, shader, or P.533 payload. Gates remain arm64 APK less than 130 MB and AAB less than 60 MB, with a preferred AAB delta no greater than 2 MB from the frozen v1 base. Final exact-SHA byte counts and hashes supersede candidate estimates.

Local final-source packages pass: arm64 APK 64,855,821 bytes (`8ad0b7629a89c96606246490d406b76da0a33b119575db5beebc5709d90a3203`); four-ABI AAB 56,262,274 bytes (`3820a2cff78e5b7df45e29a32c17d25507fa2d7594031d08bd769bcd46c68c19`); AAB delta from the frozen v1 baseline is +113,682 bytes. The instrumentation APK is 1,196,331 bytes (`541a2fa7b74f7277eb0c0642ecc7b81fb31df9c359193e18f554d44f435fe36c`). Hosted exact-SHA artifacts remain authoritative for delivery.

## Android Local SDR Receiver v3 budget

V3 adds source code and one small metadata schema only. Synthetic I/Q is generated at runtime; no fixture, recording, RDS dataset, SDRoxide asset, neural model or codec library is packaged. Gates remain arm64 APK at most 130 MB, four-ABI AAB at most 60 MB and preferred AAB delta at most 2 MB from the v2 56,261,966-byte baseline. Final exact-SHA sizes and hashes replace estimates.

## Android SDR Workbench v4 budget

Frozen v3 baselines are arm64 APK 65,104,138 bytes and four-ABI AAB 56,431,306 bytes. V4 adds Kotlin/Compose code and an empty-on-install SQLite schema; recordings and survey rows are runtime app-private data. No I/Q/audio fixture, SDRoxide asset, model, map, codec or new native dependency is packaged. Hard gates remain arm64 APK at most 130 MB and four-ABI AAB at most 60 MB, with preferred AAB delta at most 2 MB. Exact final-SHA sizes/hashes supersede this policy row.

## Android TCI Transmit v5 budget

Exact v4 baselines are arm64 APK 59,321,767 bytes and four-ABI AAB 56,593,563 bytes. V5 adds Kotlin/C++ source only: no model, recording, TX-audio fixture, SDRoxide payload, codec or dependency. Hard gates remain arm64 APK at most 130 MB and four-ABI AAB at most 60 MB. Final exact-SHA paths, sizes and SHA-256 hashes supersede estimates.

## Secure Remote Station v6 budget

V6 adds Kotlin, C++/Qt, QML, protocol tests and documentation only. It bundles no certificate, private identity, audio/IQ recording, radio firmware, SDRoxide payload, model or new codec. Android retains the 130 MB arm64 APK and 60 MB four-ABI AAB caps; stationd packages are exact-SHA, unsigned, and scanned to exclude private identity material.

## Final RC package ceilings

The pinned Opus library is source-built and measured in every final package. Ceilings are: Android arm64 APK 130 MB, Android AAB 60 MB, Windows portable 150 MB, Windows installer 110 MB, macOS ZIP 200 MB, Linux portable 180 MB, Linux DEB 150 MB, and source archive 100 MB unless the recorded vendored GPL source inventory justifies the measured excess. Release staging rejects private keys, credentials, databases, recordings, and large demo media.
