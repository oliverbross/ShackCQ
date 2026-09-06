#!/usr/bin/env python3
"""Compare the reviewed Wavelog pin with the latest stable GitHub release.

This tool is deliberately read-only. It never rewrites the pin or product source.
Exit 0 means the reviewed release is still current; exit 2 means human review is
required; exit 1 means the comparison itself could not be completed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


API_ROOT = "https://api.github.com/repos/wavelog/wavelog"
def request_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-Wavelog-Upstream-Watch/1",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code < 500 or attempt == 2:
                raise
        except urllib.error.URLError as error:
            last_error = error
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)
    raise RuntimeError(f"request failed: {last_error}")


def resolve_commit(tag_name: str) -> str:
    ref = request_json(f"{API_ROOT}/git/ref/tags/{tag_name}")["object"]
    while ref["type"] == "tag":
        ref = request_json(ref["url"])["object"]
    if ref["type"] != "commit":
        raise ValueError(f"tag {tag_name} resolved to {ref['type']}, not commit")
    return str(ref["sha"])


def path_digest(commit: str, path: str) -> str:
    value = request_json(f"{API_ROOT}/contents/{path}?ref={commit}")
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(canonical).hexdigest()


def render(pin: dict, latest: dict, latest_commit: str) -> tuple[str, bool]:
    pinned_release = str(pin["release"])
    latest_release = str(latest["tag_name"])
    changed = pinned_release != latest_release or pin["commit"] != latest_commit
    lines = [
        "# Wavelog upstream watch",
        "",
        f"- Reviewed release: `{pinned_release}`",
        f"- Reviewed commit: `{pin['commit']}`",
        f"- Latest stable release: `{latest_release}`",
        f"- Latest stable commit: `{latest_commit}`",
        f"- Result: **{'REVIEW REQUIRED' if changed else 'NO CHANGE'}**",
        "",
    ]
    if changed:
        lines += [
            "## Human review checklist",
            "",
            "1. Read the upstream release notes and migration notes.",
            "2. Compare every tracked API, schema, award, and operational-tool path.",
            "3. Run native sync, migration, conflict, Fast Entry, analytics, and platform builds.",
            "4. Update the pin only in a reviewed feature branch; never from this job.",
            "",
        ]
    return "\n".join(lines), changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pin", default="docs/wavelog/upstream.json")
    parser.add_argument("--output")
    parser.add_argument("--verify-pinned-paths", action="store_true")
    args = parser.parse_args()
    try:
        pin = json.loads(Path(args.pin).read_text(encoding="utf-8"))
        latest = request_json(f"{API_ROOT}/releases/latest")
        latest_commit = resolve_commit(str(latest["tag_name"]))
        report, changed = render(pin, latest, latest_commit)
        if args.verify_pinned_paths:
            paths = list(pin.get("trackedPaths", []))
            digests = [(path, path_digest(str(pin["commit"]), path)) for path in paths]
            report += "\n## Reviewed-path reachability\n\n"
            report += "\n".join(f"- `{path}`: `{digest}`" for path, digest in digests)
            report += "\n"
        if args.output:
            Path(args.output).write_text(report, encoding="utf-8")
        print(report)
        return 2 if changed else 0
    except (KeyError, ValueError, OSError, json.JSONDecodeError, urllib.error.URLError) as error:
        print(f"Wavelog upstream comparison failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
