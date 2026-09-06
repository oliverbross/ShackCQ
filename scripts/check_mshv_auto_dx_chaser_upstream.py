#!/usr/bin/env python3
"""Read-only audit of the pinned MSHV Auto DX Chaser upstream."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

REPOSITORY = "kd9taw/mshv-auto-dx-chaser"
BRANCH = "master"
REVIEWED_COMMIT = "d960ae22de78940c6be9d95bd4817d233d02ee39"
REVIEWED_TREE = "52d5b2e4d39a8e174000971ada3ac0c9f0442625"
REVIEWED_LICENSE_SHA256 = "ae8271f05a41a70dc47b89c560724128c3e2007f8f7db6e2c21c7f1360fe79fa"
REVIEWED_THIRD_PARTY_SHA256 = "c6419803337ee76f3e28ed81993b60f945cf782c59f11a71877f74321842f0b0"
MAX_API_BYTES = 8_000_000
MAX_PATHS = 300

WATCHED_PREFIXES = (
    "README.md", "CHANGELOG.md", "docs/", "LICENSE", "THIRD_PARTY_LICENSES.md",
    "MSHV_2762/src/HvAutoDxer/", "MSHV_2762/src/HvAutoDxSettings/",
    "MSHV_2762/src/HvDxccChaser/", "MSHV_2762/src/main_ms.",
)


class ComparisonError(RuntimeError):
    pass


def request_json(path: str) -> Any:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-MSHV-Auto-DX-Chaser-audit",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"https://api.github.com{path}", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read(MAX_API_BYTES + 1)
            if len(raw) > MAX_API_BYTES:
                raise ComparisonError("GitHub response exceeded byte limit")
            return json.loads(raw)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise ComparisonError(f"GitHub comparison failed for {path}: {error}") from error


def commit(ref: str) -> dict[str, str]:
    row = request_json(f"/repos/{REPOSITORY}/commits/{urllib.parse.quote(ref, safe='')}")
    try:
        sha, tree = row["sha"], row["commit"]["tree"]["sha"]
    except (KeyError, TypeError) as error:
        raise ComparisonError("Malformed commit response") from error
    if not all(isinstance(value, str) and len(value) == 40 for value in (sha, tree)):
        raise ComparisonError("Invalid commit identity")
    return {"commit": sha, "tree": tree}


def content_digest(path: str, ref: str) -> str:
    quoted = "/".join(urllib.parse.quote(part, safe="") for part in path.split("/"))
    row = request_json(f"/repos/{REPOSITORY}/contents/{quoted}?ref={urllib.parse.quote(ref, safe='')}")
    try:
        encoded = "".join(row["content"].split())
        raw = base64.b64decode(encoded, validate=True)
    except (KeyError, TypeError, ValueError) as error:
        raise ComparisonError(f"Malformed content response for {path}") from error
    return hashlib.sha256(raw).hexdigest()


def categories_for_path(path: str) -> list[str]:
    lower = path.lower()
    values: set[str] = set()
    if path in {"LICENSE", "THIRD_PARTY_LICENSES.md"}: values.add("LICENCE_PROVENANCE")
    if path.startswith("docs/") or path in {"README.md", "CHANGELOG.md"}: values.add("DOCUMENTATION")
    if "targetscorer" in lower: values.update(("SCORING", "TARGET_SELECTION"))
    if "decodecache" in lower or "localspotbridge" in lower: values.add("LOCAL_DECODE")
    if "bandmanager" in lower: values.add("BAND_SWITCHING")
    if "spotaggregator" in lower: values.add("PROVIDER")
    if "dxcc" in lower: values.add("DXCC_TRACKING")
    if "settings" in lower: values.add("SETTINGS")
    if "panel" in lower: values.add("UI")
    if "autodxercore" in lower:
        values.update(("STATE_MACHINE", "TARGET_SELECTION", "PERSISTENCE", "SAFETY"))
    if "main_ms." in lower: values.update(("SAFETY", "MSHV_DESKTOP_ONLY"))
    return sorted(values or {"MSHV_DESKTOP_ONLY"})


def audit() -> dict[str, Any]:
    reviewed = commit(REVIEWED_COMMIT)
    if reviewed != {"commit": REVIEWED_COMMIT, "tree": REVIEWED_TREE}:
        raise ComparisonError("Reviewed commit/tree identity changed")
    observed = commit(BRANCH)
    comparison: dict[str, Any] = {"status": "identical", "ahead_by": 0, "total_commits": 0, "paths": []}
    if observed["commit"] != REVIEWED_COMMIT:
        row = request_json(f"/repos/{REPOSITORY}/compare/{REVIEWED_COMMIT}...{observed['commit']}?per_page=100")
        files = row.get("files") if isinstance(row, dict) else None
        if not isinstance(files, list):
            raise ComparisonError("Compare response omitted changed files")
        paths = [item.get("filename") for item in files if isinstance(item, dict) and isinstance(item.get("filename"), str)]
        comparison = {
            "status": row.get("status", "unknown"), "ahead_by": row.get("ahead_by"),
            "total_commits": row.get("total_commits"), "paths": paths[:MAX_PATHS],
            "paths_truncated": len(paths) > MAX_PATHS,
        }
    licence = content_digest("LICENSE", observed["commit"])
    third_party = content_digest("THIRD_PARTY_LICENSES.md", observed["commit"])
    watched = [path for path in comparison["paths"] if any(path.startswith(prefix) for prefix in WATCHED_PREFIXES)]
    categories: dict[str, list[str]] = {}
    for path in watched:
        for name in categories_for_path(path):
            categories.setdefault(name, []).append(path)
    reasons = []
    if observed["commit"] != REVIEWED_COMMIT: reasons.append("upstream branch advanced from reviewed commit")
    if observed["tree"] != REVIEWED_TREE: reasons.append("upstream tree differs from reviewed tree")
    if licence != REVIEWED_LICENSE_SHA256: reasons.append("GPL-3.0 licence digest changed")
    if third_party != REVIEWED_THIRD_PARTY_SHA256: reasons.append("third-party licence digest changed")
    if comparison.get("paths_truncated"): reasons.append("changed-path inventory truncated")
    return {
        "schema_version": 1, "result": "REVIEW_REQUIRED" if reasons else "NO_REVIEW",
        "review_required": bool(reasons), "repository": REPOSITORY, "branch": BRANCH,
        "reviewed": {"commit": REVIEWED_COMMIT, "tree": REVIEWED_TREE,
            "license_sha256": REVIEWED_LICENSE_SHA256, "third_party_sha256": REVIEWED_THIRD_PARTY_SHA256},
        "observed": {**observed, "license_sha256": licence, "third_party_sha256": third_party},
        "comparison": comparison, "watched_changes": categories, "review_reasons": reasons,
    }


def markdown(result: dict[str, Any]) -> str:
    lines = ["# MSHV Auto DX Chaser upstream audit", "", f"**Result: {result['result']}**", "",
        f"- Reviewed commit/tree: {REVIEWED_COMMIT} / {REVIEWED_TREE}",
        f"- Observed commit/tree: {result.get('observed', {}).get('commit', 'unavailable')} / {result.get('observed', {}).get('tree', 'unavailable')}",
        "- This watcher is read-only; it never updates the pin or production source.", "", "## Review reasons", ""]
    lines += [f"- {reason}" for reason in result.get("review_reasons", [])] or ["- None"]
    lines += ["", "## Classified watched changes", ""]
    for name, paths in result.get("watched_changes", {}).items():
        lines.append(f"- {name}")
        lines.extend(f"  - {path}" for path in paths)
    if not result.get("watched_changes"): lines.append("- None")
    return "\n".join(lines) + "\n"


def write(output: Path, result: dict[str, Any]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "mshv-auto-dx-chaser-upstream.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    (output / "mshv-auto-dx-chaser-upstream.md").write_text(markdown(result))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("build/reports"))
    args = parser.parse_args()
    try:
        result = audit()
        write(args.output_dir, result)
        return 2 if result["review_required"] else 0
    except (ComparisonError, OSError) as error:
        result = {"schema_version": 1, "result": "COMPARISON_FAILED", "review_required": True,
            "review_reasons": ["comparison failure"], "error": str(error), "watched_changes": {}}
        try: write(args.output_dir, result)
        except OSError: pass
        print(f"MSHV Auto DX Chaser comparison failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
