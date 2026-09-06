# ShackCQ Empirical Outlook v1

## Product truth

This is an empirical RF outlook built from ShackCQ-owned observations. It is not P.533, VOACAP, a neural network, an ionosonde, an exact future callsign prediction, or transmit authority. Output uses `INSUFFICIENT_EVIDENCE`, `QUIET`, `BUILDING`, `FAVOURABLE`, `STRONG`, and `DEGRADED`, plus `LOW`, `MEDIUM`, or `HIGH` confidence. A percentage is withheld until calibration gates pass.

Current callsigns remain current observations. Future candidates are separately attributed as `CURRENTLY OBSERVED`, `SCHEDULED`, `WATCHLIST`, `NEEDED`, or `RECENT PATTERN`; a scheduled candidate is not a predicted transmission.

## Evidence and storage

`neural-dx.sqlite` schema 5 preserves the 90-day `spot` journal and the four outlook tables. Five-minute live buckets remain separated by station profile, station callsign, normalized grid, band, mode family, 6 × 12 region and provider identity. Historical cluster backfill is instead held once under the shared `global|cluster-history|v1` key so switching station profiles does not duplicate a globally sourced journal. Buckets retain counts, capped exact callsign/receiver hashes, SNR/distance aggregates, and source freshness—not raw responses, credentials, comments, QSO payloads, or arbitrary JSON.

Evidence and pending predictions retain at most 180 days. Verified outcomes retain 14 days, durable calibration aggregates remain model-version scoped, prediction rows have a 100,000 hard cap, and the database soft target is 50 MB. Only supported global forecasts with actual contributing observations are persisted; insufficient and 72-cell regional display forecasts are never calibration candidates. IDs use 15-minute target slots per station/window/band. Schema 5 adds `receiver_keys`, quarantines old pending rows as `UNVERIFIABLE`, and moves historical rows to the shared key. Backfill processes one 1,000-row transaction before first publication, then one batch every five seconds while foregrounded; exact capped call/receiver unions and progress commit together. Live aggregation uses replace-by-bucket semantics.

## Time-matched baseline and model

The baseline uses the target UTC quarter-hour with ±30-minute tolerance over a bounded 56-day history. It requires at least eight matched buckets and remains station-, band- and region-scoped. Evidence retains major mode-family keys; the global operator outlook deliberately aggregates those families where a mode-specific sample would be too sparse. Current World anomalies and future windows query `evidence_bucket`; neither repeatedly scans raw spot rows nor compares a target time with an all-day mean.

The versioned score is bounded 0–100. Coefficients live in `ShackCQEmpiricalOutlookV1`: baseline propensity 20, current/matched-baseline support 25, trend 15, source/call/receiver diversity 20, distance/path diversity 10, environmental context −10…+10, and explicit freshness/degradation penalties. QSO aggregates are personal context only and never contribute live support. Calendar, watchlist and Needs rank value only after the RF outlook.

All 16 canonical bands are retained. 4 m/2 m/70 cm require current and historical terrestrial evidence; 23 cm/3 cm require stronger multi-source local support and cannot be inferred from solar context alone. Output windows are 30, 60 and 120 minutes. World/map output is capped at 72 cells.

## Verification and calibration

Ended global predictions verify after ingestion in batches of at most 100. Exact hashes are unioned across all buckets for each contributing source family before applying the rule: `HIT` requires two unique calls in one contributing source or one call in two contributing sources. `MISS` requires a current/cached heartbeat from a contributing source; an unrelated provider cannot validate absence. Otherwise the outcome is `UNVERIFIABLE`. Insufficient forecasts are absent from persistence, verification and calibration.

Scores calibrate by decile, window and band family. A displayed hit rate requires at least 40 verified predictions for that window/band family and 15 in the score bin. Beforeward the UI says `Calibration collecting · N verified`; afterward it says `Empirical hit rate · XX% · N samples`. Laplace smoothing is applied. Model versions never mix bins.

## Lifecycle and safety

One recoverable `SupervisorJob` controller coalesces immutable snapshots off-main: latest snapshot wins, writes do not overlap, ingestion is limited to once per minute, and a local five-minute heartbeat writes source state and recomputes without fetching providers. Station/window/source changes or manual refresh may recompute immediately. Failures retain the latest input, expose a sanitized retry state, and retry after five seconds; a failed child cannot permanently kill later work. A background transition requests one bounded flush; there is no WorkManager or permanent service. Close is idempotent, cancels pending work, and stale generations cannot overwrite a newer snapshot.

No outlook object contains a CAT frequency or command. The established direct Neural CAT guard above 54 MHz, receive-review flow, and prohibitions on automatic PTT/TUNE/frequency change/macro/spot/log actions remain unchanged.
