#!/usr/bin/env python3
"""Read-only watcher for the reviewed official RGO ONE documentation set."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / "docs/radio/rgoone/UPSTREAM.json"
MAXIMUM_BYTES = 8 * 1024 * 1024
ALLOWED_HOSTS = {"lz2jr.com", "www.lz2jr.com"}
USER_AGENT = "ShackCQ-RGO-One-Official-Watch/1"


def validate_url(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_HOSTS or parsed.username or parsed.password:
        raise ValueError("source URL is outside the bounded official RGO ONE origin")
    if not (parsed.path.startswith("/blog/") or parsed.path == "/"):
        raise ValueError("source URL is outside the reviewed RGO ONE paths")
    if parsed.path.lower().endswith((".rar", ".zip", ".dfu", ".hex", ".bin")):
        raise ValueError("firmware/archive downloads are forbidden")


def fetch(source: dict[str, Any]) -> dict[str, Any]:
    url = source.get("watch_url", source["url"])
    validate_url(url)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read(MAXIMUM_BYTES + 1)
            if len(body) > MAXIMUM_BYTES:
                raise OSError("source exceeds bounded download limit")
            return {
                "id": source["id"],
                "url": source["url"],
                "watch_url": response.geturl(),
                "status": "AVAILABLE",
                "sha256": hashlib.sha256(body).hexdigest(),
                "bytes": len(body),
                "etag": response.headers.get("ETag"),
                "last_modified": response.headers.get("Last-Modified"),
            }
    except (OSError, urllib.error.URLError, ValueError) as error:
        return {"id": source["id"], "url": source["url"], "watch_url": url, "status": "UNAVAILABLE", "error": str(error)}


def compare(pinned: list[dict[str, Any]], observed: list[dict[str, Any]]) -> dict[str, Any]:
    pins = {item["id"]: item for item in pinned}
    records = []
    for item in observed:
        expected = pins[item["id"]]
        if item["status"] == "UNAVAILABLE":
            result = "UNAVAILABLE"
        elif item["sha256"] == expected.get("watch_sha256", expected["sha256"]):
            result = "CURRENT"
        else:
            result = "CHANGED"
        records.append({**item, "expected_sha256": expected.get("watch_sha256", expected["sha256"]), "result": result})
    overall = "CURRENT" if all(item["result"] == "CURRENT" for item in records) else "REVIEW_REQUIRED"
    return {"schema_version": 1, "result": overall, "review_required": overall != "CURRENT", "records": records}


def latest_firmware(body: bytes) -> str | None:
    versions = {(int(a), int(b)) for a, b in re.findall(rb"(?:FW\s+(?:version|ver\.)\s*)?(\d)\.(\d{2})", body, re.IGNORECASE)}
    return "%d.%02d" % max(versions) if versions else None


def markdown(result: dict[str, Any]) -> str:
    lines = ["# RGO ONE official-source watch", "", f"**Result: {result['result']}**", "",
        "This report is read-only. It does not download firmware or modify production source.", ""]
    for item in result["records"]:
        lines.append(f"- `{item['id']}`: **{item['result']}**")
        lines.append(f"  - URL: {item['url']}")
        if item.get("sha256"):
            lines.append(f"  - Observed SHA-256: `{item['sha256']}`")
        if item.get("last_modified"):
            lines.append(f"  - Last-Modified: {item['last_modified']}")
        if item.get("error"):
            lines.append(f"  - Availability error: {item['error']}")
    return "\n".join(lines) + "\n"


def write_report(output: Path, result: dict[str, Any]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    (output / "rgo-one-upstream.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output / "rgo-one-upstream.md").write_text(markdown(result), encoding="utf-8")


def self_test() -> None:
    pin = [{"id": "manual", "sha256": "a" * 64}]
    current = compare(pin, [{"id": "manual", "url": "https://lz2jr.com/blog/manual.pdf", "status": "AVAILABLE", "sha256": "a" * 64}])
    changed = compare(pin, [{"id": "manual", "url": "https://lz2jr.com/blog/manual.pdf", "status": "AVAILABLE", "sha256": "b" * 64}])
    unavailable = compare(pin, [{"id": "manual", "url": "https://lz2jr.com/blog/manual.pdf", "status": "UNAVAILABLE", "error": "offline"}])
    assert current["result"] == "CURRENT"
    assert changed["records"][0]["result"] == "CHANGED"
    assert unavailable["records"][0]["result"] == "UNAVAILABLE"
    assert latest_firmware(b"FW version 1.08 notes; FW version 1.09 notes") == "1.09"
    try:
        validate_url("https://lz2jr.com/blog/wp-content/uploads/v1.09.rar")
        raise AssertionError("firmware archive URL accepted")
    except ValueError:
        pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/reports")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("RGO ONE watcher self-test: PASS")
        return 0
    try:
        pin = json.loads(PIN.read_text(encoding="utf-8"))
        watched = [source for source in pin["sources"] if source.get("watch")]
        result = compare(watched, [fetch(source) for source in watched])
        result["expected_latest_firmware"] = pin["latest_firmware"]
        write_report(args.output_dir, result)
        print(markdown(result), end="")
        return 2 if result["review_required"] else 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        result = {"schema_version": 1, "result": "WATCH_FAILED", "review_required": True, "records": [], "error": str(error)}
        try:
            write_report(args.output_dir, result)
        except OSError:
            pass
        print(f"RGO ONE watcher failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
