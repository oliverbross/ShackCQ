#!/usr/bin/env python3
import hashlib
import sys
from pathlib import Path

root=Path(sys.argv[1])
digest=hashlib.sha256()
for path in sorted(p for p in root.rglob("*") if p.is_file()):
    digest.update(path.relative_to(root).as_posix().encode())
    digest.update(b"\0")
    digest.update(path.read_bytes())
print(digest.hexdigest())
