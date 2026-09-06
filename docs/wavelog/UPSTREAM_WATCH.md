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
