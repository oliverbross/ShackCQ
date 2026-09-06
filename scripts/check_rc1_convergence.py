#!/usr/bin/env python3
"""Fail-closed whole-repository RC1 convergence contract."""

from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RELEASE_DOCS = [
    "SHACKCQ_MULTIPLATFORM_RC1.md", "WHOLE_REPOSITORY_LINEAGE_AUDIT.md",
    "BRANCH_RETENTION_AND_ARCHIVE_PLAN.md", "RC1_UNIQUE_WORK_AND_CONFLICT_LEDGER.md",
    "RC1_OWNER_GRAPH.md", "RC1_FEATURE_COMPLETION_MATRIX.md", "RC1_PLATFORM_PARITY_MATRIX.md",
    "RC1_SCHEMA_AND_STORAGE_MATRIX.md", "RC1_CONFIGURATION_CONTRACT.md",
    "RC1_PRIVACY_AND_SECURITY_AUDIT.md", "RC1_SOURCE_DISTRIBUTION.md",
    "RC1_REPRODUCIBLE_BUILD.md", "RC1_LIVE_ACCEPTANCE_CHECKLIST.md",
    "MAIN_PROMOTION_RUNBOOK.md", "SIGNING_AND_DISTRIBUTION_RUNBOOK.md",
    "RC1_ANCESTRY_PROOF.json",
]
UPDATED_DOCS = [
    "README.md", "PRODUCT.md", "NOTICE", "docs/FINAL_WHOLE_APP_COMPLETION_MATRIX.md",
    "docs/RELEASE_CANDIDATE_READINESS.md", "docs/PRIVACY_AND_DATA_INVENTORY.md",
    "docs/THIRD_PARTY_AND_PROVENANCE_AUDIT.md", "docs/APP_SIZE_AND_MODULE_POLICY.md",
    "docs/performance/SCALE_AND_STABILITY.md",
]


def fail(message: str) -> None:
    raise SystemExit(f"RC1 convergence audit failed: {message}")


for name in RELEASE_DOCS:
    if not (ROOT / "docs/release" / name).is_file():
        fail(f"missing docs/release/{name}")
for name in UPDATED_DOCS:
    text = (ROOT / name).read_text(errors="replace")
    if "Multiplatform RC1" not in text and "RC1" not in text:
        fail(f"missing RC1 marker in {name}")

for name in ("RC1_FEATURE_COMPLETION_MATRIX.md", "RC1_PLATFORM_PARITY_MATRIX.md"):
    text = (ROOT / "docs/release" / name).read_text()
    if re.search(r"\|\s*(FOUNDATION_WIRED|MISSING)\s*\|", text):
        fail(f"provisional status remains in {name}")

configuration = (ROOT / "docs/release/RC1_CONFIGURATION_CONTRACT.md").read_text().lower()
for term in (
    "credentials", "ptt/tune", "digi arm", "keyer queue", "repeat cq", "active contest",
    "n1mm arm", "dx chaser session", "radio connection", "rotator connection", "tci tx", "pending commands",
):
    if term not in configuration:
        fail(f"safe configuration exclusion missing: {term}")

subprocess.check_call([sys.executable, "scripts/check_release_candidate.py"], cwd=ROOT)
subprocess.check_call(
    [sys.executable, "scripts/rc1_lineage_audit.py", "--verify", "docs/release/RC1_ANCESTRY_PROOF.json"],
    cwd=ROOT,
)
print("RC1 convergence audit PASS: lineage, owners, schemas, configuration, privacy and release docs")
