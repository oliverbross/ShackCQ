# Wavelog award parity inventory

Authoritative baseline: Wavelog 3.1.0 at commit
`af3256140bd05403b7c4a421746c2ea653a4f04f`. This inventory was generated from
the pinned `Awards.php`, award models, award views, and their supporting paths;
the website navigation was not treated as authoritative.

## Families found upstream

| Family | Pinned source family | ShackCQ status |
|---|---|---|
| DXCC | `Awards.php`, `Dxcc.php`, `awards/dxcc*` | Implemented from the unified local QSO snapshot; worked/confirmed filters share the Progress engine. |
| CQ and ITU zones | `Awards.php`, `awards/cq*`, `awards/itu*` | CQ/WAZ implemented; ITU-zone parity pending. |
| WAC, WAE, WAS, WPX | `Wac.php`, `Wae.php`, `Was.php`, `Wpx.php` and matching views | WAS implemented; WAC/WAE/WPX pending. |
| IOTA, SOTA, POTA, WWFF | `Iota.php`, `Sota.php`, matching award views | POTA implemented; IOTA/SOTA/WWFF award rules pending. Portable reference fields are preserved. |
| DOK and WAB | `Dok.php`, `awards/dok*`, `awards/wab*` | Pending licensed/reference datasets and validation fixtures. |
| Japan | `Jcc_model.php`, `Waja.php`, `awards/jcc*`, `awards/waja*` | Pending. |
| Canada | `Rac.php`, `awards/rac*` | Pending. |
| Switzerland | `Helvetia_model.php`, `awards/helvetia*` | Pending. |
| Poland | `Award_pl_polska.php`, `awards/pl_polska*`, `Wap.php`, `Wapc.php` | Pending. |
| Satellite/grid | `Amsat_rover.php`, `awards/amsat_rover*`, `vucc`, `ffma`, `gridmaster`, `73on73` views | Satellite analytics implemented; rule-complete award parity awaits the pinned SGP4 and reference-data packaging gate. |
| Other regional/programme families | `counties`, `sig`, `waip` award views | Pending source-specific rules, datasets, licences, and fixtures. |

## Common rule contract

Every implemented family is evaluated from the same filtered local QSO snapshot.
The common contract covers worked versus confirmed, accepted confirmation source,
band, mode, date, operator, satellite, and deleted-QSO filtering. Award-specific
eligibility units and reference datasets must be added to this table with their
licence and fixture provenance before a pending family can be called complete.
Unknown ADIF fields remain preserved, so future award families do not require a
lossy log migration.

This is a parity ledger, not a claim that every upstream award is implemented.
Rows marked pending must not appear as completed user-visible awards.
