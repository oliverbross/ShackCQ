# Android hardening live acceptance

Date: 2026-08-25
Package: `app.shackcq.mobile`

## Mandatory boundary

The protected Lenovo install is allowed only after source, sanitizer, package and exact-SHA hosted gates pass. The app must already be installed, a private-data backup with hashes must complete first, and installation must use only `adb install -r`. No uninstall, data clear, credential display, random monkey input, CAT/PTT/TUNE, RF or rotator motion is permitted.

## Pre-install gates

- Local Android, Rust, native, sanitizer, Apple, package and release-contract gates: PASS.
- Candidate arm64 APK: 58,426,676 bytes; SHA-256 `f99b529f43e28bc16834fd80cd488293234d5399e04a972d2d87ae83240896b9`.
- Exact-SHA hosted workflow: run `32784249372`, all seven jobs PASS at `826ba3031d869f12e0c9d37649257f9b2fac1ecf`.

## Device evidence

| Check | Result |
|---|---|
| Device selection | Both ADB aliases resolved to the same TB373FU/package path; all work bound to `192.168.4.232:46455`. |
| Package before install | Present at an app-private package path. |
| Backup | `evidence/protected-tablet-backup-20260825-android-hardening`; 359 files, 372 MB. Private values were not printed. |
| Backup manifest | `private-data.sha256` digest `c08a0820c61f58cea005be4f2610ff628e44265da8077d081a6d228c8dd97a62`. |
| UID | `10352` before and after. |
| Schema compatibility | QSO 16, Neural 5, Contest 2, Digi 2, Groups.io 2, DX Chaser 1; exact candidate versions. |
| QSO relationship | 67,223 canonical rows, 67,223 projection rows, zero missing and zero orphan projections before and after lifecycle cycles. |
| Install | `adb install -r` returned `Success`; no uninstall or clear-data operation occurred. |
| Private-data comparison | All 359 files matched except two SQLite `-shm` coordination files; database/WAL/content/preference/credential-ciphertext hashes were unchanged. |
| Force-stop/relaunch | 25/25 produced a live fresh process. |
| HOME/relaunch lifecycle | 20/20 returned successfully with one stable PID and no duplicate process. The secure keyguard prevented this from proving visible foreground rendering. |
| Crash evidence | Initial, post-cycle, post-soak and final-relaunch crash buffers empty; no FATAL, ANR, native-abort/tombstone or repeated resource-leak line found. |
| Visual/workspace navigation | Pending: the tablet is awake behind its secure keyguard; it was not bypassed. |
| Resource soak | PASS for locked-state process/resource stability: 30 minutes, one stable PID, 34 observations, no duplicate process and no crash. This does not substitute for unlocked foreground-provider/navigation acceptance. |
| Final relaunch | Force-stop and explicit launcher start succeeded with fresh PID `25655`; the crash buffer remained empty. |

Resource measurements were bounded. The initial post-install sample was total PSS 275,357 KB, native-heap PSS approximately 60,960 KB, 53 threads and 180 FDs. The soak started at 268,607/58,564 KB, reached a midpoint of 289,276/67,984 KB, and ended at 260,130/65,060 KB; threads remained 40 and FDs 179-180 during the soak. The fresh final process measured 232,369/61,980 KB, 54 threads and 189 FDs during startup, then 247,443/55,228 KB, 52 threads and 189 FDs at the settled sample.

## Acceptance boundary

Implementation, local/hosted validation, protected in-place installation, UID/private-data/schema/QSO preservation, relaunch cycles, locked-state process stability and crash evidence pass. Full programme PASS is blocked only by the secure keyguard: safe visible workspace navigation and a true unlocked foreground-provider soak were not performed. No keyguard bypass was attempted.

## Evidence not implied

Source/build/install/launch do not prove authenticated Groups.io/Wavelog behavior, real radio control, exact audio routing, CAT/PTT/TUNE, RF transmission, physical rotator motion or complete gesture/visual acceptance. Those layers remain pending unless separately performed under explicit authority.
