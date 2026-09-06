# SDRoxide TCI TX Audit v5

Audited upstream is immutable SDRoxide v1.5.3, commit `a680935b10f33768a499435e8bd37f779fa640ae`, GPL-3.0. The release was rechecked on 2026-08-27. The official ExpertSDR3 TCI reference was audited at repository commit `b081213ff97150fd29f669c633f060f93c81a286`.

SDRoxide confirms a receiver-owned transmitter, 48 kHz raw TX audio, bounded TX ring, chrono pacing with finite fallback, `trx:<receiver>,true,tci`, sensor enablement, de-key on detach/disconnect/shutdown, and RX queue discard after TX. ShackCQ does not copy SDRoxide transport ownership: it keeps its existing radio platform/backend and adds one acceptance-aware authority above the adapter.

ShackCQ is deliberately stricter than the audited client: PTT/Tune do not advance without trustworthy readback; no TX request is replayed after reconnect; post-stop ambiguity becomes `RX_UNCONFIRMED`; identity, foreground, route, mode, frequency, SWR, ALC when available, connection, readback, underrun, and Global Stop are monitored; production acceptance is immutable outside an evidence-bearing acceptance step.

Differences are intentional: the baseline official TCI document defines forward RMS power, peak power, and SWR, not reflected power or ALC. Those values remain unavailable rather than being inferred. Native keyer commands are excluded from the baseline adapter; CW uses the reviewed bounded audio-keyed path. Per-mode levels are ShackCQ-owned and applied once.
