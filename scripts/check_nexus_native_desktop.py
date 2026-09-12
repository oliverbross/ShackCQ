#!/usr/bin/env python3
"""Verify the immutable Nexus desktop source and its exclusion boundary."""
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UP = ROOT / "third_party/nexus"
RECORD = json.loads((ROOT / "docs/nexus/NATIVE_DESKTOP_UPSTREAM.json").read_text())

def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", str(UP), *args], text=True).strip()

assert git("rev-parse", "HEAD") == RECORD["commit"]
assert git("rev-parse", "HEAD^{tree}") == RECORD["tree"]
assert git("describe", "--tags", "--exact-match") == RECORD["release"]
for name, field in (("COPYING", "copyingSha256"), ("NOTICE", "noticeSha256")):
    assert hashlib.sha256((UP / name).read_bytes()).hexdigest() == RECORD[field]
manifest = (ROOT / "desktop/nexus-runtime/Cargo.toml").read_text()
for required in ("crates/ft8", "crates/ft4", "crates/tempo-audio"):
    assert required in manifest
for forbidden in ("rigctld", "omnirig", "tauri-plugin-updater", "mfsk-core"):
    assert forbidden not in manifest.lower()
runtime_source = "\n".join(p.read_text() for p in (ROOT / "desktop/nexus-runtime/src").glob("*.rs"))
for forbidden in ('Command::new("rigctld")', "WsjtxUdpSource", "tauri_plugin_updater"):
    assert forbidden not in runtime_source
assert "pub const TX_ENABLED: bool = false" in runtime_source
print(f"NEXUS_NATIVE_PIN_OK {RECORD['commit']} tree={RECORD['tree']}")
