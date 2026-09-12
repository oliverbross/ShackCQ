#!/usr/bin/env python3
"""Fail closed unless every Mach-O in a composed Nexus Desktop app is portable."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import plistlib
import re
import subprocess
from pathlib import Path


SYSTEM_PREFIXES = ("/System/Library/", "/usr/lib/")


def output(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def is_macho(path: Path) -> bool:
    return "Mach-O" in output("file", "-b", str(path))


def dependencies(path: Path) -> list[str]:
    lines = output("otool", "-L", str(path)).splitlines()[1:]
    return [line.strip().split(" (compatibility", 1)[0] for line in lines]


def install_id(path: Path) -> str | None:
    text = output("otool", "-D", str(path)).splitlines()
    return text[1].strip() if len(text) > 1 else None


def normalized_id(app: Path, path: Path) -> str | None:
    frameworks = app / "Contents" / "Frameworks"
    try:
        relative = path.relative_to(frameworks)
    except ValueError:
        return None
    parts = relative.parts
    if len(parts) >= 4 and parts[0].endswith(".framework") and parts[1:3] == ("Versions", "A"):
        return "@rpath/" + "/".join(parts[:4])
    if len(parts) == 1 and path.suffix == ".dylib":
        return "@rpath/" + path.name
    return None


def resolve(app: Path, owner: Path, dependency: str) -> Path | None:
    if dependency.startswith(SYSTEM_PREFIXES):
        return Path(dependency)
    if dependency.startswith("@loader_path/"):
        return owner.resolve().parent / dependency.removeprefix("@loader_path/")
    if dependency.startswith("@executable_path/"):
        return app / "Contents" / "MacOS" / dependency.removeprefix("@executable_path/")
    if dependency.startswith("@rpath/"):
        return app / "Contents" / "Frameworks" / dependency.removeprefix("@rpath/")
    if dependency.startswith("/"):
        return Path(dependency)
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("app", type=Path)
    parser.add_argument("--repair-install-ids", action="store_true")
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    app = args.app.resolve()
    required = [
        app / "Contents" / "MacOS" / "shackcq-desktop",
        app / "Contents" / "MacOS" / "shackcq-nexus-runtime",
        app / "Contents" / "MacOS" / "shackcq-stationd",
        app / "Contents" / "MacOS" / "shackcq-hamlib-helper",
    ]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"required executable missing: {path}")

    machos = sorted(
        path for path in app.rglob("*")
        if path.is_file() and not path.is_symlink() and is_macho(path)
    )
    if args.repair_install_ids:
        for path in machos:
            architectures = output("lipo", "-archs", str(path)).split()
            if architectures != ["arm64"]:
                temporary = path.with_name(path.name + ".arm64")
                subprocess.check_call(
                    ["lipo", str(path), "-thin", "arm64", "-output", str(temporary)]
                )
                os.chmod(temporary, path.stat().st_mode)
                os.replace(temporary, path)
        for path in machos:
            wanted = normalized_id(app, path)
            current = install_id(path)
            if wanted and current != wanted:
                subprocess.check_call(["install_name_tool", "-id", wanted, str(path)])

    failures: list[str] = []
    absolute_local = re.compile(r"^/(?:opt/homebrew|usr/local|Users)/")
    for path in machos:
        architectures = output("lipo", "-archs", str(path)).split()
        if architectures != ["arm64"]:
            failures.append(
                f"unexpected architecture: {path.relative_to(app)} -> {' '.join(architectures)}"
            )
        load_commands = output("otool", "-l", str(path))
        minimums = re.findall(r"\bminos\s+([0-9.]+)", load_commands)
        if not minimums or any(tuple(map(int, value.split("."))) > (13, 0) for value in minimums):
            failures.append(
                f"minimum macOS exceeds 13.0 or is absent: {path.relative_to(app)} -> {minimums}"
            )
        current_id = install_id(path)
        if current_id and absolute_local.match(current_id):
            failures.append(f"local install id: {path.relative_to(app)} -> {current_id}")
        for dependency in dependencies(path):
            if dependency == current_id:
                continue
            # Modern macOS keeps many system libraries only in the dyld shared
            # cache, so their filesystem path is intentionally not required.
            if dependency.startswith(SYSTEM_PREFIXES):
                continue
            if absolute_local.match(dependency):
                failures.append(f"local dependency: {path.relative_to(app)} -> {dependency}")
                continue
            target = resolve(app, path, dependency)
            if target is None or not target.exists():
                failures.append(f"unresolved dependency: {path.relative_to(app)} -> {dependency}")
    if failures:
        raise SystemExit("\n".join(failures))
    if args.manifest:
        repo = Path(__file__).resolve().parent.parent
        info = plistlib.loads((app / "Contents" / "Info.plist").read_bytes())
        components = {}
        for path in required:
            components[path.name] = {
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "architecture": "arm64",
            }
        shared = json.loads((repo / "desktop" / "shared-digi-ui-manifest.json").read_text())
        manifest = {
            "schemaVersion": 1,
            "product": info.get("CFBundleName", "ShackCQ Desktop"),
            "bundleIdentifier": info["CFBundleIdentifier"],
            "bundleVersion": info["CFBundleShortVersionString"],
            "minimumMacOS": "13.0",
            "sourceCommit": output("git", "-C", str(repo), "rev-parse", "HEAD").strip(),
            "nexusCommit": output("git", "-C", str(repo / "third_party" / "nexus"), "rev-parse", "HEAD").strip(),
            "sharedDigiWebCommit": shared["webCommit"],
            "components": components,
            "machOCount": len(machos),
            "loaderClosure": "VERIFIED",
            "signing": "AD_HOC_ONLY",
            "notarization": "NOT_PERFORMED",
        }
        args.manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"MACOS_BUNDLE_CLOSURE_OK machos={len(machos)} required_roots={len(required)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
