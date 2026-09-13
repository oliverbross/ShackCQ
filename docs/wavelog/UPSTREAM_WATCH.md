# Wavelog upstream watch

`scripts/check_wavelog_upstream.py` compares the reviewed Wavelog release and
peeled commit in `upstream.json` with GitHub's latest stable release. It is
read-only by design: a changed release fails the scheduled/manual workflow and
produces a review artifact, but never changes the pin or ShackCQ source.

Run locally:

```sh
python3 scripts/check_wavelog_upstream.py --verify-pinned-paths
```

Exit status `0` means no release/commit change, `2` means human review is
required, and `1` means the comparison failed. When review is required, inspect
the tracked API, schema, award, and operational-tool paths at the new immutable
commit, rerun all migration/sync/platform validation, update parity and
provenance, then change `upstream.json` in a reviewed feature branch.

## 3.2.0 compatibility review

Release 3.2.0 was reviewed at peeled commit
`824a7c2be815988799610abb2d0661e889ba119d` (annotated tag object
`0dd59c462f2e7874fc4f149851e624cf7d1faf40`). The immutable 3.1.0..3.2.0
tracked-path comparison changed 12 files (1,414 insertions and 989 deletions),
principally adding Contest, Logbook, and Catalog API-v2 resources and refactoring
advanced-logbook/statistics/award paths. It made no change to the existing QSO
resource, `Api_v2.php`, `Api_v2_response.php`, or `Api_v2_model.php`. Station
list/read response semantics used by ShackCQ remain compatible; Station update
adds `set_active` and profile country output is improved. The upstream licence
remains MIT.

ShackCQ validation for this review is deliberately non-destructive: Android API
v2 model/cache/fake-transport tests and the Agent fake-Wavelog outbox path. No
live Wavelog account or network write was performed, and this review does not
authorize one.
