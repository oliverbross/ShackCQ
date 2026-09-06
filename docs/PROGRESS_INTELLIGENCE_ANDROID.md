# Android Progress Intelligence

ShackCQ's Progress workspace turns the existing local QSO journal and existing live DX/portable snapshots into offline operating guidance. It adds no schema, analytics cache, dependency, telemetry, background alert, or network client.

## Workspace

- Expanded tablets show Progress in the navigation rail.
- Compact layouts keep the existing bottom navigation and expose Progress from Home and Logbook.
- Overview contains scoped KPIs, native charts, a UTC activity heatmap, distance coverage, and a MapLibre contact map using the existing CARTO/OpenStreetMap source.
- Needs matches resolved existing DX, POTA, and WWFF activity to locally worked data. Unresolved DX rows are excluded. SOTA live remains unavailable pending approval.
- Awards provides DXCC-, WAS-, WAZ-, QRP DXCC-, and bounded POTA-style local estimates.
- Portable summarizes locally hunted references and retained POTA activation QSOs.
- Up to four predefined personal goals are stored atomically in app-private JSON.

All award-like UI carries:

LOCAL ESTIMATE · NOT OFFICIAL AWARD CREDIT

## Calculation truth

The existing QSO journal is the only local authority. A snapshot reads it once, calculates on Dispatchers.Default, cancels superseded work, and refreshes when the database revision, filters, live snapshots, CTY data, Sync Hub state, or goals change.

- Worked means a distinct locally stored identifier after the selected station, period, band, and mode-family filters.
- Confirmed means lotwReceived or qslReceived is Y or V.
- QRZ and eQSL received indicators are shown separately. Provider uploads and Club Log acceptance never count as confirmation.
- Unknown values remain unknown. Coverage labels report the number of usable rows out of the scoped QSO total.
- CTY lookup may supply an in-memory display/entity match; it never mutates the stored QSO and is not presented as an official ARRL list.
- DXCC-style counts keep entity identifiers distinct. The 5-band matrix is 80/40/20/15/10 m; 60 m is excluded from award-band progress.
- WAS uses only the canonical 50 state abbreviations and never guesses state from callsign.
- WAZ accepts only CQ zones 1 through 40.
- QRP DXCC-style progress requires known positive TX power at or below 5 W.
- POTA hunted references come from potaRef/potaRefs; activated references come from myPotaRef/myPotaRefs.
- A multi-park QSO remains one QSO while contributing to each explicit own-reference counter.
- A local successful POTA estimate is at least 10 distinct retained QSO rows for one own reference on one UTC day.
- A P2P QSO requires explicit own and other POTA references.
- Older own-reference QSOs without a session contribute to unique parks but do not create session performance.
- SOTA association/region coverage is joined from the downloaded local catalogue. No SOTA points are calculated.
- WWFF has no fabricated worldwide denominator and remains list-only without location data.

## Official programme references checked

These define the official programmes; ShackCQ does not claim their credit:

- [ARRL DXCC rules](https://www.arrl.org/dxcc-rules)
- [ARRL DXCC award information](https://www.arrl.org/dxcc-award-information)
- [ARRL Worked All States](https://www.arrl.org/was)
- [ARRL QRP DXCC](https://www.arrl.org/qrp-dxcc)
- [CQ Worked All Zones](https://cq-amateur-radio.com/cq_awards/cq_waz_awards/index_cq_waz_award.html)
- [POTA awards](https://docs.pota.app/docs/awards.html)

The checked POTA standard unique-park levels begin at 10, 20, 30, 40, 50, and 75, followed by 100 and the published advanced levels. ShackCQ shows only the next bounded local milestone and a handoff to POTA for authoritative status.

## Privacy and safety

Progress has no provider credential access, upload action, automatic tuning, transmit action, telemetry, support export, or new endpoint. Existing DX and Portable actions remain authoritative. Runtime data is never seeded with sample QSOs or statistics.
