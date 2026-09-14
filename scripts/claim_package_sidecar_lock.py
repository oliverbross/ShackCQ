#!/usr/bin/env python3
"""Atomically claim a bounded, metadata-bearing package sidecar lock."""

from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import stat
import sys


MAX_METADATA_BYTES = 2048


def main() -> int:
    if len(sys.argv) != 8:
        print(
            "usage: claim_package_sidecar_lock.py LOCK PID HOST STARTED_UTC "
            "SOURCE_SHA PLATFORM OUTPUT",
            file=sys.stderr,
        )
        return 64

    lock = Path(sys.argv[1])
    payload = {
        "pid": int(sys.argv[2]),
        "host": sys.argv[3][:128],
        "startedUtc": sys.argv[4],
        "sourceSha": sys.argv[5],
        "platform": sys.argv[6],
        "output": sys.argv[7][:512],
    }
    raw = (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode()
    if len(raw) > MAX_METADATA_BYTES:
        print("generated sidecar owner metadata exceeds 2048 bytes", file=sys.stderr)
        return 65

    temporary = lock.with_name(
        f".{lock.name}.claim-{os.getpid()}-{secrets.token_hex(8)}"
    )
    try:
        fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        if os.name != "nt" and stat.S_IMODE(temporary.stat().st_mode) != 0o600:
            print("generated sidecar lock metadata is not mode 0600", file=sys.stderr)
            return 66
        try:
            os.link(temporary, lock)
        except FileExistsError:
            return 17
        return 0
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    raise SystemExit(main())
