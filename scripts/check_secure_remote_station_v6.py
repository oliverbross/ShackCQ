#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed source/provenance gate for Secure Remote Station v6."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = [
    "SDROXIDE_REMOTE_STATION_AUDIT_V6.md", "REMOTE_STATION_FEATURE_CROSSWALK_V6.md",
    "TCI_SERVER_PROTOCOL_MATRIX_V6.md", "RIGCTLD_PROTOCOL_MATRIX_V6.md",
    "REMOTE_MEDIA_CODEC_DECISION.md", "REMOTE_SECURITY_THREAT_MODEL.md", "REMOTE_PROVENANCE_V6.md",
    "SHACKCQ_REMOTE_STATION_V1.md", "REMOTE_PROTOCOL_V1.md", "REMOTE_PAIRING_AND_ROLES.md",
    "REMOTE_MEDIA_STREAMING.md", "REMOTE_TX_AND_INTERLOCKS.md", "REMOTE_ROTATOR_CONTROL.md",
    "REMOTE_DIGI_KEYER_VOICE.md", "STATIOND_WINDOWS_MACOS_LINUX.md", "ANDROID_REMOTE_CLIENT.md",
    "REMOTE_STATION_LIVE_ACCEPTANCE.md", "REMOTE_STATION_ADMIN_GUIDE.md",
    "REMOTE_SCALE_SOAK_V6.md",
]
for name in DOCS:
    path = ROOT / "docs" / "remote" / name
    if not path.is_file() or path.stat().st_size < 200:
        raise SystemExit(f"missing or undersized required document: {path}")

required = {
    "core/include/shackcq/remote.h": ["MaxSessions = 8", "MaxMediaPayload", "SessionAuthority"],
    "core/src/remote.cpp": ["encodeMedia", "handleRigctld", "handleTci"],
    "desktop/src/network/RemoteStationService.cpp": ["TlsV1_3OrLater", "SIGNED_CHALLENGE_INVALID", "globalStop"],
    "android/app/src/main/java/app/shackcq/mobile/RemoteStationClient.kt": ["AndroidKeyStore", "FingerprintTrustManager", "HEARTBEAT"],
    "android/app/src/main/java/app/shackcq/mobile/DebugRemoteLabV6.kt": ["DEMO · NO RADIO", "never opens a socket"],
}
for relative, needles in required.items():
    text = (ROOT / relative).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"{relative}: missing contract marker {needle!r}")

tracked = [ROOT / name for name in required]
tracked += list((ROOT / "docs" / "remote").glob("*.md"))
tracked += [
    ROOT / "android/app/src/main/java/app/shackcq/mobile/RemoteStationScreen.kt",
    ROOT / "desktop/qml/ShackCQ/Settings/SettingsPage.qml",
    ROOT / ".github/workflows/secure-remote-station-v6.yml",
]
for path in tracked:
    if path.suffix.lower() not in {".kt", ".cpp", ".h", ".qml", ".md", ".py", ".yml"}:
        continue
    text = path.read_text(encoding="utf-8", errors="ignore")
    if re.search(r"hostnameVerifier\s*\{[^}]*true\s*}", text, re.S):
        raise SystemExit(f"trust-all hostname verifier forbidden: {path}")
    if "ws://" in text and "DEMO" not in text and "UNAVAILABLE" not in text:
        raise SystemExit(f"cleartext websocket literal forbidden: {path}")

print("secure remote station v6 source, docs, pinning and fail-closed markers passed")
