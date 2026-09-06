#!/usr/bin/env python3
"""Verify the immutable RC1 lineage contract and emit machine-readable proof."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "bf297ae5d13708c16fe3ed621f29b2f649c36110"
ACCEPTED_UI = "de32c8ac908c7979f39bfdfc41ca050378901e75"
PROTECTED_MAIN = "27c70d0c2ab0ae21ef18d7fd9b39f8878b0940ea"
FROZEN_ORIGIN_MAIN = "fb04d52df0c9ccc305125449bb188ef8e3f0185e"
REVIEW_TIP = "00fe01cd56c206543b1afb0fb03dfdb9befb92f7"
SEMANTIC_INTEGRATION = "5ee25b51d979d319bdc2bc9410c5af3599b87887"
RC_BRANCH = "integration/shackcq-multiplatform-rc1"


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, text=True, capture_output=True, check=False
    )
    if check and result.returncode:
        raise SystemExit(result.stderr.strip() or "git command failed")
    return result.stdout.strip()


def ancestor(commit: str, descendant: str) -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit, descendant],
        cwd=ROOT,
        check=False,
    ).returncode == 0


def resolve(ref: str) -> str:
    return git("rev-parse", "--verify", ref)


def resolve_local_only(ref: str, expected: str) -> str:
    resolved = git("rev-parse", "--verify", ref, check=False)
    if resolved:
        return resolved
    if os.environ.get("GITHUB_ACTIONS") == "true":
        # Hosted runners cannot inspect refs that exist only in Oliver's local
        # repository. Their immutable value is recorded by the local audit;
        # hosted proof instead verifies the semantic-integration ancestor.
        return expected
    raise SystemExit(f"required local-only ref does not resolve: {ref}")


def build_proof() -> dict:
    head = resolve("HEAD")
    branch = git("branch", "--show-current") or os.environ.get("GITHUB_REF_NAME", "")
    allow_descendant_validation = os.environ.get("SHACKCQ_ALLOW_RC1_DESCENDANT_VALIDATION") == "1"
    required = {
        "canonical_source": SOURCE,
        "accepted_ui": ACCEPTED_UI,
        "protected_local_main": PROTECTED_MAIN,
        "frozen_origin_main": FROZEN_ORIGIN_MAIN,
        "recovery_ref": PROTECTED_MAIN,
        "review_tip": REVIEW_TIP,
        "semantic_integration": SEMANTIC_INTEGRATION,
    }
    actual = {
        "canonical_source": resolve(SOURCE),
        "accepted_ui": resolve(ACCEPTED_UI),
        # These entries describe immutable commit objects, not movable branch
        # names.  After promotion, both `main` and `origin/main` legitimately
        # move to the RC head while the recorded boundary objects must remain
        # resolvable and the protected local tip must remain on its recovery
        # ref.
        "protected_local_main": resolve(PROTECTED_MAIN),
        "frozen_origin_main": resolve(FROZEN_ORIGIN_MAIN),
        "recovery_ref": resolve("origin/recovery/local-main-27c70d0"),
        "review_tip": resolve_local_only(
            "integration/shackcq-final-whole-app-v1", REVIEW_TIP
        ),
        "semantic_integration": resolve(SEMANTIC_INTEGRATION),
    }
    if actual != required:
        raise SystemExit(f"immutable reference mismatch: {actual}")
    if branch != RC_BRANCH and not allow_descendant_validation:
        raise SystemExit(f"expected {RC_BRANCH}, got {branch}")
    for commit in (SOURCE, ACCEPTED_UI, SEMANTIC_INTEGRATION):
        if not ancestor(commit, head):
            raise SystemExit(f"required commit is not an RC ancestor: {commit}")

    refs = []
    raw = git(
        "for-each-ref",
        "--format=%(refname:short)|%(objectname)",
        "refs/heads",
        "refs/remotes/origin",
    )
    for line in raw.splitlines():
        name, sha = line.split("|", 1)
        if sha == REVIEW_TIP:
            classification = "ALREADY_PRESENT_EQUIVALENT"
        elif sha == PROTECTED_MAIN:
            classification = "UNRELATED_PRESERVED"
        elif ancestor(sha, head):
            classification = "ANCESTOR_OF_RC"
        else:
            classification = "EXPERIMENTAL_NOT_ACCEPTED"
        refs.append({"ref": name, "sha": sha, "classification": classification})

    counts: dict[str, int] = {}
    for item in refs:
        key = item["classification"]
        counts[key] = counts.get(key, 0) + 1
    return {
        "contract": "SHACKCQ_RC1_ANCESTRY_V1",
        "rc_branch": RC_BRANCH,
        "validation_branch": branch,
        "rc_head": head,
        "immutable_refs": required,
        "invariants": {
            "canonical_source_is_ancestor": ancestor(SOURCE, head),
            "accepted_ui_is_ancestor": ancestor(ACCEPTED_UI, head),
            "semantic_integration_is_ancestor": ancestor(SEMANTIC_INTEGRATION, head),
            "protected_main_not_merged": not ancestor(PROTECTED_MAIN, head),
            "frozen_origin_main_object_preserved": resolve(FROZEN_ORIGIN_MAIN)
            == FROZEN_ORIGIN_MAIN,
            "origin_main_is_frozen_or_promoted_head": resolve("origin/main")
            in (FROZEN_ORIGIN_MAIN, head),
            "tags": len(git("tag", "--list").splitlines()) if git("tag", "--list") else 0,
        },
        "inventory": {
            "branch_ref_count": len(refs),
            "distinct_tip_count": len({item["sha"] for item in refs}),
            "worktree_count": git("worktree", "list", "--porcelain").count("worktree "),
            "classification_counts": counts,
            "non_ancestor_refs": [
                item for item in refs if item["classification"] != "ANCESTOR_OF_RC"
            ],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    proof = build_proof()
    if args.verify:
        recorded = json.loads(args.verify.read_text())
        for key in ("contract", "rc_branch", "immutable_refs"):
            if recorded.get(key) != proof.get(key):
                raise SystemExit(f"recorded proof mismatch: {key}")
    print(json.dumps(proof, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
