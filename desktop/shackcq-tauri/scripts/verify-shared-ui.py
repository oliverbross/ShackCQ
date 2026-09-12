#!/usr/bin/env python3
"""Reject an absent, unpinned, or modified orchestrator-provided UI snapshot."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "shared-digi-ui-manifest.json"
SNAPSHOT = ROOT / "shared-digi-ui-snapshot"

record = json.loads(MANIFEST.read_text(encoding="utf-8"))
if record.get("webCommit") in (None, "", "ORCHESTRATOR_REQUIRED"):
    raise SystemExit("shared Digi UI final Web SHA is not integrated")
if not (SNAPSHOT / "index.html").is_file():
    raise SystemExit("shared Digi UI snapshot is absent")
digest = hashlib.sha256()
for path in sorted(p for p in SNAPSHOT.rglob("*") if p.is_file()):
    digest.update(path.relative_to(SNAPSHOT).as_posix().encode())
    digest.update(b"\0")
    digest.update(path.read_bytes())
if digest.hexdigest() != record.get("contentSha256"):
    raise SystemExit("shared Digi UI snapshot digest does not match manifest")
print(f"shared Digi UI verified at {record['webCommit']}")
