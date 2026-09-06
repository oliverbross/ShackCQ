# Android and Windows semantic conflict ledger

## Textual conflict

| Path | Android side | Windows side | Resolution |
|---|---|---|---|
| `core/CMakeLists.txt` | ASan/UBSan options, propagation and lifecycle-stress tests | optional standalone core tests and MSVC warning policy | Kept all three test targets behind `SHACKCQ_CORE_BUILD_TESTS`; retained sanitizers; added MSVC-safe flags to both added tests. |

No blanket `ours` or `theirs` resolution was used.

## Semantic resolutions

- Hamlib: one tree remains at `core/third_party/hamlib`, pinned to upstream `40f63488fe0bd751b147f48d62fd217bf53713a0`; platform-generated configuration may remain local to each build.
- Qt lifecycle: Wavelog tracks and aborts active replies, rejects late work after idempotent close, and the application closes Wavelog/cluster/audio/rotator/radio/support services before logger shutdown.
- Configuration: recursive export strips credentials and restored authority even when names are composite; unknown future sections are reported for explicit review rather than silently discarded.
- Data/Wavelog: Android and desktop keep separate database/outbox implementations and share semantic fixtures; no byte-interchangeability claim is made.
- CI: existing mobile/core, Windows and macOS workflows are reusable from one exact-SHA candidate workflow; no deployment, signing or external credentials were added.
