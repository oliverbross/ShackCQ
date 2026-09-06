#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed source, privacy and documentation contract for Android SDR Workbench v4."""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def implement_v4_rows(matrix: str) -> list[str]:
    return [line for line in matrix.splitlines() if re.search(r"\|\s*IMPLEMENT_V4\s*\|", line)]


def workbench_strip_calls(source: str) -> int:
    return len(re.findall(r"\bSdrStereoWorkbenchStrip\s*\(", source))


def audit(root: pathlib.Path = ROOT) -> None:
    domain = (root / "android/app/src/main/java/app/shackcq/mobile/AndroidSdrWorkbenchV4.kt").read_text()
    screens = (root / "android/app/src/main/java/app/shackcq/mobile/AndroidSdrWorkbenchScreensV4.kt").read_text()
    tci = (root / "android/app/src/main/java/app/shackcq/mobile/AndroidTciBackend.kt").read_text()
    integration = (root / "android/app/src/main/java/app/shackcq/mobile/MainActivity.kt").read_text()
    enhancement = (root / "android/app/src/main/java/app/shackcq/mobile/AndroidSdrEnhancementDomain.kt").read_text()
    panadapter = (root / "android/app/src/main/java/app/shackcq/mobile/PanadapterScreen.kt").read_text()
    kotlin_files = list((root / "android/app/src/main/java").rglob("*.kt"))
    for owner in ("AndroidSdrWorkbenchV4", "IqCaptureRepository", "ReplayIqSource", "SpectrumSurveyRepository", "SignalMeasurementController"):
        count = sum(len(re.findall(rf"\bclass\s+{owner}\b", path.read_text(errors="ignore"))) for path in kotlin_files)
        require(count == 1, f"expected one {owner}, found {count}")
    for token in ("SHACKCQ_FLOAT32_IQ_LE", ".f32iq.tmp", "fd.sync()", "maximumFileSeconds", "maximumTotalBytes"):
        require(token in domain, f"production I/Q invariant missing: {token}")
    for token in (".25f", ".5f", "1f", "2f", "audioTruthful", "requestedFrame"):
        require(token in domain, f"offline replay invariant missing: {token}")
    for token in ("median_level", "occupied_samples", "scanner_hit_count", "PRAGMA quick_check", "VACUUM"):
        require(token in domain, f"bounded survey invariant missing: {token}")
    for token in ("MARKER A/B", "TRACK A", "LOCAL RX FOLLOW", "CHANNEL MONITOR", "UNDETECTED · NO TONE CLAIM"):
        require(token in screens + domain, f"operator-visible analysis state missing: {token}")
    require("RadioPlatformAction" not in domain, "workbench domain must not own physical radio or transmit actions")
    require("iq_stop" in screens and "Replay selected" in screens and "IqReplayState.PLAYING" in integration,
            "replay must detach live TCI and reject late live frames")
    for token in ("staleFrames", "duplicateStatus", "capabilityChanges", "streamAttachmentRequired", "EXPLICIT STREAM ATTACHMENT"):
        require(token in tci, f"TCI hardening invariant missing: {token}")
    require("BuildConfig.DEBUG" in enhancement and "DEMO · NO RADIO" in enhancement,
            "Debug SDR Lab must be debug-only and visibly synthetic")
    require(workbench_strip_calls(panadapter) == 1,
            "generic Panadapter must render exactly one SDR Workbench control strip")
    for token in ("exportChannelMemoriesJson", "importChannelMemoriesJson", "exportChannelMemoriesCsv", "importChannelMemoriesCsv"):
        require(token in domain, f"memory interchange missing: {token}")
    required_docs = (
        "ANDROID_SDR_WORKBENCH_V4.md", "SDROXIDE_ANDROID_FINAL_PARITY_AUDIT_V4.md",
        "SDROXIDE_ANDROID_FINAL_PARITY_MATRIX_V4.md", "SDROXIDE_ANDROID_V4_PROVENANCE.md",
        "ANDROID_IQ_RECORD_REPLAY.md", "ANDROID_SIGNAL_MEASUREMENTS.md", "ANDROID_SPECTRUM_SURVEY.md",
        "ANDROID_SCANNER_INTELLIGENCE_V3.md", "ANDROID_RECEIVER_CALIBRATION.md",
        "ANDROID_SDR_WORKBENCH_LIVE_ACCEPTANCE.md",
    )
    for name in required_docs:
        require((root / "docs/android" / name).stat().st_size > 200, f"required v4 document missing: {name}")
    matrix = (root / "docs/android/SDROXIDE_ANDROID_FINAL_PARITY_MATRIX_V4.md").read_text()
    require(not implement_v4_rows(matrix), "final parity matrix still contains IMPLEMENT_V4 rows")
    privacy = (root / "docs/PRIVACY_AND_DATA_INVENTORY.md").read_text().lower()
    for term in ("raw i/q", "decoded conversations", "rds radiotext", "operator notes", "private paths"):
        require(term in privacy, f"support-bundle exclusion missing: {term}")
    packaged = list((root / "android/app/src/main").rglob("*.f32iq")) + list((root / "android/app/src/main").rglob("*.iq"))
    require(not packaged, "packaged I/Q fixture prohibited")


def main() -> int:
    audit()
    print("Android SDR Workbench v4 ownership, replay isolation, privacy and parity audit passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, FileNotFoundError) as error:
        print(f"ANDROID SDR WORKBENCH V4 AUDIT FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
