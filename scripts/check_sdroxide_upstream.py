#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only SDRoxide release watcher; never updates source, pins, branches, or PRs."""

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

API_ROOT = "https://api.github.com/repos/dividebysandwich/sdroxide"

CATEGORIES = {
    "tci": "TCI",
    "receiver": "RECEIVER",
    "readback": "TCI_CONTROL_READBACK",
    "spot": "SPOT_PROTOCOL",
    "spectrum": "PANADAPTER",
    "waterfall": "WATERFALL",
    "scanner": "SCANNER",
    "skimmer": "SKIMMER",
    "record": "RECORDING",
    "dsp": "RX_DSP",
    "demod": "LOCAL_DEMODULATION",
    "sideband": "SSB_CW",
    "sam": "SAM",
    "nfm": "NFM_WFM",
    "wfm": "NFM_WFM",
    "ctcss": "CTCSS_DCS",
    "dcs": "CTCSS_DCS",
    "rds": "RDS_RBDS",
    "rbds": "RDS_RBDS",
    "audio": "RX_DSP",
    "recording": "RECEIVER_RECORDING",
    "transmit": "PER_MODE_TX_AUDIO",
    "tx_audio": "PER_MODE_TX_AUDIO",
    "propagation": "PROPAGATION",
    "map": "PROPAGATION",
    "globe": "PROPAGATION",
    "safety": "SAFETY",
    "license": "LICENCE",
}


def request_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-SDRoxide-Upstream-Watch/1",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def classify(path: str) -> str:
    lowered = path.lower()
    return next((value for key, value in CATEGORIES.items() if key in lowered), "PLATFORM_OR_OTHER")


def live_state(pin: dict[str, Any]) -> dict[str, Any]:
    release = request_json(f"{API_ROOT}/releases/latest")
    tag = str(release["tag_name"])
    commit = request_json(f"{API_ROOT}/commits/{urllib.parse.quote(tag)}")
    licence = request_json(f"{API_ROOT}/contents/LICENSE?ref={commit['sha']}")
    licence_hash = hashlib.sha256(base64.b64decode(licence["content"])).hexdigest()
    files: list[dict[str, str]] = []
    if str(commit["sha"]) != pin["reviewedCommit"]:
        comparison = request_json(f"{API_ROOT}/compare/{pin['reviewedCommit']}...{commit['sha']}")
        files = [{"path": str(row["filename"]), "status": str(row["status"]),
                  "category": classify(str(row["filename"]))} for row in comparison.get("files", [])]
    return {
        "releaseVersion": tag.removeprefix("v"),
        "releaseTag": tag,
        "commit": str(commit["sha"]),
        "tree": str(commit["commit"]["tree"]["sha"]),
        "licenseSha256": licence_hash,
        "files": files,
    }


def compare(pin: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    changed = any((
        current["releaseVersion"] != pin["releaseVersion"],
        current["releaseTag"] != pin["releaseTag"],
        current["commit"] != pin["reviewedCommit"],
        current["tree"] != pin["reviewedTree"],
        current["licenseSha256"] != pin["licenseSha256"],
    ))
    return {
        "result": "REVIEW_REQUIRED" if changed else "NO_CHANGE",
        "reviewed": pin,
        "current": current,
        "relevantChanges": current.get("files", []),
        "trackedAreas": sorted(set(CATEGORIES.values())),
        "readOnly": True,
        "automaticUpdate": False,
        "automaticPullRequest": False,
    }


def markdown(report: dict[str, Any]) -> str:
    reviewed, current = report["reviewed"], report["current"]
    lines = [
        "# SDRoxide upstream watch", "",
        f"- Reviewed: `{reviewed['releaseTag']}` / `{reviewed['reviewedCommit']}` / tree `{reviewed['reviewedTree']}`",
        f"- Current: `{current['releaseTag']}` / `{current['commit']}` / tree `{current['tree']}`",
        f"- Licence hash: `{current['licenseSha256']}`",
        f"- Result: **{report['result'].replace('_', ' ')}**",
        f"- Tracked: {', '.join(report['trackedAreas'])}", "", "## Relevant changes", "",
    ]
    lines.extend(f"- `{row['status']}` `{row['path']}` — {row['category']}" for row in report["relevantChanges"])
    if not report["relevantChanges"]:
        lines.append("- None.")
    lines += ["", "Read-only watcher: no source update, pin change, branch mutation, or pull request is automatic.", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pin", default="docs/upstream/SDROXIDE.json")
    parser.add_argument("--fixture")
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
        print(f"SDRoxide upstream comparison failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
