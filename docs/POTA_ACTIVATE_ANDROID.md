# Android POTA Activate

## Purpose and lifecycle

Portable → **ACTIVATE** is a local-first POTA session manager and fast logger. Setup records editable station/operator identity, one or more own park references, the primary park, location/grid/state, station profile, radio, power, antenna, notes, and UTC start. Starting requires the operator to acknowledge that they and all station equipment are on public property and entirely inside every selected park boundary; ShackCQ does not treat GPS or catalogue proximity as legal proof.

One active session is stored in an app-private `AtomicFile` journal after each meaningful change. A recovered process shows an explicit Resume card and starts no CAT, audio, macro, PTT, TUNE, or transmit action. Finish atomically retains a local review before removing the active pointer. Abandon removes only session state; committed QSOs stay in the existing SQLite journal.

## Logging, P2P, and multi-park data

The fast logger autofocuses callsign, supports keyboard entry, uses live CAT frequency/mode when available, and remains editable with CAT offline. It performs optional non-blocking callbook/CTY enrichment and saves through the existing `QsoDatabase`; Wavelog mode queues the same single local QSO through the existing outbox. It never creates one QSO row per own park.

Activation QSOs add backward-compatible `details_json` keys:

```text
activationSessionId
activationProgram = POTA
myPotaRefs = [all own parks]
potaRefs = [all other activator parks]
```

`myPotaRef` and `potaRef` retain the respective primary references for existing ADIF/Wavelog compatibility. Wavelog therefore receives only the primary own and other park through its current serializer; ShackCQ retains every reference locally for correct export and does not duplicate uploads. A POTA Chase row can open an unsaved, editable P2P draft without tuning or transmitting; manual P2P references are also supported.

## Progress, review, and export

The session shows total QSOs, unique calls, band/mode counts, P2P count, elapsed UTC time, and the current UTC day's local progress toward 10 QSOs. It warns 15 minutes before UTC rollover, continues the session at midnight, resets only the displayed current-day count, and retains prior-day QSOs.

Finish shows an honest local review. Export validates identity, callsign, future timestamp, band, ADIF mode/submode, and structured references before creating files. Empty or invalid sessions produce a correction list rather than an invalid file. Files are generated for each:

```text
own POTA reference × UTC date
STATIONCALL@REFERENCE-YYYYMMDD.adi
```

Each applicable QSO is emitted in every own-park file with `MY_SIG=POTA` and that file's `MY_SIG_INFO`. A multi-reference P2P QSO is repeated at the unchanged time once per unique other park using `SIG=POTA` and one `SIG_INFO`, matching POTA's documented duplicate/P2P rules. Share All, per-file Share, and user-selected document save use generated files separate from the local journal.

## Browser handoffs and offline behaviour

`OPEN POTA SPOTTING` copies a concise operator-editable context summary and opens the normal official POTA site. The review opens the same official site for the operator to choose My Log Uploads. ShackCQ does not call a private/write endpoint, authenticate to POTA, post a spot, upload a log, or claim browser submission success.

After the POTA catalogue is downloaded, setup, manual reference entry, session recovery, CAT-optional logging, P2P entry, progress, review, ADIF generation, and later sharing remain local. Live Chase, callbook lookup, and POTA web handoffs degrade independently and do not block logging.

## Validation and exclusions

- Focused JVM coverage includes session round-trip/recovery rules, abandonment data ownership, scalar/list metadata, 10-QSO UTC logic, rollover, reference/date export fan-out, multi-P2P expansion, required ADIF tags/filenames, old JSON compatibility, radio-passive lifecycle/export, empty-export rejection, and the SOTA-live approval guard.
- Final gate passed on 2026-08-18: `ANDROID_HOME=/Users/oliver/Library/Android/sdk ANDROID_SDK_ROOT=/Users/oliver/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` (`117` tests, `0` failures, `0` errors; all configured ABIs assembled).
- Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`, version `0.1.0` (`1`), 86,267,945 bytes, SHA-256 `063a479d7a1c8b9d6143d2aad286f300dbe31df1963178d0703499b2463636f2`.
- Lenovo TB373FU smoke passed with `adb install -r`, preserving app data and the downloaded park catalogue. A local `SK-0121` session was started with CAT offline, recovered only after explicit Resume following force-stop/relaunch, and finished with zero QSOs. The review rejected empty ADIF export, no fake QSO was saved, no RF transmission was performed, and logcat contained no fatal exception.
- Device evidence: `docs/pota-activate/evidence/pota-activate-setup.png`, `pota-activate-operating.png`, and `pota-activate-finish.png`.

Excluded: direct POTA spotting/upload APIs or credentials; automatic CQ/PTT/TUNE/CW/voice actions; automatic QSO creation; SOTA or WWFF activation; SOTA live API work; awards/sync-provider expansion; iPadOS parity; Nexus incorporation.
