# Hamlib Android Radio Platform v1

ShackCQ vendors Hamlib 4.7.2 as source and builds its radio library and all configured radio backends for Android. The library is statically linked into the existing `libshackcq.so`; ShackCQ does not package `rigctl`, `rigctld`, rotator tools, amplifier tools, or a second C++ runtime.

The platform owns model discovery, bounded capability projection, a lifecycle-safe native session, external Android USB serial bridging, opt-in network profiles, typed commands, polling, diagnostics, settings documents, and a generic capability-driven Compose surface. It deliberately does not modify or register itself with ShackCQ's central radio controller or radio screen in this task.

## Truth boundary

- Source/build validation can prove the pin, compilation, registration, API shape, safety defaults, and packaged contents.
- No APK is installed and no physical radio is connected in this programme.
- No source/build result proves real USB permission behavior, CAT compatibility, RF output, PTT, TUNE, or on-device UI acceptance.
- Read-only is the default. PTT and TUNE are distinct typed transmit actions and are never restored or issued automatically.

## Build shape

`android/app/src/main/cpp/hamlib/build_android.sh` cross-compiles a static archive per configured Android ABI with the project NDK. The existing CMake target imports the matching archive and compiles `hamlib_jni.cpp` into `libshackcq.so`. Generated `config.h` is ABI-specific.

## Validation record

Validated in the isolated worktree on 2026-08-23 without installing an APK or connecting a radio:

- immutable source check: 4.7.2, commit `40f63488fe0bd751b147f48d62fd217bf53713a0`, 1,048 files, 37 configured radio backends, QMX present, dedicated RGO model absent;
- exact compiled host enumeration: 302 radio models; one QRPLabs QMX row (`RIG_MODEL_QRPLABS_QMX`, upstream status Beta); no dedicated RGO ONE row;
- Android NDK 28.2.13676358 static archives:
  - armeabi-v7a: 13,307,110 bytes;
  - arm64-v8a: 17,700,834 bytes;
  - x86: 12,224,182 bytes;
  - x86_64: 17,393,554 bytes;
- every archive exposes 38 radio, 27 rotator, and 4 amplifier backend initializers; the extra non-radio backend dependency closure is required to make the single Hamlib library self-contained, but no rotator/amplifier/tool executable is packaged;
- JNI arm64 compile passes with `-Wall -Wextra -Werror`; the Gradle/CMake matrix links `libshackcq.so` for all four ABIs;
- 32 focused Hamlib JVM cases pass; the complete 590-test JVM suite passes with zero failures, errors, or skips; four Android JNI registry instrumentation cases compile but are not executed on a device;
- host core CMake build passes both CTest modules;
- debug APK, debug AAB, Android-test source compile, and lint all pass in one Gradle matrix;
- package audit finds no `rigctl`, `rigctld`, separate Hamlib shared library, duplicate `libc++`, or prohibited ITU/P533 payload.

Artifacts:

- `android/app/build/outputs/apk/debug/app-debug.apk`: 154,559,952 bytes, SHA-256 `2ea934c36fed0e113a979dc5ee5014a1ea1bd33fe77849f0a3334a59b70e472f`;
- `android/app/build/outputs/bundle/debug/app-debug.aab`: 58,388,782 bytes, SHA-256 `78d76c0df71e6811ef09fb75c97013efff417741534ec10d57c460ad65a1026a`.

The APK is 44,739,040 bytes larger than the repository package-audit script's combined-integration reference and 44,453,462 bytes larger than its Finish-Line reference. Those are established project references, not a newly rebuilt exact-base APK. Native payload totals 128,008,760 uncompressed bytes across four ABIs.

An earlier aggregate JVM run measured an unrelated N1MM 10,000-QSO timing case at 17,755 ms against a 15,000 ms limit under heavy machine contention. After the Hamlib test consolidation, the first 590-test run encountered a separate transient N1MM loopback-port bind collision; that exact test passed in isolation in 17 seconds and the subsequent complete 590-test suite passed in 29 seconds. The initial native link also exposed and drove correction of the Hamlib rotator/amplifier dependency closure and the standard NDK `libandroid` sensor dependency; the final four-ABI link matrix passes.
