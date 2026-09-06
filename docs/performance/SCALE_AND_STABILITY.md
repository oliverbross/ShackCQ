# Scale and stability

## Multiplatform integration

The candidate retains Android 100k-QSO projection and native lifecycle stress gates, and Windows 100k-QSO keyset paging, bounded spot retention and repeated service-close coverage. Wavelog/configuration fixtures remain small and deterministic; no generated scale database is committed.

## Tablet Acceptance Sweep 2

Band Maps retains the bounded input/index and six-lane placement model. Contest session reads are indexed and capped at 10,000 rows; merges are serial, idempotent and restart-safe. SCP suggestions are bounded to 30 and the database to 16 MiB. IOTA is bounded to 8 MiB/5,000 groups; user CSV imports to 32 MiB/250,000 rows; visible catalogue results to 500. Intelligence maps consume bounded projections and retain user camera state. Groups.io foreground refresh bounds groups, topics and messages.

## Tablet Acceptance Sweep 1

Log Intelligence work stays on bounded projection queries with latest-generation cancellation and an explicit local/Wavelog authority identity. Contest score rebuilds run off the interface thread and apply only the latest active-session generation. Band Map placement is linear over a bounded input with six lanes and exact chooser membership capped at 20. Activation Planner catalog work runs off-main over at most 5,000 source rows and returns at most 1,000 deduplicated inclusive-radius results. The established disposable 100k-logbook, 180-day Neural, 20k-Digi, 30k-Groups and provider-lifecycle soak passes.

## Delivered architecture

The old interactive paths repeatedly decoded the canonical `qso.details_json` record, materialised the complete log, sorted it in memory, and recalculated analytics or calendar history for each consumer. That made memory and latency grow with the log and made Android lifecycle churn a plausible crash trigger.

Database version 12 keeps `qso` as the compatibility authority and adds `qso_projection`, `qso_reference`, and `qso_projection_meta`. The projection contains normalized query dimensions, confirmation flags, Wavelog relation state, station/operator identity, portable references, satellite fields, and a deterministic `(created_at, qso_id)` cursor. Targeted indexes cover time, callsign, frequency, band/mode, station, geography, contest, satellite, activation, sync relation, confirmation, and references.

Projection creation is resumable and batched. Progress and state are committed in metadata, malformed canonical rows are skipped without deleting the source, and normal writes dual-write canonical and projection rows in one transaction. Verification detects missing and orphan rows; repair fills missing rows and removes orphans; rebuild is an explicit recovery action. WAL, `synchronous=NORMAL`, a bounded busy timeout, and the private diagnostic journal reduce lock and crash ambiguity.

Logbook queries now run off-main, debounce changes, cancel the previous coroutine and SQLite `CancellationSignal`, use keyset paging, and cap UI pages at 250. Log Intelligence, Operations, Home, DX/contest history, spot status, and station summaries query compact projection aggregates. Normal interactive paths no longer load a complete `List<Qso>`. Legacy JSON helpers remain only for compatibility/migration or explicit bounded canonical retrieval, not normal filtering or sorting.

ADIF import reads records incrementally, applies bounded mutation batches, reports progress, and permits cancellation between batches. Export streams projection-selected canonical rows to the destination instead of building one giant string. The small string helpers remain for tiny inputs and focused tests.

## Representative host profile

Measured 2026-08-20 with SQLite on a deterministic temporary 100,000-row projection. The database was not committed. Times are wall-clock observations, not tablet guarantees.

| Query category | Elapsed | Peak process RSS | Selected plan/index |
|---|---:|---:|---|
| Default first page, 250 rows | 0.00 s | 3.1 MB | `qso_projection_time_idx` |
| Exact callsign | 0.01 s | 3.2 MB | `qso_projection_call_idx` |
| DXCC | 0.00 s | 3.1 MB | DXCC/time index family |
| Band and mode | 0.01 s | 3.2 MB | band/mode index family |
| Confirmation | 0.00 s | 3.2 MB | confirmation/time index |
| Contest | 0.01 s | 3.4 MB | contest/time index |
| Satellite name and mode | 0.00 s | 3.7 MB | `qso_projection_satellite_idx` |
| Wavelog relation | 0.01 s | 4.3 MB | `qso_projection_sync_idx`; bounded temporary ordering |
| Keyset next page | 0.00 s | 3.1 MB | time cursor index |
| Overview aggregates | 0.05 s | 6.0 MB | bounded aggregate scan |
| Awards aggregates | 0.07 s | 5.5 MB | bounded grouped aggregate |
| Needs compact sets | 0.00 s | 3.1 MB | compact distinct set |
| DX Calendar local history | 0.01 s | 5.2 MB | contest index/aggregate |
| Filtered satellite export stream | 0.01 s | 5.9 MB | satellite index |

Several subsequent keyset pages were also exercised. All measured categories remained below the one-second host guidance and no crash or unbounded memory growth occurred.

## Private diagnostics

The journal stores at most three sanitized crash summaries and twelve slow-query records in app-private storage. It excludes QSO payloads, credentials, URLs, tokens, callsigns, comments, and provider response bodies. Settings can show, copy a sanitized report, or clear the journal. Diagnostic failure never blocks database recovery or logging.

## Task 2A Home and map safeguards

Home no longer owns a screen-wide one-second clock state; the ticking state is isolated inside the header, while celestial/map snapshots advance once per minute. The MapLibre view is remembered across ordinary recomposition and style changes, forwards lifecycle events exactly once, and updates stable GeoJSON sources rather than reconstructing the map.

Every map layer declares a maximum object count. DX points cap at 160, great-circle paths at 80, PSK reports at 60, portable points at 160, satellites at 40, recent QSO projection rows at 120, and lightning at 120. Geometry splits at the dateline. Recent QSOs select eight compact indexed projection columns and never call `QsoDatabase.all()` or decode canonical JSON. Camera persistence is debounced by 600 ms, manual pan disables follow, and low-data mode avoids MapLibre/tile work while retaining the same visibility and selection actions.
# Task 2A1 Android Home closure (2026-08-20)

- Overlay caps are registry-owned; the visible grid produces exactly its declared 32 lines.
- Map source updates are fingerprinted per stable GeoJSON source so unchanged snapshots are not rewritten.
- One MapLibre view is lifecycle-forwarded; style callbacks are generation-guarded and disposal invalidates late callbacks.
- Low-data and style failure use the bounded Map Data snapshot; low-data makes no style/tile request.
- Home satellite positions reuse Satellite Operations' validated element cache and pinned SGP4 engine, with a bounded 40-object snapshot calculated off the main thread.
- QSO projection remains capped at 120 compact rows; no canonical log JSON is materialized.
- Camera persistence is gesture-only, delayed 600 ms, merges latest non-camera settings and rejects stale writes after profile/new-camera changes.

# Task 2A2 runtime safeguards

- Home satellite propagation is one foreground-only controller job at 45-second cadence. A mutex prevents overlap across lifecycle/reselection restarts; generation checks discard obsolete completion; output is favourites plus selected with a bounded fallback and a hard 40-row cap.
- Home refresh uses `NeuralDxRefreshScope.HOME`, which excludes the legacy Neural DX satellite download and ticker. The remaining legacy path is limited to the dedicated full-DX workspace refresh.
- Map source truth covers every registry layer, while header totals count only visible layers across current, degraded, empty and unavailable categories.
- DX/PSK/Portable/Satellite/QSO routing carries typed exact identities. No marker selection performs CAT; Home DX review confirmation alone may change receive VFO.
- DX points and paths retain the shared band palette; watchlist is a separate GeoJSON/stroke property. Existing per-source fingerprints still suppress unchanged full-source rewrites.
- Recent Home QSOs remain the bounded `recentHamClockProjection(120)` path. No canonical QSO JSON decode or `QsoDatabase.all()` was introduced.

# Task 2B1 DX News and PSK safeguards

- DX-World downloads cap at 1 MB, accept at most three HTTPS redirects and publish at most 40 normalized stories. A 30-minute TTL, 10-minute manual limit, conditional validators, last-good cache and in-flight coalescer prevent request storms and invalid replacement.
- DX News consumes the existing bounded NG3K feed, so Home, Calendar and DX share one schedule fetch. Dedup is linear over the bounded merged list and preserves distinct same-callsign stories.
- Direct PSK responses cap at 2 MB and 500 rows per direction. Cache keys include direction/callsign/window; automatic/manual cadence cannot be below five minutes and Retry-After extends backoff.
- PSK filters are applied before Home/map presentation, whose existing map cap remains 60. Direction generations discard late results after disable or provider-affecting changes; clearing PSK cancels the active job and removes displayed rows.
- Cluster map presentation applies the active window/cap/band/mode/continent/callsign filters to typed controller state. Connected with zero current spots is `EMPTY`, not a transport error.

# Task 2B2B RF evidence safeguards

- RBN retains at most 1,000 raw rows and publishes at most the saved cap. A two-second maintenance cadence filters/sorts off-main, prevents overlap, expires quiet-feed rows and publishes one immutable typed snapshot on Main.
- RBN geometry performs no per-frame or unbounded callbook work: stream/station/cached-callbook geometry precedes approximate CTY fallback, and unresolved skimmers remain list-only.
- Station-grid changes reproject retained PSK and personal-WSPR geometry locally with zero HTTP requests. Direction truth has an explicit combined `DEGRADED` state.
- Band Health is computed once into an application-scoped immutable snapshot. Exact contributing IDs are capped, and historical comparison is a compact grouped `qso_projection` query bounded to 128 rows and one year, keyed by station/filter/database revision.
- Home provider loops and Neural DX/lightning starts are foreground-guarded. No RF-evidence action bypasses receive-only review or starts CAT/PTT/TUNE/TX.

# HamClock finish-line safeguards

- The native propagation adapter accepts at most 64 frequencies and crosses JNI only as bounded structured JSON. It retains no report text. The unavailable licensed engine performs validation only.
- The reviewed ITU candidate is 564,856,257 bytes installed and 383,264,044 bytes gzip-compressed, so it is neither bundled nor downloaded. The debug APK ceiling remains 180 MB.
- NOAA weather inputs cap at 1.5-3 MB; OVATION caps at 5 MB, 720 parsed cells and 180 rendered fills. Solar images cap at 4 MB, validate MIME/dimensions and downsample to 2,048 pixels.
- Satellite map work stays in one existing controller job. Output is at most 40 positions, four 28-sample-or-smaller ground-track segments, and four 49-point footprints.
- Contest-QSO map/list work uses the indexed projection and a hard 200-row cap. No canonical JSON or full log is loaded.
- Shack rotation does not refresh a provider, change radio state, or remove the persistent exit control. Lifecycle stop/dispose restores system bars and clears the keep-screen-on flag.
## Neural empirical outlook bounds

Schema 5 uses five-minute `evidence_bucket` rows with targeted UTC-matched indexes. Live evidence is station-scoped; cluster journal history is stored once under a shared global key. Exact call/receiver hash sets are each capped at 24. Live snapshots are deduplicated and coalesced at no more than one write per minute; a local five-minute heartbeat recomputes without a provider fetch. Backfill is off-main, restart-safe, one 1,000-row transaction before first publication and then at most one batch every five seconds. Verification is capped at 100 predictions, World at 72 cells, candidates at 12 and live input at 2,000 observations.

Only eligible global forecasts persist on 15-minute station/window/band slots. Verified rows retain 14 days, pending rows and evidence retain 180 days, calibration aggregates remain durable, and prediction storage has a 100,000-row hard cap that removes verified rows before ended pending rows and never removes unended pending rows. Startup never vacuums. A deterministic disposable 30-active-day profile across all 16 bands and 30/60/120-minute windows produced 138,240 evidence rows, peaked at 69,344 prediction rows, finished with 64,736 predictions (224 pending), 60 calibration rows and a 43,061,248-byte SQLite file. Median 48-forecast recomputation was 469.96 ms and the 95th-percentile bounded verification cycle was 0.24 ms on the host. The generated database was deleted; timings are not tablet guarantees.

## Nexus Digi v2 bounds

- Spectrum publication is at most 10 Hz with 384 bins. The 900-row waterfall
  holds about 90 seconds and 1.32 MiB of float payload, below 4 MiB.
- Live UI history caps at 3,000 rows. `shackcq-digi.sqlite` defaults to seven
  days/20,000 decodes; completed sessions and drafts prune after 90 days.
- PSK31 retains at most 120 seconds of 12 kHz mono. Slotted decoding keeps one
  bounded slot plus one replay slot.
- Operator-started PCM16 WAV capture caps at 10 minutes, seven days and 100 MB.
- SSTV PNGs use temporary-file/rename completion and a 100 MB default/250 MB
  maximum quota; SQLite stores metadata, never pixels.
- Background, route loss, radio identity/frequency change and close clear TX.
  No lifecycle path restarts transmit.

## Sweep 2 radio and rotator bounds

- Radio selection is O(1) by stable profile ID; Hamlib search filters the compiled registry and renders at most 24 matches at once.
- One radio backend and one rotator backend may be active; a shared physical-identity authority prevents duplicate ownership.
- Android serial reads are capped at 64 KiB for radio bridges and 4 KiB for rotator protocol responses. Poll cadences are clamped by profile contracts.
- QMX command draining is bounded per pass; RGO polling uses one scheduler and per-cadence overlap guards; Hamlib uses one coalescing queue and one polling job.
- Rotator diagnostics retain at most 200 events, profiles at most 32, assignments at most 256, presets at most 20 and forbidden sectors at most 16.
- Background and context change disarm rotator automation. Neither connection nor physical motion is automatically restored.

## Android lifecycle hardening bounds

- Checked native calls hold one small owner monitor only for the native call; close waits for an in-flight call and cannot expose the retired pointer.
- Feature stress performs 1,000 create/close cycles; Digi, Flex, Hamlib and satellite contracts perform 500 cycles; map callback simulation performs 100 dispose cycles.
- JNI byte/float arrays are capped at 4 Mi elements, encoded sample output at 16 Mi elements, Flex discovery at 64 KiB and SSTV dimensions at 2,048 by 2,048.
- Digi receive cleanup is asynchronous and capped at 2.5 seconds; Hamlib transport join/flush/disconnect uses bounded timeouts; lifecycle close no longer calls `runBlocking` for USB disconnect.
- ASan and UBSan report no finding across all three native CTest targets. The protected tablet's 30-minute locked-state process soak remained one PID with 40 threads, 179-180 FDs and bounded PSS/native heap; visible unlocked foreground/navigation acceptance remains blocked by the secure keyguard.

## Windows desktop parity scale probe

The desktop integration suite creates 100,000 canonical QSOs and verifies keyset paging through the existing data contract. The new feature probe inserts 2,880 Neural rows over 180 days, 20,000 Digi rows, 30,000 Groups.io rows with FTS5 indexing, 10,000 Contest staging rows and 20,000 DX Chaser rows. The scale test completes inside the bounded CTest target and all stores close cleanly. This is deterministic storage/lookup evidence, not a multi-hour physical Windows soak.

Functional closure also bounds Groups.io pages to 1,000 rows/2 MiB, provider bodies by policy, Digi audio to 16 million float samples and native protocol buffers by controller limits. Route loss/global Stop cancel active work. Hosted scale/soak remains exact-SHA evidence, distinct from physical multi-hour operation.

## Multiplatform RC1 scale and soak

RC1 retains the 100,000-QSO keyset/export profile, 180-day Neural compaction, 30,000-message Groups.io FTS/recovery profile, provider malformed/oversized lifecycle, native lifecycle stress and deterministic TCI replay. Normal, ASan and UBSan native runs are separate gates. The final exact-SHA reports record elapsed time, row/frame counts, drops, bounds and exit status. A timed wrapper, silent process or interrupted runner never counts as a pass; physical multi-hour radio/audio/device acceptance remains separate.

The RC1 extended Rust quality run completed the full `tempo-sstv` catalogue with zero failures: 160 unit tests; five dropout/channel tests in 1,502.29 seconds; four legibility guards in 8,776.58 seconds; back-to-back image recovery in 1,041.63 seconds; four Nexus acceptance cases in 540.52 seconds; 15 image round trips in 2,175.16 seconds; 15 transmit loopbacks in 2,028.85 seconds; plus no-VIS, unknown-VIS and doc tests. Because this suite is intentionally multi-hour, hosted RC gating uses the bounded 160-test library suite and records the complete catalogue as separate extended evidence.

## Android SDR enhancement scale contracts

TCI binary input is capped at 8 MiB and decode backlog at eight frames. Panadapter publication is capped at 30 fps with two receiver contexts and 180 waterfall rows of at most 1,024 samples. Scanner ranges and FFT candidates cap at 10,000. RF observations cap at 100,000 and Canvas rendering at the newest 4,096 paths per frame. RX audio backlog is eight frames. Health exposes drop/clipping/filter-time counters without raw payload retention.

## Android SDRoxide operational v2 bounds

TCI safe setters coalesce to the latest generation; two audio queues are capped at eight frames each; time-shift stores at most 120 seconds of 512-bin reduced traces at bounded cadence; skimmers serialize off-main work and retain at most four candidates per mode; scan banks cap at 64 with 2,000 memories each; the journal caps at 50,000 rows; bookmarks cap at 256 and record-on-hit enforces duration/daily/total policy. Overload drops or refuses work and publishes counters/status rather than allocating without bound.

## Android Local SDR Receiver v3 bounds

One local-I/Q queue holds eight blocks and one worker services at most two checked native handles. JNI input caps at four million floats. Output is fixed at 48 kHz and reuses the existing two eight-frame audio queues and one AudioTrack. Time-shift audio is retained only by the existing owner for its selected 0/30/60/120-second window. Recording is one file, 30 minutes and 250 MB maximum total. Health exposes rate, NCO, filter, mode, DSP time, queue/drop, SAM/tone/RDS and recording counters without raw data. Golden churn covers 1,000 resets and 500 mode/rate changes; full platform and device soaks remain separate gates.

## Android SDR Workbench v4 bounds

One explicit I/Q file records at a time, each at most ten minutes; total capture storage is at most 2 GiB. Replay has one worker and four fixed speed choices. Spectrum Survey uses one serialized aggregator, indexed 15-minute/1 kHz aggregates, 7/30/90-day retention, at most 500,000 rows and 128 MiB. Display analysis consumes reduced traces; tracker is singular and channel monitors cap at four. Scanner dwell remains between the operator minimum and twice that value.

The deterministic v4 profile covers three ten-minute capture segments (30 minutes total), ten-minute seek, two local receivers during replay, four monitors, 100,000 survey aggregation inputs across 30 simulated days, 1,000 tracker selections, 1,000 replay/live switches, adaptive scanner ordering and TCI reconnect/stale-frame stress. It records RSS/PSS/native heap, threads, file descriptors, throughput, dropped blocks, seek/query/aggregation latency and database size. Generated captures/database are deleted after the profile; final measured values belong to exact-SHA evidence and are not tablet guarantees.

## Android TCI Transmit v5 bounds

TCI TX has one serialized session, an eight-entry chrono queue, native input cap, exact 1..16384 even-value packets, audited sample rates, 45-second audio ceiling, 30-second maximum Tune, and no reconnect replay. Deterministic validation covers a 30-minute fake stream, repeated FT8/FT4 slots, 1,000 PTT, 1,000 Tune, 1,000 abort/recovery cycles, disconnect-during-TX and per-mode switching. Record PSS/RSS/native heap, threads, FDs, queue high-water, under/overruns, frames, PTT/stop/RX latency and jitter; no bounded fake result is a physical-radio claim.

The final local JVM scale run completed 1,800 simulated seconds with 85,600 binary frames, queue high-water 1, zero underruns, zero overruns, 202 file descriptors before and after, 12/14 threads before/after, 20,514,536/13,848,360 bytes of JVM heap before/after forced collection, and 107,520/97,440 KiB host RSS before/after. The deterministic fake reports zero-millisecond PTT/RX acknowledgement latency and one-frame (21.333 ms at 48 kHz) pacing jitter; those timing values validate the harness and state machine, not real network or radio latency. Android process PSS/native-heap and physical-radio timing remain device acceptance evidence.

## Final Remote Station media bounds

Control frames remain capped at 64 KiB and media at 256 KiB plus header. Opus uses 20 ms frames, bounded 12–128 kbit/s policy, in-band FEC, one decoder queue per client, and backpressure drops instead of blocking radio ownership. Raw I/Q is disabled after restore, explicitly enabled per host session, limited to one client, capped by sample rate/frame size, and dropped under backpressure. Exact-SHA sanitizer, protocol, scale, and package jobs remain mandatory.
