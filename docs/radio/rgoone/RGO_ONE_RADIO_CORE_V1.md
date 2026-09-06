# ShackCQ Native RGO ONE Radio Core v1

## Scope

This branch adds an Android-only, integration-ready RGO ONE core under
`app.shackcq.mobile.radio.rgoone`. It does not connect to the central Radio screen,
`UsbRadioTransport`, native core, Digi, Panadapter, QSO storage, audio ownership, or
configuration recovery. A later semantic adapter owns those connections.

The core provides:

- bounded semicolon framing and typed parsing for the officially documented V6 CAT protocol;
- exact command builders with no firmware unlock or flash path;
- explicit V6, series 5/5+, and unknown-generation profiles;
- firmware and capability gating;
- modular ATU, NB, AF/DSP, speech processor, transverter/RX antenna, and USB-audio truth;
- one serialized transport owner with startup probing, three poll cadences, cancellation,
  identity-stable reconnect, stale-state truth, and idempotent close;
- safe settings and redacted diagnostics;
- an adaptive Compose surface that emits typed actions only.

## Evidence boundary

The V6 implementation is based on the official CAT manual v1.03, V6 operating
manual v1.01A, firmware 1.08 and 1.09 notes, and listed option manuals. The current
official firmware is 1.09. Firmware 1.09 corrects the memory commands described in
the CAT manual.

No official series 5/5+ CAT command reference was located. Those generations are
represented, but all command capability remains unknown and writes remain disabled.
Likewise, the official documents do not specify serial framing, an exact CAT command
for the displayed filter/bandwidth, a published USB audio descriptor profile, or a
general command-based absence probe for every optional module. The core records those
as unknown rather than inferring them.

## Safety decisions

- Unknown generation is connected as read-only metadata truth; it receives no V6-only probe.
- V6 `ID`/`FW` are sent only after operator or USB-identity evidence confirms V6.
- Safe setters require explicit write confirmation and a safety-port allow-once result.
- transmit, tune, RIT edge commands, receive, and memory writes are single-shot writes and
  are never retried blindly;
- memory write defaults disabled even on firmware 1.09;
- `UN` is never built or sent;
- `SN` responses are immediately converted to SHA-256 and raw serial text is never placed
  in snapshot, UI, diagnostics, or normal support export;
- imported settings always restore write confirmation and memory write to off.

## Polling

- Fast: VFO A/B, RX/TX VFO, mode, fine tune, and S-meter.
- Medium: AGC, RIT/XIT, RF gain, power, keyer/mic gain, preamp, attenuator, and NB.
- Slow: firmware, ATU, and documented EX menu 42 AF/DSP state.

Duplicate cadence execution is suppressed and all exchanges share one serialization
lock. USB CAT leaves baud/framing to the USB virtual interface because the official
manual states menu 22 affects only TTL. TTL is blocked until framing evidence is supplied;
the documented TTL baud choices are 9600, 19200, 38400, and 57600.

## Display

`RgoOneRadioSurface` keeps the active VFO dominant, the alternate VFO secondary, and
shows mode, RX/TX VFO, split, AGC, filter truth, meters, firmware, connection, and module
truth. Compact, standard, and wide layouts share the same feature set. Actionable controls
appear only for `SUPPORTED_PRESENT`; absent and unknown modules remain status truth rather
than a wall of disabled buttons. The surface has no direct CAT dependency.

## Validation

Source/build validation completed on 23 August 2026 from the isolated feature worktree:

- the RGO ONE official-source watcher reported `CURRENT`, its offline fixtures passed,
  and the existing Wavelog watcher reported `NO CHANGE`;
- `:app:testDebugUnitTest` passed all 586 JVM tests with zero failures or errors,
  including 28 focused RGO ONE cases;
- `:app:assembleDebug` passed and produced a 117,304,689-byte debug APK with SHA-256
  `0a4f7383814dd7499a0eeda2de76305ad49d843597a248bae99df85f5c38e4cb`;
- `:app:compileDebugAndroidTestSources` passed, compiling the two RGO ONE
  instrumentation cases without running them;
- `:app:lintDebug` passed after the new package's sole Compose advisory was corrected.
  The repository-wide report retains 177 pre-existing warnings and 37 hints, with no
  finding attributed to the RGO ONE package;
- `git diff --check` and the ownership/package audits passed: all five production files
  are inside `app.shackcq.mobile.radio.rgoone`, with no central-radio owner, Android USB,
  QSO/provider, Panadapter/Digi, firmware-flash, or `UN` command dependency.

No APK installation, device/emulator execution, USB enumeration, radio connection,
physical display comparison, audio, transmit, tune, memory write, dummy-load, or RF test
was performed. Those evidence layers remain pending under `LIVE_ACCEPTANCE.md`.

Delivery verdict: **PARTIAL — CORE COMPLETE WITH EXPLICIT OFFICIAL-DOCUMENT BLOCKER**.
The blocker is documentary rather than a source/build failure: the reviewed official set
does not provide a series 5/5+ CAT command reference, TTL data/parity/stop framing, an exact
filter/bandwidth CAT command, or a published USB-audio descriptor profile.
