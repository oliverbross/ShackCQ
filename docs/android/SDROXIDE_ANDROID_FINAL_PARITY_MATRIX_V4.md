# SDRoxide Android Final Parity Matrix v4

`IMPLEMENT_V4=0`. Status describes the approved Android product contract, not physical hardware acceptance.

| SDRoxide feature family | Classification | ShackCQ v4 disposition |
|---|---|---|
| TCI protocol/readback | PARITY | Existing shared codec and Android backend; generation-safe readback. |
| TCI reconnect/session lifecycle | SHACKCQ_STRONGER | Stale/duplicate/capability counters; explicit stream reattachment; no unsafe command replay. |
| Multiple receivers | PARITY | Two TCI receivers plus two bounded local virtual receivers. |
| Receive audio routing/mixing | PARITY | Sole two-input `TciRxAudioController`; bounded queues. |
| Panadapter/waterfall | SHACKCQ_STRONGER | Panadapter v6, dual TCI and generic stereo-I/Q surfaces with explicit truth. |
| Waterfall history navigation | PARITY | Bounded time shift, scrub, bookmark and radio-now-elsewhere label. |
| Production I/Q recording | PARITY | Portable float32 interleaved I/Q plus JSON, atomic finalisation and quotas. |
| Offline I/Q replay | PARITY | Seek and 0.25/0.5/1/2×; analysis works offline; audio truthful only at 1×. |
| Marker/measurement tools | PARITY | A/B, deltas, SNR/noise, 3/6/26 dB, occupied BW, channel/adjacent power. |
| Signal tracking | SHACKCQ_STRONGER | Drift/duration plus bounded local-RX follow; physical VFO unchanged. |
| Spectrum survey/history | PARITY | Versioned SQLite aggregates, retention, caps, indexes and Intelligence UI. |
| Scanner banks/journal | SHACKCQ_STRONGER | Existing bounded banks/journal plus historical ordering and conservative dwell. |
| Channel monitors | PARITY | Four lightweight in-span monitors with no invented tone decode. |
| Memories and band stacks | SHACKCQ_STRONGER | Names/groups/tone metadata/priority/history, JSON/CSV, named per-mode stacks. |
| Receive calibration | PARITY | Per-source level, PPM, IQ gain/phase and explicit user-calibrated truth. |
| RX DSP/demodulation | SHACKCQ_STRONGER | Existing USB/LSB/CW/DIGU/DIGL/DSB/AM/SAM/NFM/WFM/SPECTRUM. |
| FM CTCSS/DCS and WFM RDS | PARITY | Existing validated local receiver path; text never inferred by monitor UI. |
| PSK31/RTTY skimmers | PARITY | Existing bounded candidates with candidate/not-confirmed truth. |
| Debug/test signal laboratory | SHACKCQ_STRONGER | Runtime fixtures, survey heatmaps, replay and monitors labelled `DEMO · NO RADIO`. |
| RF map/globe/intelligence | SHACKCQ_STRONGER | Existing bounded observation owners plus Spectrum Survey tab. |
| Logbook/service integration | SHACKCQ_STRONGER | Existing canonical QSO/Wavelog owners untouched by derived SDR storage. |
| Privacy/health diagnostics | SHACKCQ_STRONGER | Sanitized counts/state; recordings, decoded content, notes and paths excluded. |
| Spot exchange over TCI | DEFER_PRODUCT_DECISION | No stable audited dialect contract; no fabricated support. |
| Coherent diversity | DEFER_PRODUCT_DECISION | Requires coherent-source capability proof and an approved DSP owner. |
| Extra digital-mode families | EXCLUDED | Programme deliberately retains existing approved mode set. |
| Winlink | EXCLUDED | Separate messaging/product/authority decision. |
| SoapySDR/OpenHPSDR/new hardware | EXCLUDED | No new SDR hardware backend in v4. |
| WSPR transmit | EXCLUDED | Automatic or scheduled transmit is outside v4. |
| RF Paint | EXCLUDED | No approved product/safety purpose. |
| Neural signal models | EXCLUDED | No model or downloaded payload. |
| Automatic PTT/TUNE/TX | EXCLUDED | Receive-analysis release; transmission remains separately gated. |
| Packaged upstream recordings/assets | EXCLUDED | Runtime synthetic fixtures only. |
| Browser/server mode | PLATFORM_NOT_APPLICABLE | Native Android product; no local web server. |
| MIDI control | PLATFORM_NOT_APPLICABLE | No approved Android operator mapping. |
| Desktop-only window/workspace behavior | PLATFORM_NOT_APPLICABLE | Desktop UI/features unchanged. |
