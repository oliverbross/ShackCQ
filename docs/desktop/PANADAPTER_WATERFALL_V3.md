# Panadapter and Waterfall v3

## Signal path

The existing shared shackcq_panadapter_context remains the DSP authority. Its compatible PCM APIs are preserved and shackcq_panadapter_push_float_iq adds direct float32 I/Q. Swap/conjugate, correction, clipping, non-finite, duplicate, DC, FFT, averaging, peak-hold/decay, and FIT diagnostics share the same core.

Desktop I/Q is routed by stable receiver ID to at most nine contexts: eight TCI receivers plus one exact local stereo route. Each float frame is capped at 2,000,000 values. FFT work executes on one worker thread behind an eight-frame queue; overload increments per-source dropped-frame diagnostics. Shutdown invalidates queued publication, waits for the bounded worker, and cannot publish into a destroyed owner.

## Display

PanadapterSceneItem uses public Qt Quick scene-graph geometry and textures. It consumes C++ render frames directly; operational rendering does not transfer a frame-sized QVariantList through QML. Display publication is bounded to roughly 30 Hz.

Waterfall history is a true scrolling QImage with 64–512 rows per context and 1024/2048/4096/8192 columns. Memory per context is exactly width × rows × four bytes plus bounded trace/peak vectors and DSP state. Pause freezes display history while capture truth continues. Discontinuities produce an explicit marker row.

Controls include spectrum/waterfall split, zoom and pan, cursor frequency, explicit marker QSY, VFO markers, band-plan strip, average frames, peak hold/decay/reset, FIT robust auto-contrast, manual floor/top, and four independently defined palettes. Passband is view-only until filter capability and readback are proven. With two contexts, a secondary live scene makes concurrent receiver evidence visible without creating another DSP or radio owner.

## FIT

FIT uses robust bounded percentiles, a minimum 30 dB display span, abrupt source/band reset behavior, and eased drift. Silence, noise, carriers, a single-bin spike, non-finite values, 48/96/192 kHz mapping, and abrupt/gradual changes are covered by deterministic tests.
