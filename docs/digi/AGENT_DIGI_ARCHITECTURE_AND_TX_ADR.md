# Agent-local Digi architecture and TX authority

Status: receive-side implementation accepted for review; transmit release is blocked. This ADR governs the code in `AgentDigiController`, `CloudAgentClient`, `DesktopRadioController`, and the linked `shackcq-flex` static library.

## Ownership

There is one local Digi owner, one existing radio owner, and one selected Qt audio input/output profile. The browser sends finite typed intents. It never sends PCM, raw CAT, PTT, filesystem paths, or WAN-timed samples. Expiry and lease duration use the Agent monotonic clock. The current implementation has no measured UTC authority, so slotted-mode arming fails closed with `CLOCK_QUALITY_UNVERIFIED`; it must not claim a slot boundary. The web/server cannot grant local TX or hardware acceptance.

The command state machine is `SAFE/RX -> PREPARING -> READY_FOR_EXPLICIT_ARM -> ARMED_FOR_VALID_WINDOW -> PTT_CONFIRMED -> LOCAL_AUDIO_TRANSMITTING -> STOPPING -> RX_VERIFIED`. Failure to prove PTT release latches `RX_UNCONFIRMED` and blocks subsequent work. STOP is idempotent and releases only PTT acquired by this Digi owner.

This state machine is not yet sufficient evidence for TX release: encoding and Hamlib calls can still occupy the Qt event loop that owns STOP and watchdog timers. Transmit packaging and deployment remain blocked until an independently scheduled safety path can de-key and verify RX even if the command/encoder path stalls.

## Five independent TX gates

1. The native Digi encoder is compiled into the package.
2. `SHACKCQ_DIGI_TX_ENABLED=true` is present at the hosted server. Production default is false.
3. The local Agent configuration explicitly permits Digi TX. Restore cannot contain armed/runtime state.
4. The exact local radio identity has an operator hardware/dummy-load acceptance record.
5. A controlling browser session holds a current lease and explicitly prepares, arms, and schedules a finite waveform inside a valid window.

Generic Radio v1 intentionally continues to advertise no PTT/Tune setter. Only `AgentDigiController` can call the non-invokable dedicated PTT method. Server disable, lease loss, takeover, stale generation, late slot, route loss, local stop, or uncertain readback cancels the intent; reconnect never resumes it.

## Audio and DSP

The Agent enumerates `QMediaDevices` without opening them. A profile stores opaque device IDs, exact mono Int16 format, and a sample rate of 12–192 kHz. RX opens only after an explicit local/web start. The 12 kHz DSP stream, slot decoder, continuous decoder, and 512-bin display work run locally; no cloud audio archive exists. The present mono/sample conversion is only a bounded baseline, not release-quality channel selection or anti-aliased resampling, so real-device acceptance remains blocked. Qt documents device identity and exact format checks in [QAudioDevice](https://doc.qt.io/qt-6/qaudiodevice.html), input pull operation in [QAudioSource](https://doc.qt.io/qt-6/qaudiosource.html), and output ownership in [QAudioSink](https://doc.qt.io/qt-6/qaudiosink.html).

FT8/FT4/FT2/FST4/Q65/MSK144/JT65/WSPR use the existing `mfsk-core` slot implementation. CW, RTTY, BPSK31, and SSTV use the existing portable streaming/encoder ABI. QPSK31, Fox/Hound, raw keying, arbitrary CAT, remote microphone, and rotator control are not claimed. WSJT-X is optional loopback companion provenance only; the embedded engine remains primary. The WSJT-X guide's strict clock requirement is reflected by separate UTC/sample uncertainty and local scheduling: [WSJT-X User Guide](https://wsjt.sourceforge.io/wsjtx-doc/wsjtx-main-2.5.4_en%20%28USLetter%29.pdf).

## Shared receive interlock

Preset recall, Band Stack cycle, scanner steps, and generic radio mutation are rejected while Digi is prepared, armed, transmitting, stopping, or RX-unconfirmed. RX-side changes clear capture context and require radio readback. Scanner entries are resolved from tenant-owned presets by the server, then stepped by a bounded local timer; they never persist as executable commands and never restart after reconnect.

## Persistence and privacy

Only inert audio/TX acceptance configuration persists. Prepared audio, lease, arm, scanner state, PCM, FFT rows, receive samples, and PTT state do not. SSTV upload accepts one bounded JPEG/PNG payload encoded to a finite Agent-local waveform; the server relays it in a protocol frame and does not store it. Agent snapshots retain at most 256 decode records internally, expose 64, and carry at most eight metadata-bearing 512-bin rows.
