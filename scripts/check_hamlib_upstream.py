#!/usr/bin/env python3
"""Verify the immutable Hamlib source package and optionally check upstream."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "core" / "third_party" / "hamlib"
MANIFEST = ROOT / "docs" / "radio" / "hamlib" / "SOURCE_MANIFEST.json"
UPSTREAM = ROOT / "docs" / "radio" / "hamlib" / "UPSTREAM.json"
VERSION = "4.7.2"
COMMIT = "40f63488fe0bd751b147f48d62fd217bf53713a0"
TREE = "56a42afe2ace9dd1b43729168bb73ca46a812848"
ARCHIVE_SHA256 = "ae1fcf2dbc80ea0786ea8f047b09399c3f7737d1930442f61a031708ed33e88f"
LICENCE_DIGESTS = {
    "COPYING": "8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643",
    "COPYING.LIB": "dc626520dcd53a22f727af3ee42c770e56c97a64fe3adb063799d8ab032fe551",
    "LICENSE": "5d126027f62cc1fe3b167aab65cbad384360e56d1163e0ab9fd9e64f6aa4abad",
    "AUTHORS": "659d97512f2858dc04315eac08c47e8fb3cd8f03a530f7d00c7b74d3d8821890",
    "README": "be2ac7d9094854e20bf24dabcb3b4456023db2cb17cae2354f5576caf972c513",
}


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def source_files() -> list[pathlib.Path]:
    # pathlib orders WindowsPath values case-insensitively, while PosixPath is
    # case-sensitive. Sort by the recorded portable path so one manifest is
    # stable across both host families.
    return sorted(
        (path for path in SOURCE.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(SOURCE).as_posix(),
    )


def document() -> dict[str, object]:
    files = [
        {
            "path": path.relative_to(SOURCE).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": digest(path),
        }
        for path in source_files()
    ]
    return {
        "schema": 1,
        "source": "Hamlib",
        "version": VERSION,
        "commit": COMMIT,
        "tree": TREE,
        "archive_sha256": ARCHIVE_SHA256,
        "modifications": ["src/iofunc.c", "src/microham.c", "README.android"],
        "file_count": len(files),
        "files": files,
    }


def verify_manifest() -> list[str]:
    errors: list[str] = []
    if not MANIFEST.is_file():
        return [f"missing {MANIFEST.relative_to(ROOT)}"]
    recorded = json.loads(MANIFEST.read_text(encoding="utf-8"))
    actual = document()
    if recorded != actual:
        errors.append("SOURCE_MANIFEST.json does not match the vendored source")
    return errors


def verify_source() -> list[str]:
    errors: list[str] = []
    metadata = json.loads(UPSTREAM.read_text(encoding="utf-8"))
    if metadata.get("selected_release") != VERSION or metadata.get("commit") != COMMIT:
        errors.append("UPSTREAM.json pin mismatch")
    for name, expected in LICENCE_DIGESTS.items():
        path = SOURCE / name
        if not path.is_file() or digest(path) != expected:
            errors.append(f"licence/provenance digest mismatch: {name}")
    riglist = (SOURCE / "include" / "hamlib" / "riglist.h").read_text(encoding="utf-8")
    if "RIG_MODEL_QRPLABS_QMX" not in riglist:
        errors.append("QMX model definition is absent")
    if re.search(r"RIG_MODEL_RGO(?:_|\b)", riglist, re.IGNORECASE):
        errors.append("unexpected dedicated RGO model requires explicit review")
    configure = (SOURCE / "configure.ac").read_text(encoding="utf-8")
    match = re.search(r'^RIG_BACKEND_LIST="([^"]+)"', configure, re.MULTILINE)
    if not match or len(match.group(1).split()) != 37:
        errors.append("radio backend registry count is not the reviewed value 37")
    return errors


def check_latest() -> list[str]:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "ShackCQ-Hamlib-Watcher",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        "https://api.github.com/repos/Hamlib/Hamlib/releases/latest",
        headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            latest = json.load(response).get("tag_name", "")
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
        return [f"unable to check latest stable release: {error}"]
    if not latest:
        return ["latest stable release response omitted tag_name"]
    if latest != VERSION:
        return [f"new stable release requires review: selected={VERSION} latest={latest}"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-manifest", action="store_true")
    parser.add_argument("--check-latest", action="store_true")
    parser.add_argument("--archive", type=pathlib.Path)
    args = parser.parse_args()

    if args.write_manifest:
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(json.dumps(document(), indent=2) + "\n", encoding="utf-8")

    errors = verify_source() + verify_manifest()
    if args.archive and digest(args.archive) != ARCHIVE_SHA256:
        errors.append("release archive SHA-256 mismatch")
    if args.check_latest:
        errors += check_latest()
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    print(f"PASS Hamlib {VERSION} commit={COMMIT} files={manifest['file_count']} backends=37 QMX=yes RGO=no")
    return 0


if __name__ == "__main__":
    sys.exit(main())
