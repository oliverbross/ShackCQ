#!/usr/bin/env python3
"""Read-only Nexus upstream comparison. Never edits the reviewed pin."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

API_ROOT = "https://api.github.com/repos/kd9taw/Nexus"


def request_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-Nexus-Upstream-Watch/1",
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
        except (urllib.error.HTTPError, urllib.error.URLError) as error:
            last_error = error
            if isinstance(error, urllib.error.HTTPError) and error.code < 500:
                raise
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError(f"request failed: {last_error}")


def content(commit: str, path: str) -> bytes:
    quoted = urllib.parse.quote(path, safe="/")
    value = request_json(f"{API_ROOT}/contents/{quoted}?ref={commit}")
    if value.get("encoding") != "base64":
        raise ValueError(f"unsupported content encoding for {path}")
    return base64.b64decode(value["content"])


def tracked(path: str, prefixes: list[str]) -> bool:
    return any(path == prefix.rstrip("/") or path.startswith(prefix) for prefix in prefixes)


def category(path: str) -> str:
    if path in {"COPYING", "NOTICE"}: return "LICENCE_PROVENANCE"
    if path.startswith("docs/") or path == "CHANGELOG.md": return "DOCUMENTATION"
    if "/rtty/" in path or "/psk/" in path or path.endswith("textmode.rs"): return "MODEM_DSP"
    if "Waterfall" in path or path.startswith("crates/tempo-audio/"): return "WATERFALL_AUDIO"
    if "logbook" in path.lower() or "Qso" in path: return "LOGGING"
    if "settings" in path.lower() or "SetupHealth" in path: return "SETTINGS_ACCESSIBILITY"
    if path.startswith("ui/") or path.startswith("crates/tempo-app/"): return "DIGITAL_WORKFLOW"
    return "INTEROPERABILITY"


def compare(pin: dict[str, Any]) -> dict[str, Any]:
    repository = request_json(API_ROOT)
    branch = str(repository["default_branch"])
    head = request_json(f"{API_ROOT}/commits/{urllib.parse.quote(branch)}")
    commit = str(head["sha"])
    tree = str(head["commit"]["tree"]["sha"])
    package = json.loads(content(commit, "ui/package.json"))["version"]
    copying = hashlib.sha256(content(commit, "COPYING")).hexdigest()
    notice = hashlib.sha256(content(commit, "NOTICE")).hexdigest()
    files: list[dict[str, str]] = []
    if commit != pin["reviewedCommit"]:
        delta = request_json(f"{API_ROOT}/compare/{pin['reviewedCommit']}...{commit}")
        for item in delta.get("files", []):
            path = str(item["filename"])
            if tracked(path, list(pin["trackedPaths"])):
                files.append({"path": path, "status": str(item["status"]), "category": category(path)})
    review = (
        commit != pin["reviewedCommit"] or tree != pin["reviewedTree"] or
        package != pin["packageVersion"] or copying != pin["copyingSha256"] or
        notice != pin["noticeSha256"] or branch != pin["branch"]
    )
    return {
        "result": "REVIEW_REQUIRED" if review else "NO_CHANGE",
        "reviewed": {"branch": pin["branch"], "commit": pin["reviewedCommit"], "tree": pin["reviewedTree"], "packageVersion": pin["packageVersion"]},
        "current": {"branch": branch, "commit": commit, "tree": tree, "packageVersion": package, "copyingSha256": copying, "noticeSha256": notice},
        "relevantChanges": files,
    }


def markdown(report: dict[str, Any]) -> str:
    current, reviewed = report["current"], report["reviewed"]
    lines = [
        "# Nexus upstream watch", "",
        f"- Reviewed: `{reviewed['branch']}` / `{reviewed['commit']}` / package `{reviewed['packageVersion']}`",
        f"- Current: `{current['branch']}` / `{current['commit']}` / package `{current['packageVersion']}`",
        f"- Result: **{report['result'].replace('_', ' ')}**", "",
        "## Relevant path changes", "",
    ]
    changes = report["relevantChanges"]
    lines.extend(f"- `{item['status']}` `{item['path']}` — {item['category']}" for item in changes)
    if not changes:
        lines.append("- None.")
    lines += ["", "The watcher is read-only and never updates the reviewed pin or product source.", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pin", default="docs/nexus/UPSTREAM.json")
    parser.add_argument("--markdown-output")
    parser.add_argument("--json-output")
    args = parser.parse_args()
    try:
        pin = json.loads(Path(args.pin).read_text(encoding="utf-8"))
        report = compare(pin)
        rendered = markdown(report)
        if args.markdown_output:
            Path(args.markdown_output).write_text(rendered, encoding="utf-8")
        if args.json_output:
            Path(args.json_output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(rendered)
        return 2 if report["result"] == "REVIEW_REQUIRED" else 0
    except (KeyError, ValueError, OSError, json.JSONDecodeError, urllib.error.URLError) as error:
        print(f"Nexus upstream comparison failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
