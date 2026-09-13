#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Validate and inspect or terminate one exact Linux process through a pidfd."""

from __future__ import annotations

import argparse
import errno
import json
import os
from pathlib import Path
import select
import signal
import sys
import time


TERM_TIMEOUT = 1.0
KILL_TIMEOUT = 2.0


def emit(state: str, **fields: object) -> None:
    print(json.dumps({"state": state, **fields}, separators=(",", ":"), sort_keys=True))


def exited(pidfd: int, timeout: float) -> bool:
    poller = select.poll()
    poller.register(pidfd, select.POLLIN)
    return bool(poller.poll(max(0, int(timeout * 1000))))


def validate(pid: int, pidfd: int, expected_executable: str, expected_socket: str) -> bool:
    process_root = Path(f"/proc/{pid}")
    if not process_root.exists():
        return exited(pidfd, 0)
    try:
        executable = os.path.realpath(process_root / "exe")
        arguments = (process_root / "cmdline").read_bytes().split(b"\0")
    except OSError:
        return exited(pidfd, 0)
    expected = expected_socket.encode("utf-8")
    return executable == os.path.realpath(expected_executable) and expected in arguments


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("check-exited", "terminate"))
    parser.add_argument("--pid", required=True, type=int)
    parser.add_argument("--executable", required=True)
    parser.add_argument("--socket", required=True)
    arguments = parser.parse_args()
    if arguments.pid <= 1 or not arguments.socket or len(arguments.socket) > 96:
        parser.error("process identity is invalid")
    try:
        pidfd = os.pidfd_open(arguments.pid, 0)
    except ProcessLookupError:
        emit("ALREADY_EXITED")
        return 0
    except OSError as error:
        if error.errno == errno.ESRCH:
            emit("ALREADY_EXITED")
            return 0
        raise
    try:
        if not validate(arguments.pid, pidfd, arguments.executable, arguments.socket):
            emit("IDENTITY_MISMATCH")
            return 4
        if exited(pidfd, 0):
            emit("ALREADY_EXITED")
            return 0
        if arguments.mode == "check-exited":
            emit("STILL_RUNNING")
            return 6
        signal.pidfd_send_signal(pidfd, signal.SIGTERM)
        if exited(pidfd, TERM_TIMEOUT):
            emit("TERMINATED", signal="TERM")
            return 0
        signal.pidfd_send_signal(pidfd, signal.SIGKILL)
        if not exited(pidfd, KILL_TIMEOUT):
            emit("TERMINATION_TIMEOUT")
            return 5
        emit("TERMINATED", signal="KILL")
        return 0
    finally:
        os.close(pidfd)


if __name__ == "__main__":
    raise SystemExit(main())
