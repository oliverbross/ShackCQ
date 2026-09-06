#!/usr/bin/env python3
"""Audit pinned OpenHamClock branches and the latest release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

EXIT_NO_REVIEW = 0
EXIT_COMPARISON_FAILED = 1
EXIT_REVIEW_REQUIRED = 2
MAX_COMMITS = 100
MAX_PATHS = 300
MAX_API_BYTES = 8_000_000
MAX_CONTENT_BYTES = 2_000_000
MAX_REPORT_BYTES = 500_000
ISSUE_TITLE = "OpenHamClock upstream review required"

SECURITY_RE = re.compile(
    r"\b(security|xss|injection|escape[ds]?|saniti[sz](?:e|ed|ing)|ssrf|"
    r"credential|token|auth(?:entication|orization)?|cors|csp|traversal|"
    r"prototype pollution|dependency vulnerabilit|rate[- ]limit abuse)\b",
    re.IGNORECASE,
)
PROVIDER_RE = re.compile(
    r"\b(provider|endpoint|api contract|schema|payload|response format|"
    r"request format|upstream feed)\b",
    re.IGNORECASE,
)
ALGORITHM_RE = re.compile(
    r"\b(propagation|voacap|p\.?533|muf|luf|prediction algorithm|"
    r"satellite layer|sgp4|solar algorithm)\b",
    re.IGNORECASE,
)


class AuditError(RuntimeError):
    """The upstream comparison could not be completed honestly."""


class GitHub:
    def __init__(self, token: str | None = None) -> None:
        self.token = token

    def request(
        self,
        path: str,
        *,
        method: str = "GET",
        body: dict[str, Any] | None = None,
    ) -> Any:
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "ShackCQ-OpenHamClock-audit",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            f"https://api.github.com{path}",
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(MAX_API_BYTES + 1)
                if len(raw) > MAX_API_BYTES:
                    raise AuditError("GitHub API response exceeded the audit byte limit")
                if response.headers.get("X-RateLimit-Remaining") == "0":
                    raise AuditError("GitHub API rate limit exhausted")
                return json.loads(raw)
        except urllib.error.HTTPError as error:
            raise AuditError(f"GitHub API HTTP {error.code} for {path}") from error
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise AuditError(f"GitHub API request failed for {path}: {error}") from error

    def content(self, repo: str, path: str, ref: str) -> bytes:
        quoted_path = "/".join(urllib.parse.quote(part, safe="") for part in path.split("/"))
        row = self.request(
            f"/repos/{repo}/contents/{quoted_path}?ref={urllib.parse.quote(ref, safe='')}"
        )
        if not isinstance(row, dict) or row.get("type") != "file":
            raise AuditError(f"{path} at {ref} is not a file")
        url = row.get("download_url")
        if not isinstance(url, str) or not url.startswith("https://"):
            raise AuditError(f"{path} at {ref} has no safe download URL")
        request = urllib.request.Request(
            url, headers={"User-Agent": "ShackCQ-OpenHamClock-audit"}
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(MAX_CONTENT_BYTES + 1)
                if len(raw) > MAX_CONTENT_BYTES:
                    raise AuditError(f"{path} at {ref} exceeded the content byte limit")
                return raw
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
            raise AuditError(f"Unable to download {path} at {ref}: {error}") from error

    def update_issue(self, repo: str, body: str) -> None:
        if not self.token:
            raise AuditError("--update-issue requires GITHUB_TOKEN")
        issues = self.request(f"/repos/{repo}/issues?state=open&per_page=100")
        matches = [
            row
            for row in issues
            if isinstance(row, dict)
            and "pull_request" not in row
            and row.get("title") == ISSUE_TITLE
        ]
        payload = {"title": ISSUE_TITLE, "body": body}
        if matches:
            self.request(
                f"/repos/{repo}/issues/{matches[0]['number']}",
                method="PATCH",
                body=payload,
            )
        else:
            self.request(f"/repos/{repo}/issues", method="POST", body=payload)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def package_version(data: bytes) -> str:
    try:
        value = json.loads(data.decode()).get("version")
    except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as error:
        raise AuditError(f"Malformed package.json: {error}") from error
    if not isinstance(value, str) or not value.strip():
        raise AuditError("package.json has no valid version")
    return value.strip()


def branch_sha(client: GitHub, repo: str, branch: str) -> str:
    row = client.request(
        f"/repos/{repo}/branches/{urllib.parse.quote(branch, safe='')}"
    )
    try:
        value = row["commit"]["sha"]
    except (KeyError, TypeError) as error:
        raise AuditError(f"Malformed branch response for {branch}") from error
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{40}", value):
        raise AuditError(f"Invalid branch SHA for {branch}")
    return value


def latest_release(client: GitHub, repo: str) -> dict[str, Any]:
    row = client.request(f"/repos/{repo}/releases/latest")
    tag = row.get("tag_name") if isinstance(row, dict) else None
    if (
        not isinstance(tag, str)
        or not tag
        or len(tag) > 80
        or re.search(r"[\x00-\x20\x7f]", tag)
    ):
        raise AuditError("Latest release has an invalid tag name")
    ref = client.request(
        f"/repos/{repo}/git/ref/tags/{urllib.parse.quote(tag, safe='')}"
    )
    obj = ref.get("object") if isinstance(ref, dict) else None
    annotated: list[str] = []
    seen: set[str] = set()
    while isinstance(obj, dict) and obj.get("type") == "tag":
        tag_sha = obj.get("sha")
        if not isinstance(tag_sha, str) or tag_sha in seen:
            raise AuditError(f"Invalid or cyclic annotated tag {tag}")
        seen.add(tag_sha)
        annotated.append(tag_sha)
        tag_row = client.request(f"/repos/{repo}/git/tags/{tag_sha}")
        obj = tag_row.get("object") if isinstance(tag_row, dict) else None
    commit = obj.get("sha") if isinstance(obj, dict) and obj.get("type") == "commit" else None
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise AuditError(f"Release tag {tag} does not peel to a commit")
    return {
        "tag": tag,
        "commit": commit,
        "published_at": row.get("published_at"),
        "annotated_tag_objects": annotated,
    }


def compare(client: GitHub, repo: str, base: str, head: str) -> dict[str, Any]:
    if base == head:
        return {
            "status": "identical",
            "ahead_by": 0,
            "behind_by": 0,
            "total_commits": 0,
            "commits": [],
            "changed_paths": [],
            "commits_truncated": False,
            "changed_paths_truncated": False,
            "truncated": False,
        }
    endpoint = (
        f"/repos/{repo}/compare/{urllib.parse.quote(base, safe='')}..."
        f"{urllib.parse.quote(head, safe='')}?per_page={MAX_COMMITS}&page=1"
    )
    row = client.request(endpoint)
    if not isinstance(row, dict):
        raise AuditError("Malformed compare response")
    raw_commits, raw_files = row.get("commits"), row.get("files")
    if not isinstance(raw_commits, list) or not isinstance(raw_files, list):
        raise AuditError("Compare response omitted commits or files")
    commits = []
    for item in raw_commits[:MAX_COMMITS]:
        message = item.get("commit", {}).get("message") if isinstance(item, dict) else None
        commit_sha = item.get("sha") if isinstance(item, dict) else None
        if isinstance(commit_sha, str) and isinstance(message, str):
            commits.append({"sha": commit_sha, "message": message})
    paths = [
        item["filename"]
        for item in raw_files[:MAX_PATHS]
        if isinstance(item, dict) and isinstance(item.get("filename"), str)
    ]
    total = row.get("total_commits")
    if not isinstance(total, int) or total < 0:
        total = len(raw_commits)
    commits_truncated = total > len(commits) or len(raw_commits) > MAX_COMMITS
    paths_truncated = len(raw_files) >= MAX_PATHS
    return {
        "status": row.get("status", "unknown"),
        "ahead_by": row.get("ahead_by"),
        "behind_by": row.get("behind_by"),
        "total_commits": total,
        "commits": commits,
        "changed_paths": paths,
        "commits_truncated": commits_truncated,
        "changed_paths_truncated": paths_truncated,
        "truncated": commits_truncated or paths_truncated,
    }


def classify(comparison: dict[str, Any], watched: dict[str, list[str]]) -> dict[str, Any]:
    paths, commits = comparison["changed_paths"], comparison["commits"]
    watched_hits = {
        group: sorted(
            path for path in paths if any(path.startswith(prefix) for prefix in prefixes)
        )
        for group, prefixes in watched.items()
    }
    watched_hits = {group: hits for group, hits in watched_hits.items() if hits}
    categories: dict[str, dict[str, Any]] = {}
    watched_category = {
        "security": "security",
        "providers": "provider_contract",
        "propagation_algorithms": "propagation_algorithm",
    }
    for name, pattern in {
        "security": SECURITY_RE,
        "provider_contract": PROVIDER_RE,
        "propagation_algorithm": ALGORITHM_RE,
    }.items():
        watched_paths = {
            path
            for group, hits in watched_hits.items()
            if watched_category.get(group) == name
            for path in hits
        }
        path_hits = sorted(watched_paths | {path for path in paths if pattern.search(path)})
        commit_hits = [
            {
                "sha": item["sha"],
                "subject": item["message"].splitlines()[0][:200],
            }
            for item in commits
            if pattern.search(item["message"])
        ]
        if path_hits or commit_hits:
            categories[name] = {"paths": path_hits, "commits": commit_hits}
    return {
        "watched_groups": watched_hits,
        "triggering_categories": categories,
        "sensitive": bool(watched_hits or categories),
    }


def channel_result(
    client: GitHub,
    repo: str,
    branch: str,
    reviewed_sha: str,
    watched: dict[str, list[str]],
) -> dict[str, Any]:
    observed_sha = branch_sha(client, repo, branch)
    comparison = compare(client, repo, reviewed_sha, observed_sha)
    return {
        "branch": branch,
        "reviewed_sha": reviewed_sha,
        "observed_sha": observed_sha,
        "changed": reviewed_sha != observed_sha,
        "comparison": comparison,
        "classification": classify(comparison, watched),
    }


def audit(client: GitHub, manifest: dict[str, Any]) -> dict[str, Any]:
    upstream = manifest["upstream"]
    repo = f"{upstream['owner']}/{upstream['repository']}"
    watched = manifest["watched_source_paths"]
    stable_pin, preview_pin = manifest["stable"], manifest["preview"]
    stable = channel_result(
        client, repo, stable_pin["branch"], stable_pin["sha"], watched
    )
    stable_package = client.content(repo, "package.json", stable["observed_sha"])
    stable_licence = client.content(
        repo, manifest["licence"]["file"], stable["observed_sha"]
    )
    stable.update(
        reviewed_package_version=stable_pin["package_version"],
        observed_package_version=package_version(stable_package),
        reviewed_licence_sha256=manifest["licence"]["sha256"],
        observed_licence_sha256=sha256(stable_licence),
    )
    stable["package_version_changed"] = (
        stable["reviewed_package_version"] != stable["observed_package_version"]
    )
    stable["licence_changed"] = (
        stable["reviewed_licence_sha256"] != stable["observed_licence_sha256"]
    )
    preview = channel_result(
        client, repo, preview_pin["branch"], preview_pin["sha"], watched
    )
    relationship = compare(client, repo, stable["observed_sha"], preview["observed_sha"])

    reviewed_release = manifest["release"]
    observed_release = latest_release(client, repo)
    unavailable = []
    try:
        release_package = client.content(
            repo, "package.json", observed_release["commit"]
        )
        observed_package_version = package_version(release_package)
    except AuditError as error:
        if "404" not in str(error):
            raise
        unavailable.append("package.json")
        observed_package_version = None
    try:
        release_licence = client.content(
            repo, manifest["licence"]["file"], observed_release["commit"]
        )
        observed_licence_sha256 = sha256(release_licence)
    except AuditError as error:
        if "404" not in str(error):
            raise
        unavailable.append(manifest["licence"]["file"])
        observed_licence_sha256 = None
    observed_release.update(
        package_version=observed_package_version,
        licence_sha256=observed_licence_sha256,
        unavailable_files=unavailable,
    )
    release_changed_fields = [
        field
        for field in ("tag", "commit", "package_version", "licence_sha256")
        if (
            observed_release.get(field) is not None
            and reviewed_release.get(field) != observed_release.get(field)
        )
    ]
    release_comparison = compare(
        client, repo, reviewed_release["commit"], observed_release["commit"]
    )
    release = {
        "reviewed": reviewed_release,
        "observed": observed_release,
        "changed": bool(release_changed_fields or unavailable),
        "changed_fields": release_changed_fields,
        "comparison": release_comparison,
        "classification": classify(release_comparison, watched),
    }

    reasons = []
    if stable["changed"]:
        reasons.append("stable branch changed")
    if stable["package_version_changed"]:
        reasons.append("stable package version changed")
    if stable["licence_changed"]:
        reasons.append("stable licence digest changed")
    if release["changed"]:
        reasons.append("latest release identity or contents changed")
    if preview["changed"] and preview["classification"]["sensitive"]:
        reasons.append("preview changed in a sensitive area")
    if preview["changed"] and preview["comparison"]["truncated"]:
        reasons.append("preview inventory was truncated")
    result = {
        "schema_version": 2,
        "upstream": repo,
        "stable": stable,
        "preview": preview,
        "release": release,
        "stable_preview_relationship": relationship,
        "release_only_change": release["changed"] and not stable["changed"],
        "review_required": bool(reasons),
        "review_reasons": reasons,
    }
    result["result"] = "REVIEW_REQUIRED" if reasons else "NO_REVIEW"
    return result


def markdown_report(result: dict[str, Any]) -> str:
    if result.get("result") == "COMPARISON_FAILED":
        return (
            "# OpenHamClock upstream audit\n\n"
            "**Result: COMPARISON FAILED**\n\n"
            f"- Error: {result.get('error', 'unknown comparison failure')}\n"
        )
    stable, preview, release = result["stable"], result["preview"], result["release"]
    lines = [
        "# OpenHamClock upstream audit",
        "",
        f"**Result: {result['result']}**",
        "",
        "## Latest release",
        "",
        f"- Reviewed tag: {release['reviewed']['tag']}",
        f"- Observed tag: {release['observed']['tag']}",
        f"- Reviewed commit: {release['reviewed']['commit']}",
        f"- Observed commit: {release['observed']['commit']}",
        f"- Changed fields: {', '.join(release['changed_fields']) or 'none'}",
        f"- Unavailable release files: {', '.join(release['observed']['unavailable_files']) or 'none'}",
        f"- Comparison: {release['comparison']['status']}",
        f"- Commit inventory truncated: {str(release['comparison']['commits_truncated']).lower()}",
        f"- Path inventory truncated: {str(release['comparison']['changed_paths_truncated']).lower()}",
        f"- Release-only change: {str(result['release_only_change']).lower()}",
        "",
        "## Stable and preview",
        "",
        f"- Stable: {stable['reviewed_sha']} -> {stable['observed_sha']}",
        f"- Stable package: {stable['reviewed_package_version']} -> {stable['observed_package_version']}",
        f"- Stable licence SHA-256: {stable['reviewed_licence_sha256']} -> {stable['observed_licence_sha256']}",
        f"- Preview: {preview['reviewed_sha']} -> {preview['observed_sha']}",
        f"- Preview inventory truncated: {str(preview['comparison']['truncated']).lower()}",
    ]
    release_commits = release["comparison"]["commits"]
    release_paths = release["comparison"]["changed_paths"]
    if release_commits or release_paths:
        lines.extend(["## Latest-release inventory", ""])
        lines.extend(
            f"- commit {item['sha']}: {item['message'].splitlines()[0][:200]}"
            for item in release_commits
        )
        lines.extend(f"- path {path}" for path in release_paths)
        lines.append("")
    lines.extend(["", "## Preview triggers", ""])
    categories = preview["classification"]["triggering_categories"]
    if not categories:
        lines.append("- None")
    for category, triggers in categories.items():
        lines.append(f"- {category}")
        for item in triggers["commits"]:
            lines.append(f"  - commit {item['sha']}: {item['subject']}")
        for path in triggers["paths"]:
            lines.append(f"  - path {path}")
    lines.extend(["", "## Review reasons", ""])
    lines.extend(f"- {reason}" for reason in result["review_reasons"])
    if not result["review_reasons"]:
        lines.append("- None")
    return "\n".join(lines) + "\n"


def write_reports(output_dir: Path, result: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_text = json.dumps(result, indent=2, sort_keys=True) + "\n"
    markdown_text = markdown_report(result)
    if len(json_text.encode()) > MAX_REPORT_BYTES:
        raise AuditError("JSON report exceeded the audit byte limit")
    if len(markdown_text.encode()) > MAX_REPORT_BYTES:
        raise AuditError("Markdown report exceeded the audit byte limit")
    (output_dir / "openhamclock-upstream-audit.json").write_text(
        json_text, encoding="utf-8"
    )
    (output_dir / "openhamclock-upstream-audit.md").write_text(
        markdown_text, encoding="utf-8"
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest", type=Path, default=Path("docs/hamclock/upstream.json")
    )
    parser.add_argument("--output-dir", type=Path, default=Path("build/reports"))
    parser.add_argument("--update-issue", action="store_true")
    parser.add_argument("--issue-repository")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    client = GitHub(os.environ.get("GITHUB_TOKEN"))
    issue_attempted = False
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        result = audit(client, manifest)
        write_reports(args.output_dir, result)
        if result["review_required"] and args.update_issue:
            repo = args.issue_repository or manifest.get("watcher", {}).get(
                "issue_repository"
            )
            if not repo:
                raise AuditError("--update-issue requires --issue-repository")
            issue_attempted = True
            client.update_issue(repo, markdown_report(result))
        return EXIT_REVIEW_REQUIRED if result["review_required"] else EXIT_NO_REVIEW
    except (AuditError, OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        failure = {
            "schema_version": 2,
            "result": "COMPARISON_FAILED",
            "review_required": True,
            "review_reasons": ["upstream comparison failed"],
            "error": str(error),
        }
        try:
            write_reports(args.output_dir, failure)
            repo = args.issue_repository or locals().get("manifest", {}).get(
                "watcher", {}
            ).get("issue_repository")
            if args.update_issue and repo and not issue_attempted:
                issue_attempted = True
                client.update_issue(repo, markdown_report(failure))
        except (AuditError, OSError):
            pass
        print(f"OpenHamClock upstream comparison failed: {error}", file=sys.stderr)
        return EXIT_COMPARISON_FAILED


if __name__ == "__main__":
    raise SystemExit(main())
