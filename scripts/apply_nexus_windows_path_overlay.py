#!/usr/bin/env python3
"""Apply and recover the audited tempo-fast Windows path overlay exactly."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile


PREIMAGE_SHA256 = "1ec101b78b44c0305af3267fe08b91caefd5f52016051600edcf2ad77b1c58a9"
POSTIMAGE_SHA256 = "03469493764276955d147e08aa2773d993c72a8996068a15e1116c585f636173"
RECOVERY_VERSION = 1

BEFORE = b'''    let libtempo_src = manifest\n        .join("../../libtempo")\n        .canonicalize()\n        .expect("locate tempo/libtempo");\n'''
AFTER = b'''    let libtempo_src = windows_cmake_path(\n        manifest\n            .join("../../libtempo")\n            .canonicalize()\n            .expect("locate tempo/libtempo"),\n    );\n'''
ANCHOR = b'''/// Emit \x60rustc-link-search\x60 entries for the gfortran and FFTW3f runtimes on macOS.\n'''
HELPER = (
    b"/// Rust's Windows canonicalization returns a verbatim \x60\\\\?\\\x60 path. MinGW\n"
    b"/// gfortran receives CMake's slash-normalized \x60//?/D:/...\x60 form and treats the\n"
    b"/// source operand as \x60//file.f90\x60, so preprocessing fails before compilation.\n"
    b"/// CMake accepts an ordinary absolute drive path and preserves it correctly.\n"
    b"fn windows_cmake_path(path: PathBuf) -> PathBuf {\n"
    b"    if cfg!(windows) {\n"
    b"        let display = path.to_string_lossy();\n"
    b'        if let Some(ordinary) = display.strip_prefix(r"\\\\?\\") {\n'
    b"            return PathBuf::from(ordinary);\n"
    b"        }\n"
    b"    }\n"
    b"    path\n"
    b"}\n\n"
)

_failure_plan = [
    item
    for item in os.environ.get("SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES", "").split(",")
    if item
]
if any(item not in ("before", "after") for item in _failure_plan):
    raise ValueError("invalid SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES value")


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize(raw: bytes) -> tuple[bytes, bytes]:
    without_crlf = raw.replace(b"\r\n", b"")
    if b"\r" in without_crlf:
        raise ValueError("mixed or bare-CR newline style is not supported")
    if b"\r\n" in raw and b"\n" in without_crlf:
        raise ValueError("mixed LF and CRLF newline styles are not supported")
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    return raw.replace(b"\r\n", b"\n"), newline


def encode_newlines(normalized: bytes, newline: bytes) -> bytes:
    return normalized if newline == b"\n" else normalized.replace(b"\n", b"\r\n")


def write_exclusive(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def atomic_write(path: Path, data: bytes, mode: int) -> None:
    failure = _failure_plan.pop(0) if _failure_plan else None
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if failure == "before":
            raise OSError("injected atomic-write failure before replace")
        os.replace(temporary, path)
        if failure == "after":
            raise OSError("injected atomic-write failure after replace")
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def metadata_path(backup: Path) -> Path:
    return backup.with_name(f"{backup.name}.metadata.json")


def create_recovery(backup: Path, original: bytes, mode: int) -> None:
    backup.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup.parent, 0o700)
    write_exclusive(backup, original)
    metadata = {
        "version": RECOVERY_VERSION,
        "rawSha256": digest(original),
        "normalizedSha256": PREIMAGE_SHA256,
        "mode": mode,
    }
    try:
        write_exclusive(
            metadata_path(backup),
            (json.dumps(metadata, sort_keys=True, separators=(",", ":")) + "\n").encode(),
        )
    except BaseException:
        backup.unlink()
        raise


def load_recovery(backup: Path) -> tuple[bytes, int]:
    original = backup.read_bytes()
    metadata = json.loads(metadata_path(backup).read_text(encoding="utf-8"))
    if set(metadata) != {"version", "rawSha256", "normalizedSha256", "mode"}:
        raise ValueError("Nexus overlay recovery metadata has unexpected fields")
    mode = metadata["mode"]
    if (
        metadata["version"] != RECOVERY_VERSION
        or metadata["rawSha256"] != digest(original)
        or metadata["normalizedSha256"] != PREIMAGE_SHA256
        or not isinstance(mode, int)
        or mode < 0
        or mode > 0o7777
    ):
        raise ValueError("Nexus overlay recovery metadata failed validation")
    normalized, _ = normalize(original)
    if digest(normalized) != PREIMAGE_SHA256:
        raise ValueError("Nexus overlay backup is not the exact pinned preimage")
    return original, mode


def remove_recovery(backup: Path) -> None:
    metadata_path(backup).unlink()
    backup.unlink()


def recover(target: Path, backup: Path) -> None:
    original, mode = load_recovery(backup)
    atomic_write(target, original, mode)
    if target.read_bytes() != original or stat.S_IMODE(target.stat().st_mode) != mode:
        raise ValueError("Nexus overlay exact byte-and-mode restore verification failed")
    remove_recovery(backup)


def verify_preimage(target: Path) -> None:
    raw = target.read_bytes()
    normalized, _ = normalize(raw)
    if digest(normalized) != PREIMAGE_SHA256:
        raise ValueError("Nexus source is not the exact pinned overlay preimage")


def apply_overlay(target: Path, backup: Path) -> None:
    original = target.read_bytes()
    original_mode = stat.S_IMODE(target.stat().st_mode)
    normalized, newline = normalize(original)
    if digest(normalized) != PREIMAGE_SHA256:
        raise ValueError("Nexus overlay preimage SHA-256 does not match the pinned source")
    if normalized.count(BEFORE) != 1 or normalized.count(ANCHOR) != 1:
        raise ValueError("Nexus overlay anchors are not exact and unique")
    transformed = normalized.replace(BEFORE, AFTER).replace(ANCHOR, HELPER + ANCHOR)
    if digest(transformed) != POSTIMAGE_SHA256:
        raise ValueError("Nexus overlay postimage SHA-256 does not match the audited result")

    create_recovery(backup, original, original_mode)
    try:
        atomic_write(target, encode_newlines(transformed, newline), original_mode)
    except BaseException as apply_error:
        try:
            recover(target, backup)
        except BaseException as recovery_error:
            raise RuntimeError(
                f"overlay apply failed and recovery failed; retain {backup.parent}: "
                f"{recovery_error}"
            ) from apply_error
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("apply", "restore", "verify-preimage"))
    parser.add_argument("target", type=Path)
    parser.add_argument("backup", nargs="?", type=Path)
    args = parser.parse_args()
    if args.action == "verify-preimage":
        verify_preimage(args.target)
    elif args.backup is None:
        parser.error("apply and restore require BACKUP")
    elif args.action == "apply":
        apply_overlay(args.target, args.backup)
    else:
        recover(args.target, args.backup)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
