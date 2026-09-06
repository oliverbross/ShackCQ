# Android lifecycle stress evidence

Date: 2026-08-25

## Executable contracts

The focused JVM contract uses fakes, latches and actual concurrent calls. It covers post-close Feature CTY/worked/cluster rejection, close-twice, close racing an active lease, 1,000 Feature cycles, 500 Digi-style replacements, 500 Flex-style sessions, 500 cancelled satellite generations, 100 map style/camera disposal simulations, closed-owner import rejection, bounded no-active-call close and multi-owner close order.

Existing executable suites retain Digi stop/route/background safety, voice repeat-CQ cleanup, Hamlib session and connection behavior, rotator background/disconnect safety, Contest process restoration, temporary store fixtures and global Stop contracts. New instrumentation fixtures cover neutral zero-handle JNI behavior, malformed Panadapter calls, and schema-16 close/reopen/repair. Instrumentation was compiled locally; it is not run through a Gradle connected-device task on the sole protected tablet.

## Native and Rust stress

- C++ lifecycle target: 1,000 feature create/watchlist/destroy cycles plus 500 base-context and 500 Panadapter create/destroy cycles.
- Rust FFI test: 500 Flex context and 500 Digi context create/destroy cycles.
- Hamlib JVM test: 500 dummy native-session owner create/close cycles.

## Sanitizers

Host Clang Debug build:

```text
SHACKCQ_ENABLE_ASAN=ON
SHACKCQ_ENABLE_UBSAN=ON
shackcq_core_tests                    PASS
shackcq_propagation_adapter_tests     PASS
shackcq_lifecycle_stress_tests        PASS
```

No AddressSanitizer or UndefinedBehaviorSanitizer finding was emitted. ThreadSanitizer was not used: the JNI/Android and vendored dependency toolchains are not one compatible TSan surface, while deterministic owner races and ASan/UBSan are reliable here. The exact sanitizer command is also part of the hosted `rust-native` job.

## Package artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| arm64 debug APK | 58,426,676 | `f99b529f43e28bc16834fd80cd488293234d5399e04a972d2d87ae83240896b9` |
| four-ABI debug AAB | 55,739,195 | `e43aeb115149899d19d95060464fd5274cb74612d8a60c66a8fe3976aee8f053` |

## Protected-device process stress

The protected Lenovo passed 25/25 force-stop/relaunch cycles, 20/20 HOME/relaunch lifecycle cycles and a 30-minute locked-state process/resource soak with one stable PID and an empty crash buffer. Soak total PSS moved from 268,607 KB through a 289,276 KB midpoint to 260,130 KB; native-heap PSS moved from 58,564 KB through 67,984 KB to 65,060 KB. Threads remained 40 and FDs 179-180. A final force-stop/relaunch produced a fresh process and another empty crash buffer.

The secure keyguard remained active throughout. These measurements prove bounded process/resource behavior behind the lock screen, not safe visible workspace navigation or a true unlocked foreground-provider soak. The exact device boundary is recorded in `ANDROID_HARDENING_LIVE_ACCEPTANCE.md`.
