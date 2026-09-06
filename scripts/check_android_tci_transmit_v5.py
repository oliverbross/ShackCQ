#!/usr/bin/env python3
"""Fail-closed source/provenance watcher for Android TCI Transmit v5."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REQUIRED = [
    "docs/android/ANDROID_TCI_TRANSMIT_V5.md",
    "docs/android/SDROXIDE_TCI_TX_AUDIT_V5.md",
    "docs/android/TCI_TX_PROTOCOL_MATRIX_V5.md",
    "docs/android/TCI_TX_PROVENANCE_V5.md",
    "docs/android/ANDROID_TCI_TX_AUDIO.md",
    "docs/android/ANDROID_TCI_TX_INTERLOCKS.md",
    "docs/android/ANDROID_TCI_PHYSICAL_ACCEPTANCE.md",
    "docs/android/ANDROID_TCI_TX_LIVE_ACCEPTANCE.md",
    "android/app/src/main/java/app/shackcq/mobile/TciTransmitControl.kt",
    "android/app/src/main/java/app/shackcq/mobile/DebugTciTransmitter.kt",
]


def check(root: pathlib.Path = ROOT) -> list[str]:
    errors: list[str] = []
    for relative in REQUIRED:
        if not (root / relative).is_file():
            errors.append(f"missing {relative}")
    if errors:
        return errors
    authority = (root / REQUIRED[8]).read_text()
    required_tokens = [
        "UNVERIFIED", "TX_AUDIO_LOOPBACK_ACCEPTED", "PTT_ACCEPTED", "TUNE_ACCEPTED", "RF_ACCEPTED",
        "RX_UNCONFIRMED", "NativeTci.TRX", "NativeTci.TUNE", "SWR_ABORT", "ALC_ABORT",
        "Dispatchers.IO", "globalStop", "requestRxAndRecheck",
    ]
    for token in required_tokens:
        if token not in authority:
            errors.append(f"authority missing {token}")
    matrix = (root / REQUIRED[2]).read_text()
    for classification in ["SUPPORTED_VERIFIED", "SUPPORTED_READBACK_ONLY", "SUPPORTED_WRITE_WITH_ACCEPTANCE",
                           "UNAVAILABLE_PROTOCOL", "DIALECT_SPECIFIC", "EXCLUDED"]:
        if classification not in matrix:
            errors.append(f"protocol matrix missing {classification}")
    provenance = (root / REQUIRED[3]).read_text()
    for pin in ["a680935b10f33768a499435e8bd37f779fa640ae", "b081213ff97150fd29f669c633f060f93c81a286"]:
        if pin not in provenance:
            errors.append(f"provenance missing pin {pin}")
    java_root = root / "android/app/src/main/java"
    allowed = {"TciTransmitControl.kt", "DebugTciTransmitter.kt", "NativeTci.kt"}
    risky = re.compile(r"(?:NativeTci\.(?:TRX|TUNE)|trx:\\d+,true|tune:\\d+,true)")
    for path in java_root.rglob("*.kt"):
        if path.name not in allowed and risky.search(path.read_text(errors="ignore")):
            errors.append(f"direct TCI TX surface outside authority: {path.relative_to(root)}")
    if "DEMO · NO RADIO" not in (root / REQUIRED[9]).read_text():
        errors.append("debug transmitter lacks DEMO · NO RADIO identity")
    return errors


if __name__ == "__main__":
    failures = check()
    if failures:
        print("ANDROID TCI TRANSMIT V5 WATCHER: FAIL")
        print("\n".join(f"- {item}" for item in failures))
        sys.exit(1)
    print("ANDROID TCI TRANSMIT V5 WATCHER: PASS")
