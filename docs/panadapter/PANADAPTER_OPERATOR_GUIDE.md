# Android KX3 panadapter operator guide

## Before starting

Connect and identify the KX3 through the normal ShackCQ CAT path. Connect the KX3 RX I/Q output to a class-compliant external stereo USB ADC through a topology the tablet can actually enumerate. Select that exact input. Use the always-visible **48K** or **96K** control to change capture rate; the live stream is reopened and re-proved. Do not select a mono route.

For the StarTech ICUSBAUDIO2D, the three hardware LEDs document the **playback** sample rate, not microphone-capture truth. The KX3 I/Q lead belongs in its stereo microphone input. Trust the live ShackCQ client/device format fields and Android audio diagnostics for capture rate. In the tested Lenovo/StarTech path, direct 48 kHz used nearly the entire nominal span; direct Android-reported 96 kHz exposed only about 48 kHz of useful central response. Use 48 kHz for honest routine viewing until the 96 kHz analogue/input response is independently explained and calibrated.

Open **Radio → Panadapter** on a compact device or **Panadapter** in the expanded navigation rail. Grant microphone permission only if you intend to use physical receive I/Q; Android applies that permission to external audio capture too. Press **Start**. Treat the instrument as unavailable until the header reports live operation and Diagnostics shows the requested route equals the actual route, stereo channels, and 48 or 96 kHz.

There is no panadapter audio playback. The audible monitor pauses while the panadapter owns the input. Transmit freezes the receive display and start/QSY actions fail closed while transmitting.

## Reading and operating the display

- The green center cursor follows fresh effective CAT receive frequency, including VFO/RIT state. Red shows split transmit frequency.
- Tap to place marker A/B; drag the active marker to refine it. Pinch and pan alter only the view.
- **QSY A/B** is the only tuning gesture. **Undo** appears only after CAT confirms the requested tune.
- The layout button cycles split, spectrum-only and waterfall-only views. Drag the divider in split mode. Fullscreen hides system bars until a swipe or the fullscreen action restores them.
- The truth strip separately reports route, format, usable bandwidth, I/Q state, display state, and persistent comb-spur warnings. `TRUE 96 kHz` describes the Android device interface; `USABLE 47.7 kHz`, for example, honestly narrows the response that currently contributes to display statistics.
- Settings control FFT/window/overlap, independent spectrum power averaging, manual or robust attack/release auto-level, floor line, peak hold/reset, optional Elecraft generic flatness, I/Q orientation, genuine decimation zoom, visual-only DC masking and the independent waterfall power averaging/palette/range/gamma/line rate.

The display is dBFS unless a separately measured level calibration is available; this implementation does not claim dBm or S-unit accuracy. The generic Elecraft curve is a display correction, not a measurement of your particular radio/interface. “Raw” center-mask mode is the diagnostic truth.

## I/Q calibration

Use one known, stable, unmodulated receive tone clearly off center. Put marker A on its known offset, verify healthy non-clipping stereo metrics, then press the calibration action. Confirm the first preview, retune the same known tone to the opposite offset, and repeat. The profile is saved only when both offsets are stable, agree in coefficient, improve rejection by at least 6 dB, and reach at least 35 dB corrected image rejection. Until then the UI remains `ORIENTATION UNVERIFIED` or `VERIFIED UNCALIBRATED`. Saved correction is bound to the selected device fingerprint and physical sample rate; detach/reconnect or changing either invalidates it.

This is image-balance calibration, not RF level or multi-point analogue-flatness calibration. Use a dummy load/shielded source where appropriate and never key the transmitter for this receive-only workflow.

## Recording, replay and diagnostics

The record action captures a finite private stereo WAV (10 seconds from the main action, never more than 60) and companion metadata. Stop early with the same action. Replay is available only when live capture is stopped and uses the production DSP path. Files remain in app-private storage unless you deliberately export through a later platform workflow.

Diagnostics exposes requested/actual route, formats, frame/transform/drop counters, FFT/hop/RBW, I/Q RMS/correlation/duplication, clipping, peak/floor, CAT and calibration state. **Export support snapshot** writes a redacted JSON file to private storage; share it only by an explicit operator action.

For periodic lines, open Diagnostics and follow **SPUR DIAGNOSTIC** exactly: capture A with the USB input safely terminated and KX3 disconnected; capture B with KX3 I/Q connected and the antenna path in an operator-approved controlled state; capture C during normal receive. Do not press a capture label unless the physical state matches it. Compare comb spacing/persistence across all three; ShackCQ deliberately does not auto-notch the result.

## Recovery

- **No external USB input:** reconnect the ADC/hub, reopen setup and select it explicitly.
- **Route mismatch or detached:** stop, verify the physical topology, then restart. Matching hotplug recovery is attempted only for a previously wanted live session.
- **Invalid stereo/duplicate channels:** correct the cable/interface or orientation; do not interpret the mirrored display as RF truth.
- **CAT stale/disconnected/wrong model:** restore the normal KX3 CAT connection. The panadapter does not start a second CAT poller.
- **Clipping:** reduce analogue interface level. **Weak signal:** raise the safe receive-I/Q/interface level; software gain is not proof of usable capture.
- **Reversed spectrum:** use Swap/Conjugate/Invert deliberately, verify with a known off-center signal, then calibrate again.
- **CAT offline with ADC still live:** use the trace only as an input diagnostic. The header shows `RF STALE`, the truth strip shows `CAT OFFLINE · RELATIVE OFFSETS ONLY`, absolute-frequency labels disappear, and QSY remains blocked.
- **Dominant mirror images:** `MIRROR IMAGES DOMINANT` means strong positive/negative-offset peaks have less than 3 dB median separation. This is not usable quadrature I/Q; check the KX3 RX I/Q output, stereo cable/contact, ADC input mode, grounding and channel gain before attempting calibration.
