#!/usr/bin/env python3
"""Create deterministic exact-SHA source, manifest, SBOM and digest artifacts."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = {"qso": 16, "neural": 5, "contest": 2, "digi": 2, "groups_io": 2, "dx_chaser": 1}
EXPECTED_NAMES = {
    "android_apk": "ShackCQ-Android-arm64-v0.1.0-rc.1.apk",
    "android_aab": "ShackCQ-Android-four-ABI-v0.1.0-rc.1.aab",
    "ios_simulator": "ShackCQ-iOS-Simulator-v0.1.0-rc.1.zip",
    "ios_xcarchive": "ShackCQ-iOS-unsigned-XCArchive-v0.1.0-rc.1.zip",
    "windows_zip": "ShackCQ-Windows-x64-portable-v0.1.0-rc.1.zip",
    "windows_setup": "ShackCQ-Windows-x64-setup-v0.1.0-rc.1.exe",
    "macos_zip": "ShackCQ-macOS-arm64-unsigned-v0.1.0-rc.1.zip",
    "linux_tar": "ShackCQ-Linux-x86_64-v0.1.0-rc.1.tar.gz",
    "linux_deb": "ShackCQ-Linux-x86_64-v0.1.0-rc.1.deb",
    "stationd_x64": "ShackCQ-stationd-Linux-x86_64-v0.1.0-rc.1.tar.gz",
    "stationd_arm64": "ShackCQ-stationd-Linux-aarch64-v0.1.0-rc.1.tar.gz",
    "source": "ShackCQ-source-v0.1.0-rc.1.tar.gz",
}
SIZE_CEILINGS = {
    "android_apk": 130 * 1024 * 1024, "android_aab": 60 * 1024 * 1024,
    "windows_zip": 150 * 1024 * 1024, "windows_setup": 110 * 1024 * 1024,
    "macos_zip": 200 * 1024 * 1024, "linux_tar": 180 * 1024 * 1024,
    "linux_deb": 150 * 1024 * 1024, "source": 100 * 1024 * 1024,
}


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "build/rc1/distribution")
    parser.add_argument("--channel", default="RC")
    parser.add_argument("--build-utc")
    parser.add_argument("--collect", action="append", default=[], metavar="KIND=PATH")
    parser.add_argument("--allow-dirty", action="store_true")
    args = parser.parse_args()

    sha = git("rev-parse", "HEAD")
    if not args.allow_dirty and git("status", "--porcelain"):
        raise SystemExit("distribution requires a clean exact-SHA worktree")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    build_utc = args.build_utc or datetime.now(timezone.utc).replace(microsecond=0).isoformat()

    source_name = EXPECTED_NAMES["source"].format(sha=sha)
    subprocess.check_call(
        ["git", "archive", "--format=tar.gz", "--prefix=ShackCQ-0.1.0-rc.1/", "-o", str(output / source_name), sha],
        cwd=ROOT,
    )
    if (output / source_name).stat().st_size > SIZE_CEILINGS["source"]:
        raise SystemExit(f"artifact exceeds size gate: {source_name}")

    files = []
    for name in git("ls-tree", "-r", "--name-only", sha).splitlines():
        blob = subprocess.check_output(["git", "show", f"{sha}:{name}"], cwd=ROOT)
        files.append({"path": name, "sha256": hashlib.sha256(blob).hexdigest(), "bytes": len(blob)})
    manifest = {
        "contract": "SHACKCQ_SOURCE_MANIFEST_V1",
        "sha": sha,
        "channel": args.channel,
        "build_utc": build_utc,
        "schemas": SCHEMAS,
        "files": files,
    }
    (output / "SOURCE_MANIFEST.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    packages = [
        ("ShackCQ", "NOASSERTION"), ("Hamlib", "4.7.2"), ("SDRoxide", "vendored-record"),
        ("mfsk-core", "vendored"), ("tempo-sstv", "vendored"), ("SGP4", "vendored"),
        ("ITUHFProp", "vendored"), ("CTY", "data-snapshot"), ("BandPlans", "data-snapshot"),
        ("Xiph.Org Opus", "1.5.2-ddbe48383984d56acd9e1ab6a090c54ca6b735a6"),
        ("libsecret", "system-dynamic"),
    ]
    sbom = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"ShackCQ-{sha}",
        "documentNamespace": f"https://shackcq.app/spdx/{sha}",
        "creationInfo": {"created": build_utc, "creators": ["Tool: scripts/prepare_rc1_distribution.py"]},
        "packages": [
            {"name": name, "SPDXID": f"SPDXRef-Package-{index}", "versionInfo": version,
             "downloadLocation": "NOASSERTION", "filesAnalyzed": False,
             "licenseConcluded": "NOASSERTION", "licenseDeclared": "NOASSERTION"}
            for index, (name, version) in enumerate(packages)
        ],
    }
    (output / "SBOM.spdx.json").write_text(json.dumps(sbom, indent=2, sort_keys=True) + "\n")

    collected = {}
    for item in args.collect:
        kind, separator, raw_path = item.partition("=")
        if not separator or kind not in EXPECTED_NAMES or kind == "source":
            raise SystemExit(f"invalid --collect value: {item}")
        source = Path(raw_path).resolve()
        if not source.is_file():
            raise SystemExit(f"artifact not found: {source}")
        destination = output / EXPECTED_NAMES[kind].format(sha=sha)
        shutil.copy2(source, destination)
        ceiling = SIZE_CEILINGS.get(kind)
        if ceiling and destination.stat().st_size > ceiling:
            raise SystemExit(f"artifact exceeds size gate: {destination.name}")
        collected[kind] = destination.name

    build = {
        "contract": "SHACKCQ_RC1_BUILD_MANIFEST_V1", "sha": sha, "channel": args.channel,
        "build_utc": build_utc, "schemas": SCHEMAS,
        "platforms": ["Android", "iPhone/iPad", "Windows", "macOS", "Linux x86_64", "Linux arm64 stationd"],
        "expected_artifacts": {key: value.format(sha=sha) for key, value in EXPECTED_NAMES.items()},
        "collected_artifacts": collected,
    }
    (output / "BUILD_MANIFEST.json").write_text(json.dumps(build, indent=2, sort_keys=True) + "\n")
    shutil.copy2(ROOT / "NOTICE", output / "THIRD_PARTY_NOTICES.txt")
    shutil.copy2(ROOT / "docs/release/SHACKCQ_V0_1_0_RC1.md", output / "RELEASE_NOTES.md")

    artifacts = sorted(path for path in output.iterdir() if path.is_file() and path.name != "SHA256SUMS.txt")
    sums = "".join(f"{digest(path)}  {path.name}\n" for path in artifacts)
    (output / "SHA256SUMS.txt").write_text(sums)
    print(json.dumps({"sha": sha, "output": str(output), "artifacts": [path.name for path in artifacts]}, indent=2))


if __name__ == "__main__":
    main()
