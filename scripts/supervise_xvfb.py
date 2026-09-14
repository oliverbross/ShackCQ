#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Own one Xvfb child through a pidfd and a private file control channel."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time


READY_TIMEOUT = 3.0
CONTROL_TIMEOUT = 15.0
TERM_TIMEOUT = 1.0
KILL_TIMEOUT = 2.0


def atomic_json(path: Path, value: dict[str, object]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, separators=(",", ":"), sort_keys=True)
        stream.write("\n")
    os.replace(temporary, path)


def wait_child(child: subprocess.Popen[bytes], timeout: float) -> int | None:
    try:
        return child.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        return None


def signal_child(pidfd: int, value: signal.Signals) -> None:
    signal.pidfd_send_signal(pidfd, value)


def launch_child(arguments: argparse.Namespace, display_write: int) -> subprocess.Popen[bytes]:
    if arguments.test_behavior:
        if arguments.test_behavior == "early-exit":
            source = "import sys; sys.exit(9)"
        else:
            handler = (
                "signal.signal(signal.SIGTERM, signal.SIG_IGN);"
                if arguments.test_behavior == "term-ignore"
                else ""
            )
            source = (
                "import os,signal,sys,time;"
                + handler
                + "os.write(int(sys.argv[1]),b'77\\n');time.sleep(60)"
            )
        return subprocess.Popen(
            [sys.executable, "-c", source, str(display_write), arguments.marker],
            pass_fds=(display_write,),
        )
    return subprocess.Popen(
        [
            arguments.marker,
            "-displayfd",
            str(display_write),
            "-screen",
            "0",
            "1280x720x24",
            "-nolisten",
            "tcp",
        ],
        executable=arguments.executable,
        pass_fds=(display_write,),
    )


def stop_and_reap(child: subprocess.Popen[bytes], pidfd: int) -> tuple[int, str]:
    signal_child(pidfd, signal.SIGTERM)
    status = wait_child(child, TERM_TIMEOUT)
    if status is not None:
        return status, "TERM"
    signal_child(pidfd, signal.SIGKILL)
    status = wait_child(child, KILL_TIMEOUT)
    if status is None:
        raise RuntimeError("owned Xvfb did not exit after pidfd SIGKILL")
    return status, "KILL"


def supervise(arguments: argparse.Namespace) -> int:
    ready = Path(arguments.ready)
    stop = Path(arguments.stop)
    done = Path(arguments.done)
    display = Path(arguments.display)
    read_descriptor, write_descriptor = os.pipe()
    child: subprocess.Popen[bytes] | None = None
    pidfd: int | None = None
    try:
        child = launch_child(arguments, write_descriptor)
        os.close(write_descriptor)
        write_descriptor = -1
        try:
            if os.environ.get("SHACKCQ_TEST_PIDFD_OPEN_FAILURE") == "1":
                raise OSError("injected pidfd_open failure")
            pidfd = os.pidfd_open(child.pid, 0)
        except Exception:
            child.terminate()
            if wait_child(child, TERM_TIMEOUT) is None:
                child.kill()
                if wait_child(child, KILL_TIMEOUT) is None:
                    raise RuntimeError("failed to reap child after pidfd_open failure")
            raise
        readable, _, _ = select.select([read_descriptor], [], [], READY_TIMEOUT)
        if not readable:
            status = child.poll()
            if status is not None:
                atomic_json(done, {"marker": arguments.marker, "state": "EARLY_EXIT", "status": status})
                return 3
            _, termination = stop_and_reap(child, pidfd)
            atomic_json(done, {"marker": arguments.marker, "state": "DISPLAY_TIMEOUT", "termination": termination})
            return 5
        display_number = os.read(read_descriptor, 32).decode("ascii", errors="strict").strip()
        if not display_number:
            status = wait_child(child, 0.2)
            if status is not None:
                atomic_json(done, {"marker": arguments.marker, "state": "EARLY_EXIT", "status": status})
                return 3
        if not display_number.isdecimal() or len(display_number) > 5:
            _, termination = stop_and_reap(child, pidfd)
            atomic_json(done, {"marker": arguments.marker, "state": "INVALID_DISPLAY", "termination": termination})
            return 5
        display.write_text(display_number + "\n", encoding="ascii")
        atomic_json(ready, {"display": int(display_number), "marker": arguments.marker, "supervisorPid": os.getpid()})
        deadline = time.monotonic() + CONTROL_TIMEOUT
        while time.monotonic() < deadline:
            status = child.poll()
            if status is not None:
                atomic_json(done, {"marker": arguments.marker, "state": "EARLY_EXIT", "status": status})
                return 3
            if stop.exists():
                requested_marker = stop.read_text(encoding="utf-8", errors="strict").strip()
                if requested_marker != arguments.marker:
                    _, termination = stop_and_reap(child, pidfd)
                    atomic_json(done, {"marker": arguments.marker, "state": "INVALID_STOP_REQUEST", "termination": termination})
                    return 4
                status, termination = stop_and_reap(child, pidfd)
                atomic_json(
                    done,
                    {"marker": arguments.marker, "state": "STOPPED", "status": status, "termination": termination},
                )
                return 0
            time.sleep(0.025)
        _, termination = stop_and_reap(child, pidfd)
        atomic_json(done, {"marker": arguments.marker, "state": "CONTROL_TIMEOUT", "termination": termination})
        return 5
    finally:
        os.close(read_descriptor)
        if write_descriptor >= 0:
            os.close(write_descriptor)
        if child is not None and child.poll() is None:
            if pidfd is not None:
                signal_child(pidfd, signal.SIGKILL)
            else:
                child.kill()
            child.wait(timeout=KILL_TIMEOUT)
        if pidfd is not None:
            os.close(pidfd)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--executable", required=True)
    parser.add_argument("--marker", required=True)
    parser.add_argument("--display", required=True)
    parser.add_argument("--ready", required=True)
    parser.add_argument("--stop", required=True)
    parser.add_argument("--done", required=True)
    parser.add_argument("--test-behavior", choices=("normal", "early-exit", "term-ignore"))
    value = parser.parse_args()
    if not value.marker.startswith("shackcq-") or len(value.marker) > 96:
        parser.error("marker is invalid")
    return value


if __name__ == "__main__":
    raise SystemExit(supervise(parse_arguments()))
