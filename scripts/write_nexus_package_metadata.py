#!/usr/bin/env python3
"""Write immutable, non-secret Nexus Desktop package capability metadata."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--web", required=True)
    parser.add_argument("--frontend-content-sha", required=True)
    parser.add_argument("--nexus", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--qt", required=True)
    parser.add_argument("--rust", required=True)
    parser.add_argument("--tauri-cli", required=True)
    args = parser.parse_args()
    is_macos = args.platform == "macos-arm64"
    payload = {
        "schemaVersion": 1,
        "product": "ShackCQ Desktop",
        "bundleVersion": "0.2.0",
        "platform": args.platform,
        "sourceCommit": args.source,
        "sharedDigiWebCommit": args.web,
        "sharedDigiFrontendContentSha256": args.frontend_content_sha,
        "nexusCommit": args.nexus,
        "qtVersion": args.qt,
        "rustToolchain": args.rust,
        "tauriCliVersion": args.tauri_cli,
        "platformBaseline": {
            "macos-arm64": "macOS 13 arm64",
            "windows-x64": "Windows 10 x86_64 GNU",
            "linux-x86_64": "Ubuntu 24.04 x86_64 glibc 2.39",
        }.get(args.platform, "TEST_ONLY"),
        "icuLicenseCoverage": ["73", "74"],
        "legalSources": {
            "qt": f"qtbase-v{args.qt}/LICENSES/LGPL-3.0-only.txt",
            "icu": ["release-73-2/icu4c/LICENSE", "release-74-2/LICENSE"],
        },
        "packageScope": "TAURI_DIGI_DESKTOP_WITH_OWNED_NATIVE_INGRESS_AGENT_AND_HAMLIB_HELPER",
        "components": [
            "shackcq-desktop",
            "shackcq-nexus-runtime",
            "shackcq-stationd",
            "shackcq-hamlib-helper",
        ],
        "rxModes": ["FT8", "FT4", "FT2", "FST4", "FST4W", "Q65", "MSK144", "JT65", "WSPR"],
        "recordingDecode": "LOCAL_ONLY",
        "browserLocalBridge": "DISABLED_UNTIL_EXPLICIT_PAIRING",
        "canonicalLogbook": "BUNDLED_AGENT_NATIVE_INGRESS",
        "transmit": {
            "encoderCallableWithoutPhysicalOutput": False,
            "automaticSequenceImplemented": False,
            "physicalAudioOutputAvailable": False,
            "productionEnabled": False,
        },
        "signing": "AD_HOC_ONLY" if is_macos else "UNSIGNED",
        "notarization": "NOT_PERFORMED" if is_macos else "NOT_APPLICABLE",
        "hardwareAcceptance": "NOT_PERFORMED",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
