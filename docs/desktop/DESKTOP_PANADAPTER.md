# Receive-only desktop Panadapter

`DesktopPanadapter` combines Qt Multimedia input, the shared `shackcq_panadapter_*` C ABI and Qt Quick rendering. It enumerates actual `QAudioDevice` values and stores an exact device identifier. Start requires that exact device, real stereo and the requested 48/96/192 kHz Int16 format. If the route disappears or cannot supply stereo, the UI remains offline; it never selects the built-in microphone or generates display data.

The shared DSP provides 4096-bin FFT/window/smoothing, peak hold, I/Q swap/correction hooks, floor/top metrics, stereo validity, clipping and correlation diagnostics. Runtime history is bounded to the current trace/waterfall frame. The deterministic stereo-tone test exercises the DSP boundary only and is labelled synthetic CI evidence, not live audio.

QMX/QMX+ can be selected when Windows exposes its matching UAC route. Same-device serial/audio correlation is `LIVE_ACCEPTANCE_PENDING`; CAT alone does not fabricate QMX spectrum and no direct-tone TX exists in this Alpha.
