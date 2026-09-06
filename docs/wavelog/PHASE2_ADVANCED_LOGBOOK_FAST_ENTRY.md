# Phase 2 Advanced Logbook and Fast Entry

## Provenance

The behavior comparison used Wavelog release 3.1.0 at peeled commit `af3256140bd05403b7c4a421746c2ea653a4f04f` under the MIT licence. The reviewed upstream paths were:

- `application/controllers/Logbookadvanced.php`
- `application/models/Logbookadvanced_model.php`
- `application/controllers/Simplefle.php`
- `application/views/simplefle/index.php`
- `assets/js/sections/simplefle.js`

ShackCQ does not copy or adapt those PHP, HTML, or JavaScript sources. It implements the observed product behaviors independently in Kotlin/Compose, Swift/SwiftUI, and the existing SQLite stores. ShackCQ remains GPL-3.0-only.

## Native decisions

- Normal Advanced Logbook browsing is SQL-paged and deterministically sorted. Explicit full filtered ADIF export may materialize the selected result because the export artifact itself is necessarily complete.
- A duplicate candidate is visibly defined as the same normalized callsign, exact frequency, main mode, and a time within 15 seconds. It is a review signal, not an automatic deletion.
- Native Wavelog relation filters query the persisted remote-link, outbox, conflict, and tombstone tables. “Remote deleted” means an acknowledged native tombstone; it is not inferred from a missing bounded page.
- Analytics and quick-filter navigation share `logbookFilterForDimension`; unknown dimensions are a no-op.
- Bulk remote deletion is intentionally absent. Local deletion remains the existing single-QSO explicit choice with guarded remote deletion only when the accepted baseline and scope permit it.
- Wavelog 3.1.0 has no server idempotency contract. The obsolete custom Apple header was removed; ambiguous Apple writes remain operator-visible instead of being blindly retried.

## Fast Entry contract

`fixtures/wavelog/fast_entry_golden.json` is the cross-platform corpus. Android and Apple both parse inherited context, explicit date and day increments, timezone offsets, time shorthand, frequency-derived bands, broad modes/submodes, callsign/RST/name/grid, multiple portable references, contest exchanges, satellite and arbitrary ADIF fields, UTF-8 comments, and QSL messages. Invalid lines remain visible and never create hidden rows.

Both native workspaces support editable previews, optional callbook enrichment, all-valid versus explicit selected import, import summary, and an undo receipt that expires after any later local mutation. Apple drafts use scene storage; Android drafts and selection mode use saveable state.

## Evidence boundary

The current operator-directed acceptance scope is Android-only; Apple source parity is retained, but further Apple build and simulator validation is deferred. Repository tests, host SQLite measurements, and APK assembly prove Android source-level behavior only. They do not prove authenticated remote mutation, network ambiguity recovery against a live server, physical rotation/keyboard/pointer behavior, or radio/device operation. No Android device/emulator was attached, so connected benchmark and physical acceptance remain unverified and the Phase 2 verdict is `PARTIAL` rather than `PASS`.
