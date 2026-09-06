# Android Time-Shift and Bookmarks

Receive time-shift stores reduced display traces only, at a bounded cadence, for OFF/30/60/120 seconds. Pause, scrub, replay, return-live, bookmark, and clear are explicit actions. Receiver/source/radio changes invalidate incompatible buffered state.

Bookmarks live in the separate schema-1 `shackcq-sdr-operational-v2-derived.db` store, capped at 256 rows. They contain frequency/source metadata and a reduced trace; no raw IQ, raw audio, credentials, or QSO records are stored.
