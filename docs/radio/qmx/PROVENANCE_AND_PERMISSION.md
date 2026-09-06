# QMX/QMX+ provenance and permission

## Reviewed upstream

- Repository: `https://github.com/SteffenLav/qmx-panadapter`
- Default branch at review: `main`
- Commit: `30c61f6142153d61d3160689aab1edbf95de810d`
- Tree: `aed9dcdec704d13d380d6dc85c7c28a233a74244`
- Release/tag: `1.9.2` / `v1.9.2`
- Licence: MIT
- LICENSE SHA-256: `eb9507064a15ee9a14b9e3ffb22711ce29564ea2be4ca7acda77e249f608c385`
- Copyright: `Copyright (c) 2026 Steffen Lav (OZ1LAV)`

The ShackCQ owner reports explicit permission from OZ1LAV. That statement is recorded as owner-provided evidence; ShackCQ relies on the repository's MIT licence as the legal source.

## Review scope

The review covered the actual pinned `README.md`, `LICENSE`, version history, QMX source ledger, 1.03/1.04 CAT comparison, all guide/reference Markdown, CAT, audio, DSP, display, UI, FT8 TX/QSO/decode, ADIF, storage/settings, network and build sources. No upstream `NOTICE` or third-party notice file exists at the reviewed top-level search depth; bundled component licences remain upstream platform concerns and are not dependencies of this branch.

The brief named `main/settings/**`, but that path is absent at the pin. QMX settings live under `main/storage/**`; the absence is recorded rather than repaired or silently reinterpreted.

## Adaptation boundary

The Kotlin implementation is independently written from observed behavior and documented protocol facts. No ESP32 source, comments, UI assets, fixtures, state shapes, FT exchange engine, provider client, logbook code, binary, component or build dependency was copied. ShackCQ retains GPL-3.0-only source and adds no runtime dependency.

Reviewed behavior used in the independent implementation includes:

- exact Kenwood-style QMX CAT shapes and native AF/RF units;
- Q9 write-echo hazard and mandatory readback;
- Q3 VOX disable/readback;
- 48 kHz stereo UAC I/Q with approximately +12 kHz IF;
- clear-first RIT behavior because RU/RD can be configured as relative or absolute;
- QMX's lack of XIT and the VFO-B/split CW workaround;
- FT8 79 × 160 ms and FT4 105 × 48 ms absolute monotonic cadence;
- `TX;`, `TA<tone>;`, `TA0;`, 5 ms settle and `RX;` cleanup;
- second-CDC terminal isolation and bounded 80×24 ANSI behavior.

## Later NOTICE entry

This branch does not edit `NOTICE` because it incorporates no upstream source or asset. If a later integration copies or adapts a substantial MIT portion, add this exact entry after confirming that later diff:

> QMX Panadapter by Steffen Lav (OZ1LAV), version 1.9.2, commit 30c61f6142153d61d3160689aab1edbf95de810d, MIT License. ShackCQ's QMX/QMX+ integration is independently maintained and does not imply upstream endorsement.
