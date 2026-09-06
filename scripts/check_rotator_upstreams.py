#!/usr/bin/env python3
"""Read-only watcher for the reviewed Rotator Platform upstreams."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

PIN = {
    "radio_station_pro": {
        "commit": "e3df89c4bec2eed3e56570a538ea881cf9b203be",
        "tree": "42c23a547a90f9b0b798d4488cf61446b8e4e9a4",
        "reviewed_tree_manifest_sha256": "e93798268ed56d9bcda159c6b310283ca5c7d89fb9f208d50212ef9a5436fdf1",
    },
    "microham": {
        "manual_url": "https://www.microham.com/Downloads/ARCO_English_Manual.pdf",
        "manual_sha256": "daa5084ac5034c65b0bfb5f19a0e339ac26429624d511043ec186d21e8bd17b4",
        "manual_version": "4.1",
        "manual_date": "2024-12-10",
        "firmware_version": "4.2.B",
        "firmware_date": "2025-01-03",
        "firmware_sha256": "541649ed3c429205d7054d6c2cb6fc9f09ba2448aa72132d697671b8714589fc",
        "history_sha256": "69dad8ea98ebde86f86276812d6213b3f7fa598d944acf39e5291bbcea957e92",
    },
    "hamlib": {
        "tag": "4.7.2",
        "commit": "40f63488fe0bd751b147f48d62fd217bf53713a0",
        "tree": "56a42afe2ace9dd1b43729168bb73ca46a812848",
        "rotlist_sha256": "c6726ef1cebedf12667811d66794794348b35f3fea4e380cd74b3c09eb5e4f2b",
        "rotctld_sha256": "f829177a3a90a247dc78b6bacd8e30aa7219169e9450a431e603c9c3571b4883",
        "licence_sha256": "dc626520dcd53a22f727af3ee42c770e56c97a64fe3adb063799d8ab032fe551",
    },
}


def fetch(url: str, *, github: bool = False) -> bytes:
    headers = {"User-Agent": "ShackCQ-Rotator-Upstream-Watch/1"}
    token = os.environ.get("ROTATOR_UPSTREAM_TOKEN") or os.environ.get("GH_TOKEN")
    if github and token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=20) as response:
        return response.read()


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def github_json(path: str) -> dict:
    return json.loads(fetch(f"https://api.github.com{path}", github=True))


def check_radio_station_pro() -> dict:
    pin = PIN["radio_station_pro"]
    try:
        commit = github_json(f"/repos/oliverbross/radio-station-pro/git/commits/{pin['commit']}")
        tree = commit["tree"]["sha"]
        return {"status": "NO_CHANGE" if tree == pin["tree"] else "REVIEW_REQUIRED", "commit": pin["commit"],
                "tree": tree, "expected_tree": pin["tree"], "classification": ["DOCUMENTATION", "SAFETY", "AUTOMATION"]}
    except (urllib.error.URLError, urllib.error.HTTPError, KeyError, ValueError) as failure:
        return {"status": "UNAVAILABLE", "reason": f"private source unavailable: {type(failure).__name__}",
                "classification": ["DOCUMENTATION"]}


def check_microham() -> dict:
    pin = PIN["microham"]
    result = {"status": "NO_CHANGE", "classification": []}
    try:
        product = fetch("https://www.microham.com/contents/en-us/d50_ARCO.html")
        downloads = fetch("https://www.microham.com/contents/en-us/d29_downloads.html")
        manual = fetch(pin["manual_url"])
        firmware = fetch("https://www.microham.com/Downloads/ARCO.upd")
        history = fetch("https://www.microham.com/Downloads/arco_change_log.txt")
        first = history.decode("utf-8", "replace").splitlines()[4:12]
        version = next((m.group(1) for line in first if (m := re.search(r"v([0-9.]+[A-Z]?)", line))), "UNKNOWN")
        result.update({"manual_sha256": sha256(manual), "firmware_sha256": sha256(firmware),
                       "history_sha256": sha256(history), "firmware_version": version,
                       "product_page_sha256": sha256(product), "downloads_page_sha256": sha256(downloads)})
        if result["manual_sha256"] != pin["manual_sha256"]:
            result["classification"].extend(["ARCO_PROTOCOL", "SAFETY", "DOCUMENTATION"])
        if result["firmware_sha256"] != pin["firmware_sha256"] or version != pin["firmware_version"]:
            result["classification"].append("ARCO_FIRMWARE")
        if result["history_sha256"] != pin["history_sha256"]:
            result["classification"].append("DOCUMENTATION")
        if result["classification"]:
            result["status"] = "REVIEW_REQUIRED"
    except (urllib.error.URLError, urllib.error.HTTPError, ValueError) as failure:
        result = {"status": "UNAVAILABLE", "reason": f"official source unavailable: {type(failure).__name__}",
                  "classification": ["ARCO_PROTOCOL", "ARCO_FIRMWARE", "DOCUMENTATION"]}
    return result


def check_hamlib() -> dict:
    pin = PIN["hamlib"]
    try:
        ref = github_json(f"/repos/Hamlib/Hamlib/git/ref/tags/{pin['tag']}")
        obj = ref["object"]
        if obj["type"] == "tag":
            obj = github_json(f"/repos/Hamlib/Hamlib/git/tags/{obj['sha']}")["object"]
        commit = github_json(f"/repos/Hamlib/Hamlib/git/commits/{obj['sha']}")
        current_commit, current_tree = commit["sha"], commit["tree"]["sha"]
        status = "NO_CHANGE" if current_commit == pin["commit"] and current_tree == pin["tree"] else "REVIEW_REQUIRED"
        return {"status": status, "tag": pin["tag"], "commit": current_commit, "tree": current_tree,
                "classification": [] if status == "NO_CHANGE" else ["ROTATOR_MODEL", "HAMLIB_API", "LICENCE"]}
    except (urllib.error.URLError, urllib.error.HTTPError, KeyError, ValueError) as failure:
        return {"status": "UNAVAILABLE", "reason": f"Hamlib source unavailable: {type(failure).__name__}",
                "classification": ["HAMLIB_API", "ROTATOR_MODEL", "LICENCE"]}


def markdown(report: dict) -> str:
    lines = ["# Rotator upstream watch", ""]
    for name, item in report["sources"].items():
        lines += [f"## {name}", f"- Result: **{item['status']}**"]
        for key in ("commit", "tree", "manual_sha256", "firmware_version", "firmware_sha256", "reason"):
            if key in item:
                lines.append(f"- {key.replace('_', ' ').title()}: `{item[key]}`")
        if item.get("classification"):
            lines.append(f"- Review classes: {', '.join(sorted(set(item['classification']))) }")
        lines.append("")
    lines.append("This watcher is read-only. It never changes production source or opens a pull request.")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args()
    sources = {"radio-station-pro": check_radio_station_pro(), "microHAM ARCO": check_microham(), "Hamlib": check_hamlib()}
    report = {"schema": 1, "sources": sources}
    print(json.dumps(report, indent=2, sort_keys=True) if args.format == "json" else markdown(report))
    return 0 if all(value["status"] == "NO_CHANGE" for value in sources.values()) else 2


if __name__ == "__main__":
    sys.exit(main())
