#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only

from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import subprocess
import sys


if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
    print("PIDFD_PROCESS_GUARD_TEST_SKIP reason=pidfd-unavailable")
    raise SystemExit(0)


GUARD = Path(__file__).with_name("pidfd_process_guard.py")
SOCKET = "shackcq-pidfd-test.sock"


def child(ignore_term: bool = False, exit_now: bool = False) -> subprocess.Popen[bytes]:
    source = "import signal,sys,time;"
    if ignore_term:
        source += "signal.signal(signal.SIGTERM,signal.SIG_IGN);print('READY',flush=True);"
    source += "sys.exit(0)" if exit_now else "time.sleep(60)"
    process = subprocess.Popen(
        [sys.executable, "-c", source, SOCKET],
        stdout=subprocess.PIPE if ignore_term else subprocess.DEVNULL,
    )
    if ignore_term:
        assert process.stdout is not None and process.stdout.readline() == b"READY\n"
    return process


def guard(mode: str, process: subprocess.Popen[bytes], socket: str = SOCKET) -> tuple[int, dict[str, object]]:
    completed = subprocess.run(
        [
            sys.executable,
            str(GUARD),
            mode,
            "--pid",
            str(process.pid),
            "--executable",
            sys.executable,
            "--socket",
            socket,
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=5,
    )
    return completed.returncode, json.loads(completed.stdout)


normal = child()
status, outcome = guard("terminate", normal)
assert status == 0 and outcome == {"signal": "TERM", "state": "TERMINATED"}
normal.wait(timeout=2)

mismatch = child()
status, outcome = guard("terminate", mismatch, "shackcq-wrong.sock")
assert status == 4 and outcome == {"state": "IDENTITY_MISMATCH"}
assert mismatch.poll() is None
status, _ = guard("terminate", mismatch)
assert status == 0
mismatch.wait(timeout=2)

gone = child(exit_now=True)
gone.wait(timeout=2)
status, outcome = guard("check-exited", gone)
assert status == 0 and outcome == {"state": "ALREADY_EXITED"}

ignored = child(ignore_term=True)
status, outcome = guard("terminate", ignored)
assert status == 0 and outcome == {"signal": "KILL", "state": "TERMINATED"}
ignored.wait(timeout=2)

print("PIDFD_PROCESS_GUARD_TEST_OK normal=true mismatch=true exited=true escalation=true")
