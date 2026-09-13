#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Bounded, fail-closed readiness probe for a packaged native-only stationd."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

MAX_RESPONSE_BYTES = 64 * 1024
MAX_DIAGNOSTIC_BYTES = 2048
SECRET_PATTERN = re.compile(rb"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")


def process_exists(pid: int) -> bool:
    stat_path = Path(f"/proc/{pid}/stat")
    try:
        process_stat = stat_path.read_text(encoding="ascii")
        state = process_stat.rsplit(")", 1)[1].lstrip()[:1]
        if state == "Z":
            return False
    except (OSError, IndexError):
        pass
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def diagnostic(path: Path) -> str:
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - MAX_DIAGNOSTIC_BYTES))
            raw = stream.read(MAX_DIAGNOSTIC_BYTES)
    except OSError as error:
        return f"stationd diagnostics unavailable: {error}"
    return SECRET_PATTERN.sub(b"[REDACTED]", raw).decode("utf-8", "replace")


def validate(payload: bytes, expected_pid: int) -> dict[str, object]:
    if len(payload) > MAX_RESPONSE_BYTES:
        raise ValueError("stationd status exceeds response bound")
    value = json.loads(payload)
    if not isinstance(value, dict) or value.get("ok") is not True:
        raise ValueError("stationd status is not an ok response")
    result = value.get("result")
    if not isinstance(result, dict) or result.get("processId") != expected_pid:
        raise ValueError("stationd status processId mismatch")
    if result.get("hardwareAutoconnect") is not False:
        raise ValueError("stationd hardware autoconnect is not disabled")
    ingress = result.get("nativeIngress")
    if not isinstance(ingress, dict) or ingress.get("available") is not True:
        raise ValueError("stationd native ingress is unavailable")
    if ingress.get("contract") != {"major": 1, "minor": 0}:
        raise ValueError("stationd native ingress protocol mismatch")
    if ingress.get("authentication") != "HMAC_SHA256_OS_VAULT":
        raise ValueError("stationd native ingress authentication mismatch")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--executable", required=True)
    parser.add_argument("--socket", required=True)
    parser.add_argument("--expected-pid", required=True, type=int)
    parser.add_argument("--stderr-log", required=True, type=Path)
    parser.add_argument("--overall-timeout", type=float, default=5.0)
    parser.add_argument("--probe-timeout", type=float, default=0.25)
    args = parser.parse_args()
    if not (0.05 <= args.probe_timeout <= 1.0):
        parser.error("probe timeout must be between 0.05 and 1 second")
    if not (args.probe_timeout <= args.overall_timeout <= 10.0):
        parser.error("overall timeout must include one probe and not exceed 10 seconds")

    deadline = time.monotonic() + args.overall_timeout
    failure = "stationd readiness timed out"
    while time.monotonic() < deadline:
        if not process_exists(args.expected_pid):
            failure = "stationd exited before becoming ready"
            break
        remaining = deadline - time.monotonic()
        with tempfile.TemporaryFile() as probe_output:
            probe = subprocess.Popen(
                [args.executable, "--admin-socket", args.socket, "--status"],
                stdin=subprocess.DEVNULL, stdout=probe_output,
                stderr=subprocess.DEVNULL,
            )
            try:
                probe.wait(timeout=min(args.probe_timeout, max(0.01, remaining)))
            except subprocess.TimeoutExpired:
                probe.kill()
                probe.wait()
                failure = "stationd readiness probe exceeded its per-probe bound"
                probe_returncode = None
            else:
                probe_returncode = probe.returncode
            probe_output.seek(0, os.SEEK_END)
            response_size = probe_output.tell()
            probe_output.seek(0)
            if response_size > MAX_RESPONSE_BYTES:
                failure = "stationd status exceeds response bound"
                break
            payload = probe_output.read(MAX_RESPONSE_BYTES + 1)
            if probe_returncode == 0:
                try:
                    ready = validate(payload, args.expected_pid)
                except (ValueError, json.JSONDecodeError) as error:
                    failure = str(error)
                    break
                sys.stdout.write(json.dumps(ready, separators=(",", ":")) + "\n")
                return 0
            if probe_returncode is not None:
                failure = f"stationd status unavailable (exit {probe_returncode})"
        time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))

    details = diagnostic(args.stderr_log)
    sys.stderr.write(f"{failure}\n")
    if details:
        sys.stderr.write(details[-MAX_DIAGNOSTIC_BYTES:] + ("\n" if not details.endswith("\n") else ""))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
