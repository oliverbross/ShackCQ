#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed source contract audit for Android Local SDR Receiver v3."""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    kotlin = (ROOT / "android/app/src/main/java/app/shackcq/mobile/LocalSdrReceiverV3.kt").read_text()
    native = (ROOT / "core/src/local_receiver.cpp").read_text()
    cmake = (ROOT / "core/CMakeLists.txt").read_text()
    screens = (ROOT / "android/app/src/main/java/app/shackcq/mobile/LocalSdrReceiverScreens.kt").read_text()
    lab = (ROOT / "android/app/src/main/java/app/shackcq/mobile/AndroidSdrEnhancementDomain.kt").read_text()
    owners = list((ROOT / "android/app/src/main/java").rglob("*.kt"))
    owner_count = sum(len(re.findall(r"\bclass\s+LocalReceiverController\b", path.read_text(errors="ignore"))) for path in owners)
    require(owner_count == 1, f"expected one LocalReceiverController, found {owner_count}")
    require("RadioPlatformAction" not in kotlin and not re.search(r"\b(?:PTT|TUNE|TX_AUDIO)\b", kotlin),
            "local receiver must not own radio/transmit actions")
    require("ArrayBlockingQueue<IqFrame>(8)" in kotlin and "latestReceivers.size >= 2" in kotlin,
            "receiver/input bounds missing")
    require("TciRxAudioController" in kotlin and "AudioTrack" not in kotlin,
            "local receiver must reuse the sole audio owner")
    require("ReceiveTimeShiftController" in kotlin and "audioPreRoll" in kotlin,
            "recording pre-roll must reuse the existing time-shift owner")
    require("one active file" in (ROOT / "docs/android/ANDROID_RECEIVER_RECORDING.md").read_text().lower(),
            "recording bound documentation missing")
    for mode in ("USB", "LSB", "CW", "DIGU", "DIGL", "DSB", "AM", "SAM", "NFM", "WFM", "SPECTRUM"):
        require(mode.title() in native or mode in kotlin, f"mode missing: {mode}")
    for token in ("CTCSS", "DCS", "RDS", "SAM", "WFM"):
        require(token in screens + kotlin, f"operator-visible state missing: {token}")
    require("BuildConfig.DEBUG" in lab and "DEMO · NO RADIO" in lab, "debug lab must be debug-only and labelled")
    require("shackcq_local_receiver_tests" in cmake, "native local-receiver test registration missing")
    required_docs = (
        "ANDROID_LOCAL_SDR_RECEIVER_V3.md", "SDROXIDE_LOCAL_RECEIVER_AUDIT_V3.md",
        "SDROXIDE_LOCAL_RECEIVER_CROSSWALK_V3.md", "SDROXIDE_LOCAL_RECEIVER_PROVENANCE_V3.md",
        "ANDROID_LOCAL_RECEIVER_ARCHITECTURE.md", "ANDROID_RECEIVE_MODES.md",
        "ANDROID_FM_TONE_AND_RDS.md", "ANDROID_RECEIVER_RECORDING.md", "ANDROID_LOCAL_SDR_LIVE_ACCEPTANCE.md",
    )
    for name in required_docs:
        require((ROOT / "docs/android" / name).stat().st_size > 100, f"required document missing: {name}")
    print("Android Local SDR Receiver v3 ownership, safety, visibility and documentation audit passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"LOCAL SDR V3 AUDIT FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
