#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Deterministic lifecycle tests for the packaged stationd readiness harness."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent
HARNESS = ROOT / "wait_stationd_ready.py"
OWNER = "b" * 64
WRONG = "c" * 64
SECRET = "d" * 64

MOCK = r'''#!/usr/bin/env python3
import json, os, signal, sys, time
from pathlib import Path
root = Path(os.environ["MOCK_ROOT"])
scenario = os.environ.get("MOCK_SCENARIO", "valid")
args = sys.argv[1:]
if "--foreground" in args:
    root.joinpath("pid").write_text(str(os.getpid()))
    if scenario == "early":
        raise SystemExit(7)
    def terminate(*_):
        raise SystemExit(143)
    signal.signal(signal.SIGTERM, terminate)
    while not root.joinpath("stop").exists():
        time.sleep(0.01)
    raise SystemExit(0)
if "--status" in args:
    count_path = root / "count"
    count = int(count_path.read_text()) + 1 if count_path.exists() else 1
    count_path.write_text(str(count))
    if scenario == "transient" and count < 3:
        raise SystemExit(4)
    if scenario == "timeout":
        time.sleep(1)
        raise SystemExit(4)
    pid = int(root.joinpath("pid").read_text())
    if scenario == "malformed":
        print("not-json")
        raise SystemExit(0)
    if scenario == "oversize":
        print("x" * 70000)
        raise SystemExit(0)
    payload = {"ok": True, "result": {
        "processId": pid + (1 if scenario == "mismatch" else 0),
        "hardwareAutoconnect": scenario == "autoconnect",
        "nativeIngress": {
            "available": scenario != "unavailable",
            "contract": {"major": 9 if scenario == "protocol" else 1, "minor": 0},
            "authentication": "WRONG" if scenario == "auth" else "HMAC_SHA256_OS_VAULT",
        },
    }}
    print(json.dumps(payload))
    raise SystemExit(0)
if "--stop" in args:
    token = args[args.index("--native-owner-token") + 1]
    if token != os.environ["MOCK_OWNER"]:
        raise SystemExit(9)
    root.joinpath("stop").write_text("stop")
    raise SystemExit(0)
raise SystemExit(2)
'''


def run_case(scenario: str) -> tuple[subprocess.Popen[bytes], Path, dict[str, str]]:
    root = Path(tempfile.mkdtemp(prefix="shackcq-stationd-ready-"))
    executable = root / "stationd"
    executable.write_text(MOCK)
    executable.chmod(0o700)
    stderr_log = root / "stationd.stderr"
    stderr_log.write_text("x" * 4096 + SECRET)
    stderr_log.chmod(0o600)
    env = os.environ.copy()
    env.update(MOCK_ROOT=str(root), MOCK_SCENARIO=scenario, MOCK_OWNER=OWNER)
    daemon = subprocess.Popen(
        [str(executable), "--foreground"], env=env,
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=stderr_log.open("ab"),
    )
    for _ in range(100):
        if (root / "pid").exists() or daemon.poll() is not None:
            break
        time.sleep(0.01)
    return daemon, root, env


def probe(daemon: subprocess.Popen[bytes], root: Path, env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(HARNESS), "--executable", str(root / "stationd"),
         "--socket", "fixture.sock", "--expected-pid", str(daemon.pid),
         "--stderr-log", str(root / "stationd.stderr"),
         "--overall-timeout", "0.8", "--probe-timeout", "0.15"],
        env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=1.5, check=False,
    )


def stop(daemon: subprocess.Popen[bytes], root: Path, env: dict[str, str]) -> None:
    if daemon.poll() is None:
        subprocess.run([str(root / "stationd"), "--stop", "--native-owner-token", OWNER],
                       env=env, timeout=1, check=True)
    daemon.wait(timeout=1)


def main() -> int:
    daemon, root, env = run_case("transient")
    result = probe(daemon, root, env)
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["result"]["processId"] == daemon.pid
    assert root.joinpath("count").read_text() == "3", "readiness must not issue a duplicate status"
    wrong = subprocess.run([str(root / "stationd"), "--stop", "--native-owner-token", WRONG],
                           env=env, timeout=1, check=False)
    assert wrong.returncode != 0 and daemon.poll() is None
    stop(daemon, root, env)
    shutil.rmtree(root)

    failures = {
        "timeout": "per-probe bound",
        "malformed": "Expecting value",
        "oversize": "exceeds response bound",
        "mismatch": "processId mismatch",
        "autoconnect": "autoconnect is not disabled",
        "unavailable": "native ingress is unavailable",
        "protocol": "protocol mismatch",
        "auth": "authentication mismatch",
    }
    for scenario, message in failures.items():
        daemon, root, env = run_case(scenario)
        started = time.monotonic()
        result = probe(daemon, root, env)
        assert result.returncode != 0 and message in result.stderr, (scenario, result.stderr)
        assert time.monotonic() - started < 1.4
        assert SECRET not in result.stderr and len(result.stderr.encode()) <= 2300
        assert stat.S_IMODE(root.joinpath("stationd.stderr").stat().st_mode) == 0o600
        stop(daemon, root, env)
        shutil.rmtree(root)

    daemon, root, env = run_case("early")
    daemon.wait(timeout=1)
    result = probe(daemon, root, env)
    assert result.returncode != 0 and "exited before becoming ready" in result.stderr
    shutil.rmtree(root)

    print("STATIOND_READINESS_TEST_OK transient=true single-response=true timeout=true early-exit=true validation=true diagnostics=true stop-reap=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
