# Panadapter and waterfall design

**Status:** Shared DSP plus integrated fail-closed Android implementation; Apple evidence remains separate. Physical Android KX3 quadrature-I/Q RF acceptance is deferred.

## Signal contract

ShackCQ consumes real stereo I/Q PCM. Left is I and right is Q. Mono input must not be duplicated into a misleading symmetric display. Spectrum reversal is an explicit operator setting for interfaces whose I/Q polarity is reversed.

The shared implementation is in core/portable/include/kx3/panadapter_dsp.hpp and core/portable/src/panadapter_dsp.cpp, exposed through core/include/shackcq/core.h. It performs DC removal, windowing, complex FFT/magnitude processing, smoothing, bin copying, and I/Q metrics.

Apple feeds the core from AVAudioSession in ios/ShackCQ/FeatureModel.swift and renders the spectrum/waterfall in ios/ShackCQ/ContentView.swift. Android uses a dedicated native context, `NativePanadapter`, and `PanadapterController`; its verified stereo `UNPROCESSED` capture is explicitly separate from `AudioMonitorController` playback/voice processing.

## Display contract

- Spectrum/waterfall is instrumentation, not generated decoration.
- Centre frequency comes from live CAT; span comes from the physical sample rate.
- Newest waterfall history stays aligned with the current frequency frame.
- Noise floor, black level, dynamic range, palette, and I/Q reversal are explicit.
- No physical input means an offline/empty instrument.
- New or missing Android settings request 48 kHz. Explicit saved 48 kHz and 96 kHz choices round-trip unchanged; both controls remain visible, and Android-reported 96 kHz is not a claim of 96 kHz useful RF width.
- With stale/offline CAT the header shows `RF STALE`, the truth strip shows `CAT OFFLINE · RELATIVE OFFSETS ONLY`, absolute labels stay hidden, and QSY is blocked.

## Evidence

- Shared host build and focused CTest passed during Phase 0.
- Generic iOS build and Android unit/assemble validation passed during Phase 0.
- Historical repository evidence records a physical iPad stereo-I/Q pass at 48 kHz and more than 1.2 million frames. Phase 0 did not repeat this device test.
- Android unit/package and connected-native results are recorded in `panadapter/PANADAPTER_VALIDATION_EVIDENCE.md`; physical Android I/Q claims remain limited to the scenarios actually observed there.

## Residual gates

- Measure axis/calibration accuracy against known RF/audio sources.
- Reconfirm I/Q orientation, image rejection, frequency alignment, and device persistence on each supported interface.
- Separate capture proof, DSP proof, rendered-axis proof, and monitoring/playback proof.
- The retained Android StarTech/KX3 diagnostic showed about 0.10 dB median mirror rejection, about 7.72 dB channel imbalance, and no observed spectrum movement during the recorded VFO diagnostic topology. These are deferred-hardware findings, not accepted quadrature I/Q.

Historical research references and owner-local paths are retained in [PORTING_NOTES.md](PORTING_NOTES.md); they are provenance clues, not imported Phase 0 code.
