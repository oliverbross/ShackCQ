#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
    print("XVFB_SUPERVISOR_TEST_SKIP reason=pidfd-unavailable")
    raise SystemExit(0)


HELPER = Path(__file__).with_name("supervise_xvfb.py")


def wait_file(path: Path, timeout: float = 4.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file() and path.stat().st_size:
            return
        time.sleep(0.01)
    raise AssertionError(f"timed out waiting for {path.name}")


def run_case(behavior: str, stop_value: str | None, expected_rc: int, expected_state: str) -> dict[str, object]:
    marker = f"shackcq-xvfb-test-{behavior}"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        ready, stop, done, display = (root / name for name in ("ready.json", "stop", "done.json", "display"))
        process = subprocess.Popen(
            [
                sys.executable,
                str(HELPER),
                "--executable",
                "/test/not-used",
                "--marker",
                marker,
                "--display",
                str(display),
                "--ready",
                str(ready),
                "--stop",
                str(stop),
                "--done",
                str(done),
                "--test-behavior",
                behavior,
            ]
        )
        if behavior != "early-exit":
            wait_file(ready)
            if stop_value is not None:
                stop.write_text(stop_value.replace("$MARKER", marker) + "\n", encoding="utf-8")
        status = process.wait(timeout=6)
        assert status == expected_rc, (behavior, status)
        wait_file(done)
        outcome = json.loads(done.read_text(encoding="utf-8"))
        assert outcome["state"] == expected_state, outcome
        assert outcome["marker"] == marker
        return outcome


def pidfd_open_failure_reaps_child() -> None:
    marker = "shackcq-xvfb-test-pidfd-open-failure"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        environment = os.environ.copy()
        environment["SHACKCQ_TEST_PIDFD_OPEN_FAILURE"] = "1"
        process = subprocess.run(
            [
                sys.executable,
                str(HELPER),
                "--executable",
                "/test/not-used",
                "--marker",
                marker,
                "--display",
                str(root / "display"),
                "--ready",
                str(root / "ready"),
                "--stop",
                str(root / "stop"),
                "--done",
                str(root / "done"),
                "--test-behavior",
                "normal",
            ],
            env=environment,
            check=False,
            capture_output=True,
            timeout=4,
        )
        assert process.returncode != 0
        for command_line in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                assert marker.encode() not in command_line.read_bytes().split(b"\0")
            except (FileNotFoundError, PermissionError, ProcessLookupError):
                pass


normal = run_case("normal", "$MARKER", 0, "STOPPED")
assert normal["termination"] == "TERM"
invalid = run_case("normal", "shackcq-wrong-marker", 4, "INVALID_STOP_REQUEST")
assert invalid["termination"] == "TERM"
run_case("early-exit", None, 3, "EARLY_EXIT")
ignored = run_case("term-ignore", "$MARKER", 0, "STOPPED")
assert ignored["termination"] == "KILL"
pidfd_open_failure_reaps_child()
print("XVFB_SUPERVISOR_TEST_OK normal=true invalid=true early=true escalation=true pidfd-failure=true")
