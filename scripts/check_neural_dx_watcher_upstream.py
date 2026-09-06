#!/usr/bin/env python3
"""Read-only Neural-DX-Watcher commit comparison; never imports upstream code."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

REVIEWED = "fe3cba8ed9c0502f5dabdb2f64ebd990de986559"
API = "https://api.github.com/repos/F1SMV/Neural-DX-Watcher/commits/main"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pin", default=REVIEWED)
    args = parser.parse_args()
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-upstream-watch",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(API, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            current = json.load(response)["sha"]
    except (urllib.error.URLError, KeyError, ValueError) as error:
        print(f"Neural-DX-Watcher comparison unavailable: {error}", file=sys.stderr)
        return 3
    print("# Neural-DX-Watcher upstream watch")
    print(f"- Reviewed behavioural pin: `{args.pin}`")
    print(f"- Current main: `{current}`")
    if current == args.pin:
        print("- Result: **NO CHANGE**")
        return 0
    print("- Result: **REVIEW REQUIRED**")
    print("- Licence/permission remains unresolved; do not copy or absorb upstream implementation.")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
