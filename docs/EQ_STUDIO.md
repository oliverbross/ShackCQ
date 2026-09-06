# Android KX3 EQ Studio

EQ Studio is a local, hardware-first calibration workspace for the Elecraft KX3. It keeps the last exact radio readback, the editable local draft, and named local profiles visibly separate. Sliders never send CAT commands; only **Apply to Radio & Verify** writes a curve.

## Contexts and navigation

- RX stores separate Voice and CW curves; RX EQ is unavailable in DATA modes.
- TX stores normal SSB and wideband ESSB/AM/FM curves; it is not active in CW/DATA.
- Split TX context is resolved from `FT`, the applicable `MD`/`MD$`, and `ES`.
- EQ never changes radio mode. Select the required mode on the KX3 or through the ordinary Radio controls, then read again.
- Expanded tablet navigation includes EQ. Compact navigation keeps the six retained destinations and opens EQ Studio from Radio or Settings → Audio, with Back returning to Radio.
- KX2 keeps offline profiles/preview available but KX3 keypad/write operations remain disabled because the KX2 switch map has not been physically qualified here.

## Signal paths

1. **Raw mic / reference** — microphone or pre-radio source into the selected Android/USB input. Preview applies the draft as an absolute approximate curve.
2. **KX3 output / current radio baseline** — headphones, receive audio, or an operator-controlled TX-monitor loopback into the selected USB input. Preview uses `draft - captureBaseline`, so the recorded hardware EQ is not applied twice.
3. **Second receiver / off-air baseline** — treated like a hardware-baseline clip. ShackCQ never operates PTT, DVR, TUNE, or TX for the recording.

Each transient clip records its source, EQ context, exact verified baseline when applicable, input label, actual sample rate/channel, processing state, and timestamp. Clips remain in memory, clear when the EQ session ends, and are not placed in profiles, backup, sync, cloud, analytics, or external storage.

## CAT protocol and safety

The implementation follows Elecraft K3S/K3/KX3/KX2 Programmer's Reference Rev G5:

- `MN008;` / `MN009;` enter RX/TX EQ; `MN255;` exits in `finally`.
- Bands 1–8 use `SWT19;`, `SWT27;`, `SWT20;`, `SWT28;`, `SWT21;`, `SWT29;`, `SWT32;`, and `SWT33;`.
- `DB;` reads exact VFO-B display text. `DS` is never used as an exact dB source.
- TX uses one `TE` SET with eight signed, fixed-width fields, followed by full menu readback.
- RX reads the selected band, sends one bounded `UP;`/`DN;` per dB, verifies that band, then performs a full readback. It never uses `SWH35;` or flattens the radio first.
- Every operation requires a KX3, `TQ0;`, an idle `MN255;` menu, and a supported live context.
- Apply re-reads the curve inside the same exclusive transaction and stops on a baseline conflict.
- Normal and CW-text polling cannot interleave. Responses are prefix-matched through a bounded quiet window so auto-info frames are routed without treating the next arbitrary frame as the requested response.
- The transport allowlist excludes `TX;`, `SWT16;`, `SWH16;`, TUNE, PTT, DVR, and CLR/reset commands.

## Observed physical KX3 proof — 2026-08-17

Device path: Lenovo `TB373FU` Android tablet, real KXUSB CAT, and physical KX3 MCU firmware `03.02` at 38,400 baud. The radio was in receive throughout the EQ transactions.

Observed `DB` frames use decimal-kHz display text, for example `DB0.05 +0;`, then `DB0.10 +0;` through `DB3.20 +0;`. The initial provisional parser rejected that unfamiliar shape, exited the menu, and preserved the draft. The captured fixture and production parser now use this observed form.

RX CW proof:

- original curve: `[+00,+00,+00,+00,+00,+00,+00,+00]`;
- 50 Hz changed to `+01` through one `UP;` and all eight values read back exactly;
- 50 Hz restored to `+00` through one `DN;` and the complete original curve read back exactly;
- the KX3 required a 600 ms post-menu settle before a fresh `TQ0;`/full readback; shorter windows saw transient auto-info `TQ1;` and correctly failed closed;
- final RX curve: `[+00,+00,+00,+00,+00,+00,+00,+00]`.

TX normal-SSB proof (USB selected explicitly through the ordinary Radio UI, then restored to the original CW mode):

- original curve: `[-16,-12,-08,-03,+00,+03,+05,+02]`;
- one atomic `TE-15-12-08-03+00+03+05+02;` changed the 50 Hz band by +1;
- TX menu/`DB` readback matched all eight intended values;
- `TE-16-12-08-03+00+03+05+02;` restored the exact original curve and menu readback matched;
- final operating mode was restored to CW;
- no transmission-capable command was present and no transmission was initiated.

## Audio capture and preview

- Preferred capture is 48 kHz PCM16 mono; 44.1 kHz is the fallback.
- The controller attempts `UNPROCESSED`, then `VOICE_RECOGNITION`, with the selected USB input preferred. Built-in mic is explicitly labelled reference-only.
- AGC, noise suppression, and echo cancellation are disabled where available and the UI reports OFF, PARTIAL, or UNKNOWN based on observed effect state.
- One central audio coordinator prevents monitor, panadapter, voice record/import/preview, voice TX, and EQ capture/playback from contending. A running monitor requires the explicit **Pause and Use for EQ** action and is restored once by that coordinator; EQ never preempts another non-monitor owner.
- Eight one-octave-style peaking filters (Q 1.15) approximate the documented KX3 centres. This is not an Elecraft DSP emulation.
- Waveform envelope, averaged spectrum, response curve, clipping, peak, speech-active RMS, crest factor, noise floor, usable speech, and band energy all derive from captured PCM.
- A/B uses the same PCM, speech-active RMS matching by default, static headroom reduction when required, short fades, and optional blind labels.

Physical audio proof used `USB Advanced Audio Device` at 48 kHz mono for 15 second raw-reference and KX3-hardware-baseline captures with input processing reported OFF. The baseline-tagged path retained the verified all-zero RX curve and built its preview from `draft - captureBaseline`. The connected source was too quiet for a valid speech suggestion (`peak -37.9 dBFS`, no clipped samples, too little active speech), so no claim of useful acoustic calibration is made. Real-data plots were built and BEFORE/AFTER playback completed through the selected physical output.

## Starting-point assistant and profiles

Suggestions are deterministic local drafts. They normalise speech-band energy, penalise boosts, smooth adjacent bands, refuse aggressive changes for low-confidence audio, cap changes to +4/−6 dB from baseline, and never apply automatically. CW Focus uses the configured pitch but does not replace filter width, shift, or APF.

Profiles are app-private JSON/preferences records containing name, context, eight gains, optional audio-chain label, intent, notes, timestamps, model/firmware, and input label. Built-ins are generic and honestly labelled; microphone-branded universal presets are not included.

## Remaining physical limitations

- The connected USB source did not contain enough speech for a valid acoustic suggestion; repeat with a properly routed mic/KX3 output/second receiver and headphones.
- Split-VFO and ESSB bucket selection are unit-tested but were not physically exercised in this session.
- KX2 writes are intentionally unsupported pending its own official switch-map implementation and physical proof.
- Physical conflict injection, route disappearance during capture, and compact phone/multi-window touch checks remain separate device exercises.
