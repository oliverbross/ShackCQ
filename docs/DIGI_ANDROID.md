# Android Digi integration

## Delivered engine

Android Digi uses component-audited source from Nexus commit
`6ec4a7925f1550cc364c7fd95967ce38c696ad3f`.

- CW: deterministic waveform synthesis plus adaptive streaming audio decode.
- RTTY: ITA2/USOS character handling, shaped 45.45-baud AFSK generation,
  streaming fldigi-derived demodulation, squelch, confidence, and AFC.
- SSTV: streaming VIS detection and progressive image decode; TX/RX for PD-50,
  PD-90, PD-120, PD-160, PD-180, PD-240, PD-290, Robot 24/36/72,
  Scottie 1/2/DX, and Martin 1/2.

The complete `tempo-sstv` MIT licence and NOTICE are retained under
`rust/tempo-sstv`. CW/RTTY provenance is recorded in
`rust/shackcq-flex/UPSTREAM.md`.

## Android operating path

KX2/KX3 receive uses the selected USB audio input, disables Android AGC,
noise suppression and echo cancellation, then resamples to the modem's
12 kHz clock. Transmit acquires the selected DigiRig output before any CAT
action, sets Elecraft DATA mode, verifies fresh `TQ` after `TX;`, streams
the generated waveform, sends `RX;`, and verifies RX. Failure is fail-closed
or visibly RX-unconfirmed.

Flex receive taps decoded VITA float or Opus PCM before playback gain/mute,
downmixes it to mono, and feeds the same 12 kHz modem clock without using an
Android microphone. Flex transmit uses the existing session transmit gate,
interlock, remote audio stream, Opus packetizer, MOX lifecycle, and bounded
cleanup. Both paths are built and protocol-tested but have no physical Flex
hardware acceptance in this delivery.

After TX, Elecraft success requires confirmed DATA mode, confirmed PTT, complete
audio, an RX command and `TQ` confirming receive. Flex success is returned only
after `stopTransmit` completes and the controller observes `DISABLED`, `READY`
or `ARMED`; a bounded timeout remains visibly `RX UNCONFIRMED`. That latch
disables TX and decoder restart until `REQUEST RX & RECHECK` confirms receive.

Mode, CW pitch/speed, RTTY sense, SSTV mode, and TX text persist in app-private
preferences. Arming and selected image pixels deliberately do not persist.

## Not exposed

Weak-signal modes are exposed on Android through ShackCQ's GPL-compatible
`mfsk-core` native bridge. The typed `DigiCapabilities` registry is the
authority for picker visibility, variants, fixture status, sequencing,
waterfall tuning, TX duration and ADIF mapping. FT8 and FT4 have the automatic
operator sequencer and verified RX/TX fixture status. Other slotted modes remain
manual even when native RX/TX exists; the UI does not imply FT8-style automation.

## Nexus feature audit

ShackCQ already has the stronger POTA/WWFF chase, recoverable POTA activation,
offline SOTA catalogue, local journal, and authority-aware QRZ/Club Log/eQSL
delivery paths. They were not replaced. Nexus LoTW upload shells out to desktop
TQSL for certificate signing, which is not an Android implementation; only its
LoTW report download is portable. Nexus Field Day has useful 2026 scoring and
exchange domain logic, but it is a separate event workspace and was not mixed
into Digi without its durable QSO/event model and tests.

## Nexus Digi completion v2

The cockpit owns one real native 384-bin spectrum, a bounded 90-second
waterfall, typed USB/Flex health, exact click-to-net tuning, Classic/Roster
decode views and separate `shackcq-digi.sqlite` storage. FT8/FT4 sequencing
is operator-started, locks one base callsign, ignores bystanders and never
persists arm/PTT/transmitting state. RTTY and BPSK31 remain truthful manual
workflows; QPSK31 is excluded.

SSTV includes exact prepared-image preview, atomic private PNG saves and an
explicit receive-only ISS session. Logging uses `QsoMutationCoordinator` and
the existing Wavelog outbox. WSJT-X UDP is disabled and loopback-only by
default. See `docs/nexus/NEXUS_DIGI_INTEGRATION_V2.md` and the unperformed
physical checklist in `docs/nexus/NEXUS_DIGI_LIVE_ACCEPTANCE.md`.

## FT8/FT4 operating sequence

Start live RX and explicitly enable TX. For CQ, choose `TX FIRST / EVEN` or
`TX SECOND / ODD`, review auto-CQ/retry bounds, then press `CALL CQ`. For
search-and-pounce, select a decode from the current session and press
`CALL SELECTED`; the application derives the opposite TX parity from that
decode's exact millisecond capture slot and locks it for the exchange. FT4
7.5-second half boundaries remain exact in memory, SQLite and WSJT-X UDP.

The UI shows the exact parity and countdown. Any mode, radio identity,
frequency, clock, route, PTT, audio or RX-confirmation failure cancels the
automatic exchange. A completed draft is offered only when the standard
exchange completes with both sent and received reports.

`AUTO SEQUENCE` is explicit. When off, `CALL CQ` and `CALL SELECTED` prepare
one message and the correct parity but never queue a follow-up; the operator
must arm and send it. Auto-CQ is unavailable while automatic sequencing is off.
Reference recordings, companion rows and legacy second-resolution history are
visible for review/manual drafts but cannot start or advance on-air automation.

## DX Chaser integration

DX Chaser is a subpage of the Digi workspace and consumes only exact current-session FT8/FT4 local decodes. Its typed adapter must revalidate foreground state, selected weak-signal mode, live RX, local modem availability, explicit operator TX enable, confirmed RX, session/decode/slot/callsign identity and dial frequency before invoking the existing Digi prepare/select path. The adapter never enables TX, owns no modem/sequencer, and cannot bypass normal Digi safety. Cross-band suggestions route to receive review only; canonical QSO outcomes return through the existing mutation authority.
