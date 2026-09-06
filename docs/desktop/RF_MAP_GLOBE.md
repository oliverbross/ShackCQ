# RF Observation Map and Globe

## Truth model

RfObservationModel is derived, non-canonical state beneath ShackCQ's existing QSO, spot, Neural DX, Band Health, and provider owners. Every row records source identity, observation time, evidence class (LIVE, HISTORICAL, or OUTLOOK), endpoint precision, coordinates, band/mode, distance/bearing, and applicable worked/confirmed/needed fields.

Reported SNR is displayed only when the source genuinely supplied it. A logged QSO contributes a historical path and no invented SNR. COARSE endpoints are hollow and never presented as exact. Demo records load only when SHACKCQ_DESKTOP_DEMO=1.

Filters run in C++ for source, band, mode, evidence class, age, distance, callsign, worked, confirmed, needed DXCC, freshness, and explicit long path. Preferences are schema-versioned and safe to persist. Storage is capped at 100,000 observations. Rendering samples at most 4,096 deterministic rows plus the selected row, avoiding thousands of QML delegates or unbounded geometry.

## Geometry and display

Distance, initial bearing, short/explicit-long great circles, interpolation, and antimeridian segmentation use standard spherical formulas. Paths below 1,000 km receive no ionospheric-style control point. Longer paths use a documented nominal 3,500 km hop distance and at most five points. Heat halos distinguish observed live evidence from separately coloured outlook; no point claims exact MUF.

One public RfMapItem renders both projections. Flat mode supplies offline coastlines, grid, station/target markers, selected path, day/night cells, terminator, heat/control points, pan, and zoom. Globe mode uses an interactive orthographic sphere with drag rotation, wheel/pinch zoom, clipped coastlines, arcs, markers, terminator, and solar-direction shading. Selection is shared and action-free until the operator chooses explicit QSY, Logbook, DX, or Band Maps.

The compiled coastline outline is independently simplified from Natural Earth ne_110m_land release 5.1.2. Original ZIP SHA-256: 9e0729ee253ca7d7a5c4ae9395fb1902264c5377c52e224d13dd85010e2835d9; original size: 138,160 bytes. Natural Earth data is public domain. No runtime tile service, commercial key, WebView, private Qt API, SDRoxide map asset, or texture is packaged.

## Scale evidence

The deterministic local benchmark stores 100,000 observations, filters 50,000 matching rows, and paints a 4,096-row aggregate. Representative macOS arm64 Release measurements were 2.6 s ingest, 0.5 s filter, and 0.3 s map update; hosted exact-SHA values are recorded with the final workflow evidence. These measurements are software performance evidence, not live-provider or RF proof.
