# DX Chaser integration contract v1

The core exposes immutable, bounded, Compose-independent contracts in
`app.shackcq.mobile.dxchaser`. Integration must create exactly one `DxChaserController`; this branch does not instantiate it.

## Inputs

- `DxChaserLocalDecodePort` supplies one station-scoped, generation-safe `DxChaserInputSnapshot`.
- `DxChaserNeedsPort`, `DxChaserProviderEvidencePort`, `DxChaserOperatingContextPort` and `DxChaserRarityPort` define the narrow
  future adapters. Missing award/evidence dimensions must be `UNAVAILABLE`, never inferred as needed.
- Lists are bounded before entry: at most 500 local decodes, provider bodies omitted, no complete QSO list, no credentials.

Only `LIVE_CAPTURE` and exact current-slot `REDECODE_LIVE_SLOT` can pass provenance. The semantic adapter must preserve the exact
station, radio, band, mode, dial frequency, Digi session, captured slot and generation values.

## Outputs

`DxChaserActionPort` receives typed intents. `PREPARE_FT_CALL` carries the display/base callsign, grid, band, mode, dial/audio
frequency, slot identity, local decode ID, score, reason, session ID and generation. The adapter must revalidate every field before
using the existing Digi sequencer. An intent is not authorization to bypass TX enable, TX arm, route/audio health, PTT or RX checks.

`DxChaserReviewPort` handles `REQUEST_RECEIVE_BAND_REVIEW` through the existing receive-review route. Acceptance does not make the
external row eligible; a qualifying new local decode and normal Digi safety are still mandatory.

`DxChaserQsoOutcomePort` reports only canonical outcomes after `QsoMutationCoordinator` succeeds. DX Chaser updates statistics and
cooldown metadata but never creates a QSO.

Background, route loss, radio/station/mode change, material frequency change or controller close must be delivered as a stop event.
Pending intents and engaged state are cleared and are never restored after process death or configuration import.

## Band Maps read-only contract

Task C consumes only `DxChaserReadOnlyPort.snapshot()`. `DxChaserReadOnlySnapshot` v1 contains at most 50 ranked candidates, one
current target, an engaged call label, 100 cooldowns, 20 cross-band opportunities and a provider-freshness summary. It is immutable
and already reduced; Band Maps must not call the engine or query `shackcq-dxchaser.sqlite`.

Candidate summaries contain callsign, band/mode/frequency/audio frequency, score/tier/eligibility, need reasons, local age/SNR,
current and outlook labels, watchlist and cooldown truth. This is sufficient to render `CHASER PRIORITY`, `CHASER TARGET`,
`CHASER COOLDOWN` and `CHASER INELIGIBLE REASON`.

## Parallel integration boundaries

- Digi: use the existing FT parity, exact live decode, TX enable/arm and sequencer; do not create another FT state machine.
- Keyer/Hotkeys: may later map start/stop/accept commands but cannot bypass Digi safety; no CW/voice keying is used.
- Contest: may later supply active-contest/duplicate/multiplier/claimed-station read-only context; Chaser never scores contests.
- Band Maps: read-only snapshot only.
- Configuration/System Health/MainActivity: later semantic wiring only; none is edited in this branch.

