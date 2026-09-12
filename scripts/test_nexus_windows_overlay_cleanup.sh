#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
overlay_tool="$repo/scripts/apply_nexus_windows_path_overlay.py"
source_file="$repo/third_party/nexus/crates/tempo-fast-sys/build.rs"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

test -z "$(git -C "$repo/third_party/nexus" status --short)"
test "$(grep -Ec '^trap (cleanup )?EXIT$|^trap - EXIT$' "$candidate")" = 1
! grep -Eq 'main_rc.*124|main_rc" -eq 124' "$candidate"

for style in lf crlf; do
  fixture="$scratch/build-$style.rs"
  backup="$scratch/build-$style.backup"
  if [ "$style" = lf ]; then
    cp "$source_file" "$fixture"
  else
    python3 - "$source_file" "$fixture" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_bytes()
assert b"\r" not in source
Path(sys.argv[2]).write_bytes(source.replace(b"\n", b"\r\n"))
PY
  fi
  cp "$fixture" "$fixture.original"
  python3 "$overlay_tool" apply "$fixture" "$backup"
  if [ "$style" = lf ]; then
    ! grep -q "$(printf '\r')" "$fixture"
  else
    python3 - "$fixture" <<'PY'
from pathlib import Path
import sys

raw = Path(sys.argv[1]).read_bytes()
assert b"\r\n" in raw
assert b"\n" not in raw.replace(b"\r\n", b"")
PY
  fi
  python3 "$overlay_tool" restore "$fixture" "$backup"
  cmp "$fixture.original" "$fixture"

  bad_fixture="$scratch/build-$style-bad.rs"
  bad_backup="$scratch/build-$style-bad.backup"
  cp "$fixture.original" "$bad_fixture"
  if [ "$style" = lf ]; then
    printf '\n// unexpected preimage mutation\n' >> "$bad_fixture"
  else
    printf '\r\n// unexpected preimage mutation\r\n' >> "$bad_fixture"
  fi
  set +e
  python3 "$overlay_tool" apply "$bad_fixture" "$bad_backup" >/dev/null 2>&1
  bad_status=$?
  set -e
  test "$bad_status" != 0
  test ! -e "$bad_backup"

  changed_fixture="$scratch/build-$style-changed.rs"
  changed_backup="$scratch/build-$style-changed.backup"
  cp "$fixture.original" "$changed_fixture"
  cp "$changed_fixture" "$changed_fixture.original"
  python3 "$overlay_tool" apply "$changed_fixture" "$changed_backup"
  if [ "$style" = lf ]; then
    printf '\n// unexpected postimage mutation\n' >> "$changed_fixture"
  else
    printf '\r\n// unexpected postimage mutation\r\n' >> "$changed_fixture"
  fi
  set +e
  python3 "$overlay_tool" restore "$changed_fixture" "$changed_backup" >/dev/null 2>&1
  changed_status=$?
  set -e
  test "$changed_status" != 0
  cmp "$changed_fixture.original" "$changed_fixture"
  test ! -e "$changed_backup"
done

SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-success"
test -z "$(git -C "$repo/third_party/nexus" status --short)"

set +e
SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=failure \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-failure"
failure_status=$?
set -e
test "$failure_status" = 73
test -z "$(git -C "$repo/third_party/nexus" status --short)"

echo "NEXUS_WINDOWS_OVERLAY_CLEANUP_OK success=clean failure=clean"
