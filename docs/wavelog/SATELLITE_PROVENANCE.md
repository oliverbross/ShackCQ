# Satellite engine provenance decision

## Audited candidate

- Repository: `https://github.com/dnwrnr/sgp4`
- Immutable commit: `661e057a5d369d5ee424676cf1d69cbead95ff2c`
- Commit date: 2026-07-26
- Licence: Apache-2.0
- Build: C++17/CMake
- Evidence present upstream: TLE parsing, SGP4 propagation, observer/topocentric
  output, pass-prediction example, `SGP4-VER.TLE`, and focused unit tests.
- Local audit: the candidate built with examples disabled and all 168 CTest
  cases passed, including its propagation vectors and CSV/TLE coverage.

Apache-2.0 is compatible for inclusion in this GPL-3.0-only product when the
Apache licence, notices, attribution, and modification statements are retained.
No upstream source has been copied into this branch, so no Apache NOTICE payload
is currently distributed by ShackCQ.

## Decision

The candidate is acceptable, but it is not fetched at product build time and is
not vendored until the shared Android/Apple packaging path can carry one audited
copy without duplicated source or network-dependent builds. The exact commit
above is the approved integration candidate. Native pass/flightpath UI therefore
remains blocked, rather than exposing approximate or server-derived predictions.
The audit used the Android SDK's pinned CMake 3.22.1 because `cmake` is not on
the default process PATH.
