#!/usr/bin/env python3
"""Deterministic, credential-free release-candidate contract audit."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_DOCS = [
    "SHACKCQ_FINAL_WHOLE_APP_CONVERGENCE.md",
    "FINAL_WHOLE_APP_COMPLETION_MATRIX.md",
    "FINAL_WHOLE_APP_OWNERSHIP.md",
    "FINAL_INTEGRATED_LIVE_ACCEPTANCE.md",
    "SHACKCQ_FINAL_CONVERGENCE.md",
    "FINAL_CORE_COMPLETION_MATRIX.md",
    "FINAL_CORE_OWNERSHIP.md",
    "RELEASE_CANDIDATE_READINESS.md",
    "PRIVACY_AND_DATA_INVENTORY.md",
    "THIRD_PARTY_AND_PROVENANCE_AUDIT.md",
    "FINAL_LIVE_ACCEPTANCE_CHECKLIST.md",
    "TABLET_ACCEPTANCE_SWEEP_2.md",
    "BAND_MAPS_V2_OPERATOR_GUIDE.md",
    "CONTEST_COCKPIT_AND_SESSION_LOG_V2.md",
    "PORTABLE_CATALOGUE_PROVIDER_MATRIX.md",
    "TABLET_ACCEPTANCE_SWEEP_2_LIVE_CHECKLIST.md",
]
SCHEMA_PATTERNS = {
    "QSO schema 17": r"class QsoDatabase[^\n]+SQLiteOpenHelper\(context, databaseName, null, 17\)",
    "Neural schema 5": r"SQLiteOpenHelper\(context, databaseName, null, 5\)",
    "Digi schema 2": r"SQLiteOpenHelper\(context, databaseName, null, 2\)",
    "Groups.io schema 2": r"SQLiteOpenHelper\(appContext, databaseName, null, 2\)",
    "projection contract 6": r"QSO schema 17 · projection contract 6 · Neural 5 · Digi 2 · Groups\.io 2",
    "Contest schema 2": r"class ContestSessionStore[^\n]+SQLiteOpenHelper\(context, name, null, 2\)",
    "Contest staging table": r"CREATE TABLE IF NOT EXISTS contest_qso_entry",
    "DX Chaser schema 1": r"class DxChaserStore[^\n]+SQLiteOpenHelper\(context, DATABASE_NAME, null, 1\)",
    "Band Maps preferences only": r"BAND_MAP_PREFERENCES\s*=\s*\"shackcq-bandmaps-v1\"",
}


def fail(message: str) -> None:
    raise SystemExit(f"release-candidate audit failed: {message}")


for name in REQUIRED_DOCS:
    if not (ROOT / "docs" / name).is_file():
        fail(f"missing docs/{name}")

source = "\n".join(
    path.read_text(errors="replace")
    for path in (ROOT / "android/app/src/main/java/app/shackcq/mobile").rglob("*.kt")
)
for label, pattern in SCHEMA_PATTERNS.items():
    if not re.search(pattern, source):
        fail(f"{label} source contract not found")

fixture_path = ROOT / "fixtures/configuration/golden-v1.json"
fixture = json.loads(fixture_path.read_text())
if fixture.get("format_signature") != "SHACKCQ_CONFIGURATION_BUNDLE" or fixture.get("schema") != 1:
    fail("configuration fixture signature/schema mismatch")
canonical = json.dumps(fixture["sections"], separators=(",", ":"), ensure_ascii=False)
if hashlib.sha256(canonical.encode()).hexdigest() != fixture.get("payload_sha256"):
    fail("configuration fixture hash mismatch")
serialized = fixture_path.read_text().lower()
for forbidden in ("password", "secret", "api_key", "credential", "tx_arm", "transmit_enabled"):
    if forbidden in serialized:
        fail(f"configuration fixture contains forbidden key {forbidden}")

health = (ROOT / "android/app/src/main/java/app/shackcq/mobile/SystemHealthCentre.kt").read_text()
for forbidden in ("message_body", "qso_payload", "credential_value"):
    if forbidden in health.lower():
        fail(f"support-bundle implementation contains forbidden payload marker {forbidden}")

print("release-candidate audit PASS: docs, schemas, golden configuration, privacy contracts")
