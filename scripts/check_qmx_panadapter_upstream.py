#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only QMX Panadapter upstream watcher; never updates source or opens a PR."""

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

API_ROOT = "https://api.github.com/repos/SteffenLav/qmx-panadapter"

CATEGORIES = {
    "main/cat/": "CAT", "main/audio/": "AUDIO_IQ", "main/dsp/": "DSP",
    "main/display/": "PANADAPTER", "main/render/": "PANADAPTER", "main/ft8_decode": "FT8_RX",
    "main/ft8_tx": "FT8_TX", "main/ft8_qso": "SAFETY", "main/qmx_term": "RADIO_MENU",
    "main/settings/": "SETTINGS", "main/storage/": "SETTINGS", "main/util/ansi_term": "RADIO_MENU",
    "main/util/diag": "DIAGNOSTICS", "main/util/status": "DIAGNOSTICS",
    "main/ui/": "PLATFORM_ONLY", "main/net/": "PLATFORM_ONLY", "LICENSE": "LICENCE",
    "README.md": "DOCUMENTATION", "docs/": "DOCUMENTATION", "components/espressif__usb_host_uac/": "USB",
}


def request_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-QMX-Upstream-Watch/1",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def category(path: str) -> str:
    for prefix, value in CATEGORIES.items():
        if path == prefix or path.startswith(prefix):
            return value
    if "usb" in path.lower() or "cdc" in path.lower():
        return "USB"
    if "setting" in path.lower():
        return "SETTINGS"
    return "PLATFORM_ONLY"


def live_state(pin: dict[str, Any]) -> dict[str, Any]:
    repository = request_json(API_ROOT)
    branch = str(repository["default_branch"])
    head = request_json(f"{API_ROOT}/commits/{urllib.parse.quote(branch)}")
    release = request_json(f"{API_ROOT}/releases/latest")
    licence = request_json(f"{API_ROOT}/contents/LICENSE?ref={head['sha']}")
    licence_bytes = base64.b64decode(licence["content"])
    files: list[dict[str, str]] = []
    if str(head["sha"]) != pin["reviewedCommit"]:
        comparison = request_json(f"{API_ROOT}/compare/{pin['reviewedCommit']}...{head['sha']}")
        for item in comparison.get("files", []):
            path = str(item["filename"])
            files.append({"path": path, "status": str(item["status"]), "category": category(path)})
    return {
        "defaultBranch": branch,
        "commit": str(head["sha"]),
        "tree": str(head["commit"]["tree"]["sha"]),
        "version": str(release["tag_name"]).removeprefix("v"),
        "licenseSha256": hashlib.sha256(licence_bytes).hexdigest(),
        "files": files,
    }


def compare(pin: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    changed = any((
        current["defaultBranch"] != pin["defaultBranch"],
        current["commit"] != pin["reviewedCommit"],
        current["tree"] != pin["reviewedTree"],
        current["version"] != pin["releaseVersion"],
        current["licenseSha256"] != pin["licenseSha256"],
    ))
    return {
        "result": "REVIEW_REQUIRED" if changed else "NO_CHANGE",
        "reviewed": {
            "branch": pin["defaultBranch"], "commit": pin["reviewedCommit"], "tree": pin["reviewedTree"],
            "version": pin["releaseVersion"], "licenseSha256": pin["licenseSha256"],
        },
        "current": current,
        "relevantChanges": current.get("files", []),
        "readOnly": True,
    }


def markdown(report: dict[str, Any]) -> str:
    reviewed, current = report["reviewed"], report["current"]
    lines = [
        "# QMX Panadapter upstream watch", "",
        f"- Reviewed: `{reviewed['branch']}` / `{reviewed['commit']}` / tree `{reviewed['tree']}` / v{reviewed['version']}",
        f"- Current: `{current['defaultBranch']}` / `{current['commit']}` / tree `{current['tree']}` / v{current['version']}",
        f"- Result: **{report['result'].replace('_', ' ')}**", "", "## Relevant path changes", "",
    ]
    changes = report["relevantChanges"]
    lines.extend(f"- `{item['status']}` `{item['path']}` — {item['category']}" for item in changes)
    if not changes:
        lines.append("- None.")
    lines += ["", "This watcher is read-only. It never updates the pin, product source, branch, or pull requests.", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pin", default="docs/radio/qmx/UPSTREAM.json")
    parser.add_argument("--fixture", help="Offline watcher state fixture")
    parser.add_argument("--json-output")
    parser.add_argument("--markdown-output")
    args = parser.parse_args()
    try:
        pin = json.loads(Path(args.pin).read_text(encoding="utf-8"))
        current = json.loads(Path(args.fixture).read_text(encoding="utf-8")) if args.fixture else live_state(pin)
        report = compare(pin, current)
        rendered = markdown(report)
        if args.json_output:
            Path(args.json_output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if args.markdown_output:
            Path(args.markdown_output).write_text(rendered, encoding="utf-8")
        print(rendered)
        return 2 if report["result"] == "REVIEW_REQUIRED" else 0
    except (KeyError, ValueError, OSError, json.JSONDecodeError, urllib.error.URLError) as error:
        print(f"QMX upstream comparison failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
