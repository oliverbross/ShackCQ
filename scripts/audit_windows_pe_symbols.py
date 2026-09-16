#!/usr/bin/env python3
"""Report local packaged PE imports whose symbols are absent from the bundled DLL."""

from __future__ import annotations

import sys
from pathlib import Path

import pefile


def normal(value: bytes | str) -> str:
    if isinstance(value, bytes):
        value = value.decode("ascii", errors="replace")
    return value.casefold()


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: audit_windows_pe_symbols.py PAYLOAD_ROOT")
    root = Path(sys.argv[1]).resolve()
    if not root.is_dir():
        raise SystemExit(f"payload root is missing: {root}")
    objects = sorted((*root.rglob("*.exe"), *root.rglob("*.dll")))
    local = {path.name.casefold(): path for path in objects if path.suffix.casefold() == ".dll"}
    exports: dict[Path, tuple[set[str], set[int]]] = {}
    missing: list[str] = []
    checked = 0
    for obj in objects:
        pe = pefile.PE(str(obj), fast_load=True)
        pe.parse_data_directories(
            directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"]]
        )
        for imported in getattr(pe, "DIRECTORY_ENTRY_IMPORT", ()):
            dependency = local.get(normal(imported.dll))
            if dependency is None:
                continue
            if dependency not in exports:
                dep_pe = pefile.PE(str(dependency), fast_load=True)
                dep_pe.parse_data_directories(
                    directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_EXPORT"]]
                )
                symbols = getattr(dep_pe, "DIRECTORY_ENTRY_EXPORT", None)
                names = {normal(item.name) for item in symbols.symbols if item.name} if symbols else set()
                ordinals = {item.ordinal for item in symbols.symbols} if symbols else set()
                exports[dependency] = (names, ordinals)
            names, ordinals = exports[dependency]
            for symbol in imported.imports:
                checked += 1
                if symbol.name is not None and normal(symbol.name) not in names:
                    missing.append(f"{obj.name}: {dependency.name}!{normal(symbol.name)}")
                elif symbol.name is None and symbol.ordinal not in ordinals:
                    missing.append(f"{obj.name}: {dependency.name}!ordinal-{symbol.ordinal}")
    print(f"PACKAGED_PE_SYMBOL_AUDIT objects={len(objects)} local_imports={checked} missing={len(missing)}")
    for item in missing:
        print(f"MISSING_PE_SYMBOL {item}")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
