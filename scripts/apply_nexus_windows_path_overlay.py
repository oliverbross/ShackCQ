#!/usr/bin/env python3
"""Apply the audited tempo-fast Windows path overlay without newline drift."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import stat
import tempfile


PREIMAGE_SHA256 = "1ec101b78b44c0305af3267fe08b91caefd5f52016051600edcf2ad77b1c58a9"
POSTIMAGE_SHA256 = "03469493764276955d147e08aa2773d993c72a8996068a15e1116c585f636173"

BEFORE = b'''    let libtempo_src = manifest\n        .join("../../libtempo")\n        .canonicalize()\n        .expect("locate tempo/libtempo");\n'''
AFTER = b'''    let libtempo_src = windows_cmake_path(\n        manifest\n            .join("../../libtempo")\n            .canonicalize()\n            .expect("locate tempo/libtempo"),\n    );\n'''
ANCHOR = b'''/// Emit `rustc-link-search` entries for the gfortran and FFTW3f runtimes on macOS.\n'''
HELPER = b'''/// Rust's Windows canonicalization returns a verbatim `\\\\?\\` path. MinGW\n/// gfortran receives CMake's slash-normalized `//?/D:/...` form and treats the\n/// source operand as `//file.f90`, so preprocessing fails before compilation.\n/// CMake accepts an ordinary absolute drive path and preserves it correctly.\nfn windows_cmake_path(path: PathBuf) -> PathBuf {\n    if cfg!(windows) {\n        let display = path.to_string_lossy();\n        if let Some(ordinary) = display.strip_prefix(r"\\\\?\\") {\n            return PathBuf::from(ordinary);\n        }\n    }\n    path\n}\n\n'''


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize(raw: bytes) -> tuple[bytes, bytes]:
    without_crlf = raw.replace(b"\r\n", b"")
    if b"\r" in without_crlf:
        raise ValueError("mixed or bare-CR newline style is not supported")
    if b"\r\n" in raw and b"\n" in without_crlf:
        raise ValueError("mixed LF and CRLF newline styles are not supported")
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    normalized = raw.replace(b"\r\n", b"\n")
    return normalized, newline


def encode_newlines(normalized: bytes, newline: bytes) -> bytes:
    return normalized if newline == b"\n" else normalized.replace(b"\n", b"\r\n")


def atomic_write(path: Path, data: bytes) -> None:
    original_mode = stat.S_IMODE(path.stat().st_mode)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(fd, original_mode)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def apply_overlay(target: Path, backup: Path) -> None:
    original = target.read_bytes()
    normalized, newline = normalize(original)
    if digest(normalized) != PREIMAGE_SHA256:
        raise ValueError("Nexus overlay preimage SHA-256 does not match the pinned source")
    if normalized.count(BEFORE) != 1 or normalized.count(ANCHOR) != 1:
        raise ValueError("Nexus overlay anchors are not exact and unique")
    transformed = normalized.replace(BEFORE, AFTER).replace(ANCHOR, HELPER + ANCHOR)
    if digest(transformed) != POSTIMAGE_SHA256:
        raise ValueError("Nexus overlay postimage SHA-256 does not match the audited result")

    backup.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(original)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            atomic_write(target, encode_newlines(transformed, newline))
        except BaseException:
            atomic_write(target, original)
            raise
    except BaseException:
        try:
            backup.unlink()
        except FileNotFoundError:
            pass
        raise


def restore_overlay(target: Path, backup: Path) -> None:
    original = backup.read_bytes()
    original_normalized, _ = normalize(original)
    if digest(original_normalized) != PREIMAGE_SHA256:
        raise ValueError("Nexus overlay backup is not the exact pinned preimage")

    current = target.read_bytes()
    current_normalized, _ = normalize(current)
    postimage_matches = digest(current_normalized) == POSTIMAGE_SHA256
    atomic_write(target, original)
    if target.read_bytes() != original:
        raise ValueError("Nexus overlay exact-byte restore verification failed")
    backup.unlink()
    if not postimage_matches:
        raise ValueError("Nexus overlay target changed unexpectedly; exact backup was restored")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("apply", "restore"))
    parser.add_argument("target", type=Path)
    parser.add_argument("backup", type=Path)
    args = parser.parse_args()
    if args.action == "apply":
        apply_overlay(args.target, args.backup)
    else:
        restore_overlay(args.target, args.backup)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
