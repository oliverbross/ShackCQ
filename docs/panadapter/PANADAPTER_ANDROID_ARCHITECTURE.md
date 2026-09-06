# Android KX3 panadapter architecture

## Scope and boundaries

This is the production Android receive-I/Q path for Elecraft KX3. It is independent of the mono audible monitor and the transmit-capable voice-macro path. It never creates playback, requests audio focus, opens CAT or network transports, or fabricates spectrum data. KX2 remains visible as an unsupported boundary because its wideband-I/Q readiness is not proven here.

## Data and ownership

```text
selected external USB input
  -> one AudioRecord (UNPROCESSED, stereo PCM16, 96 kHz or explicit 48 kHz fallback)
  -> PanadapterController capture thread
  -> one dedicated shackcq_panadapter_context through batched primitive-array JNI
  -> streaming complex DSP
  -> three preallocated snapshot buffers
  -> <=30 Hz coalesced Compose state
  -> Spectrum Canvas + circular Bitmap waterfall

existing CAT state -> effective RX/TX, split, RIT/XIT, mode and bandwidth
existing CAT sender <- explicit marker QSY only
DX snapshots       -> bounded read-only overlays
```

The controller owns the `AudioRecord`, native handle, capture/replay threads, route callback, waterfall bitmap, bounded recorder and calibration state. `close()` stops those resources and destroys the native handle. The shared audio arbiter pauses the audible monitor before panadapter acquisition and releases ownership on stop or failure.

## Route proof and failure behavior

The user selects a concrete external input. The always-visible **48K** and **96K** controls select the requested capture rate. After `AudioRecord.startRecording()`, ShackCQ reads the active `AudioRecordingConfiguration` and compares the typed client format, typed device format, selected route and active route. A client-side 96 kHz request is accepted as `TRUE_96K_STEREO` only when the device side is also 96 kHz stereo with matching encoding. A 96-client/48-device path is stopped and may reopen only as a separately proven true 48 kHz path. Mono, conversion, a rejected preferred route, route change, detach, repeated read failure, CAT disconnect, wrong radio model, or transmit state fails closed. Proof is cleared on stop so persistence never restores a stale green state. A physical detach invalidates enabled I/Q, level and measured-flatness profiles before a matching device may recover automatically.

## DSP contract

Left/right PCM enters as I/Q after explicit swap, polarity, conjugation and trim controls. Streaming IIR DC removal precedes an optional stable widely-linear correction `y = a*z + b*conj(z)`. Live frames never estimate or silently change that transform.

The DSP supports 1,024/2,048/4,096/8,192-point radix-2 FFTs; 25/50/75 percent overlap; Blackman-Harris, Hann, Nuttall, rectangular and flat-top windows; coherent-gain power normalization; ENBW/RBW reporting; independent linear-power averaging, attack/release trace smoothing and peak hold/decay. Each frame carries a stable mask for physically useful bins, transition/edge bins and the centre/DC guard. Floor estimation uses a lower percentile of valid bins only, with separate raw and slowly stabilized values. Spectrum and waterfall auto ranges evolve independently; the waterfall publishes black/top, below-black/useful/saturated fractions, valid fraction, peak-to-floor contrast and in-band/out-of-band power. The generic Elecraft approximation is disabled by default and never applies outside valid bins. Measured correction is device/rate/radio bound and clamped to a safe display-only range.

High-resolution zoom is actual translate, 63-tap low-pass FIR and decimation by 2/4/8 before analysis. Sequence, input-frame, transform and discontinuity counters remain monotonic. A coherent native snapshot copies metadata, metrics, trace, waterfall powers and peak values from the same transform. The UI masks center bins only when explicitly requested; raw DSP bins remain available.

## Real-time and rendering policy

The capture thread uses preallocated short and native output arrays. FFT tables, windows, FIR state and work buffers are allocated at configuration time. UI publication is capped near 30 Hz, while all captured frames continue through DSP. Display analysis and palette preparation run off the Compose thread. Spectrum and waterfall pixels use exact non-overlapping source-bin intervals, with mean linear power blended with a high-percentile value so one narrow signal is preserved without being duplicated into neighbouring pixels. The waterfall uses a fixed-size circular pixel buffer rather than shifting history. Invalid regions are visibly dimmed instead of being relabelled as useful bandwidth.

CAT center is accepted only from fresh connected KX3 state. Effective RX/TX incorporates VFO role and documented RIT/XIT offsets. Frequency gestures change only the local view; QSY requires an explicit marker action, rounds to the configured step, and enables Undo only after the requested frequency is observed in subsequent CAT state.

## Calibration and persistence

Settings use a versioned, validated private preference record and participate in the existing app backup/recovery scope. Version 1 display defaults migrate once to automatic scaling with generic flatness disabled. A calibration requires verified independent stereo, stable unclipped known tones at opposite nonzero offsets, credible signal-to-floor margin, at least 35 dB corrected image rejection and at least 6 dB improvement. The workflow reports requested/measured offset, axis error, desired/image levels, gain imbalance, phase error and DC-spur relation. Coefficients are previewed and saved only after both sides agree; the profile is enabled only for the exact selected device fingerprint and physical rate.

The route, physical format, I/Q proof, calibration validity and display fitness are independent typed states. Healthy non-duplicate stereo is not called verified I/Q orientation. A stable comb detector reports spacing and persistence but does not notch or conceal peaks. The bounded spur workflow retains comparable A (terminated interface), B (KX3 with controlled antenna state) and C (normal station) metrics for operator-led source classification.

## Privacy and support evidence

Raw I/Q recording is an explicit, bounded (maximum 60 second) private WAV plus metadata action. Replay feeds the production native path. Support export is a bounded private JSON snapshot containing route and DSP facts, Android/device model and CAT state; it contains no raw I/Q, credentials, database, logbook or network content.
