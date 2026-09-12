#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
overlay_tool="$repo/scripts/apply_nexus_windows_path_overlay.py"
source_file="$repo/third_party/nexus/crates/tempo-fast-sys/build.rs"
scratch=$(mktemp -d)
test_sidecar_dir="$repo/desktop/shackcq-tauri/binaries"
test_sidecar_sentinel="$test_sidecar_dir/unrelated-sentinel.keep"
test_exact_sidecar=
lock_holder_pid=
lock_ready="$scratch/sidecar-lock-ready"
cleanup_test() {
  if [ -n "$lock_holder_pid" ]; then
    printf 'release\n' >"$lock_ready.release"
    kill "$lock_holder_pid" 2>/dev/null || true
    wait "$lock_holder_pid" 2>/dev/null || true
  fi
  rm -rf "$scratch"
  rm -f -- "$test_sidecar_sentinel"
  [ -z "$test_exact_sidecar" ] || rm -f -- "$test_exact_sidecar"
  rmdir "$test_sidecar_dir" 2>/dev/null || true
}
trap cleanup_test EXIT

test -z "$(git -C "$repo/third_party/nexus" status --short)"
test "$(grep -Ec '^trap (cleanup )?EXIT$|^trap - EXIT$' "$candidate")" = 1
! grep -Eq 'main_rc.*124|main_rc" -eq 124' "$candidate"
grep -Fq 'fftw_rust_lib=$(cygpath -m "$fftw_rust_lib")' "$candidate"
grep -Fq 'export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }-Lnative=$fftw_rust_lib"' "$candidate"
grep -Fq 'x86_64-w64-mingw32-gcc -print-file-name=libfftw3f.a' "$candidate"
grep -Fq 'if ! cmp "$expected_archive" "$fftw_gcc_archive"; then' "$candidate"

cygpath() {
  case "$1" in
    -m) printf '%s\n' "$SHACKCQ_TEST_MIXED_LIB" ;;
    -u) printf '%s\n' "$2" ;;
    *) return 64 ;;
  esac
}
x86_64-w64-mingw32-gcc() {
  test "$1" = -print-file-name=libfftw3f.a
  printf '%s\n' "$SHACKCQ_TEST_GCC_ARCHIVE"
}
export -f cygpath x86_64-w64-mingw32-gcc

fftw_valid="$scratch/fftw-valid"
mkdir -p "$fftw_valid/lib"
printf 'expected archive\n' >"$fftw_valid/lib/libfftw3f.a"
valid_log="$scratch/fftw-valid.log"
FFTW_MINGW_PREFIX="$fftw_valid" \
SHACKCQ_TEST_MIXED_LIB=C:/shackcq-fftw/lib \
SHACKCQ_TEST_GCC_ARCHIVE="$fftw_valid/lib/libfftw3f.a" \
SHACKCQ_TEST_WINDOWS_FFTW_LINK=1 RUSTFLAGS=-Cdebuginfo=1 \
  "$candidate" windows-x64 "$scratch/unused-valid" >"$valid_log"
grep -Fx 'RUSTFLAGS=-Cdebuginfo=1 -Lnative=C:/shackcq-fftw/lib' "$valid_log"

for failure in relative whitespace missing mismatch; do
  failure_prefix="$scratch/fftw-$failure"
  mkdir -p "$failure_prefix/lib"
  printf 'expected archive\n' >"$failure_prefix/lib/libfftw3f.a"
  mixed_lib=C:/shackcq-fftw/lib
  resolved_archive="$failure_prefix/lib/libfftw3f.a"
  case "$failure" in
    relative) mixed_lib=relative/path ;;
    whitespace) mixed_lib='C:/path with space/lib' ;;
    missing) rm "$failure_prefix/lib/libfftw3f.a" ;;
    mismatch)
      resolved_archive="$scratch/different-libfftw3f.a"
      printf 'different archive\n' >"$resolved_archive"
      ;;
  esac
  set +e
  FFTW_MINGW_PREFIX="$failure_prefix" \
  SHACKCQ_TEST_MIXED_LIB="$mixed_lib" \
  SHACKCQ_TEST_GCC_ARCHIVE="$resolved_archive" \
  SHACKCQ_TEST_WINDOWS_FFTW_LINK=1 \
    "$candidate" windows-x64 "$scratch/unused-$failure" >/dev/null 2>&1
  failure_status=$?
  set -e
  test "$failure_status" != 0
done

SHACKCQ_TEST_SIDECAR_LOCK_READY="$lock_ready" \
  "$candidate" windows-x64 "$scratch/lock-holder" &
lock_holder_pid=$!
lock_observed=0
for _ in {1..100}; do
  if [ -s "$lock_ready" ]; then
    lock_observed=1
    break
  fi
  sleep 0.05
done
test "$lock_observed" = 1
set +e
SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
  "$candidate" windows-x64 "$scratch/lock-contender" >/dev/null 2>&1
contender_status=$?
set -e
test "$contender_status" != 0
printf 'release\n' >"$lock_ready.release"
wait "$lock_holder_pid"
lock_holder_pid=
test ! -e "$test_sidecar_dir/.shackcq-package-x86_64-pc-windows-gnu.lock"

lock_ready="$scratch/sidecar-abort-ready"
SHACKCQ_TEST_SIDECAR_LOCK_READY="$lock_ready" \
  "$candidate" windows-x64 "$scratch/lock-abort" &
lock_holder_pid=$!
lock_observed=0
for _ in {1..100}; do
  if [ -s "$lock_ready" ]; then
    lock_observed=1
    break
  fi
  sleep 0.05
done
test "$lock_observed" = 1
set +e
kill -TERM "$lock_holder_pid"
wait "$lock_holder_pid"
abort_status=$?
set -e
lock_holder_pid=
test "$abort_status" = 143
test ! -e "$test_sidecar_dir/.shackcq-package-x86_64-pc-windows-gnu.lock"

mkdir -p "$test_sidecar_dir"
exact_sidecar="$test_sidecar_dir/shackcq-nexus-runtime-x86_64-pc-windows-gnu.exe"
test_exact_sidecar=$exact_sidecar
for preexisting_kind in file dangling-symlink; do
  case "$preexisting_kind" in
    file) printf 'pre-existing\n' >"$exact_sidecar" ;;
    dangling-symlink) ln -s "$scratch/missing-sidecar" "$exact_sidecar" ;;
  esac
  set +e
  SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
    "$candidate" windows-x64 "$scratch/preexisting-$preexisting_kind" >/dev/null 2>&1
  preexisting_status=$?
  set -e
  test "$preexisting_status" != 0
  if [ "$preexisting_kind" = file ]; then
    test "$(cat "$exact_sidecar")" = pre-existing
  else
    test -L "$exact_sidecar"
  fi
  rm -f -- "$exact_sidecar"
done
test_exact_sidecar=
test ! -e "$test_sidecar_dir/.shackcq-package-x86_64-pc-windows-gnu.lock"

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
  python3 - "$fixture.original" "$fixture" <<'PY'
from pathlib import Path
import stat
import sys

assert stat.S_IMODE(Path(sys.argv[1]).stat().st_mode) == stat.S_IMODE(
    Path(sys.argv[2]).stat().st_mode
)
PY

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

  for damage in deleted malformed mode unreadable; do
    changed_fixture="$scratch/build-$style-$damage.rs"
    changed_backup="$scratch/build-$style-$damage.backup"
    cp "$fixture.original" "$changed_fixture"
    cp "$changed_fixture" "$changed_fixture.original"
    python3 "$overlay_tool" apply "$changed_fixture" "$changed_backup"
    case "$damage" in
      deleted) rm "$changed_fixture" ;;
      malformed) printf '\rbare-CR and mixed\n' > "$changed_fixture" ;;
      mode) chmod 0600 "$changed_fixture" ;;
      unreadable) chmod 0000 "$changed_fixture" ;;
    esac
    python3 "$overlay_tool" restore "$changed_fixture" "$changed_backup"
    cmp "$changed_fixture.original" "$changed_fixture"
    python3 - "$changed_fixture.original" "$changed_fixture" <<'PY'
from pathlib import Path
import stat
import sys

assert stat.S_IMODE(Path(sys.argv[1]).stat().st_mode) == stat.S_IMODE(
    Path(sys.argv[2]).stat().st_mode
)
PY
    test ! -e "$changed_backup"
    test ! -e "${changed_backup}.metadata.json"
  done

  rollback_fixture="$scratch/build-$style-rollback.rs"
  rollback_backup="$scratch/build-$style-rollback.backup"
  cp "$fixture.original" "$rollback_fixture"
  cp "$rollback_fixture" "$rollback_fixture.original"
  set +e
  SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES=before \
    python3 "$overlay_tool" apply "$rollback_fixture" "$rollback_backup" >/dev/null 2>&1
  rollback_status=$?
  set -e
  test "$rollback_status" != 0
  cmp "$rollback_fixture.original" "$rollback_fixture"
  test ! -e "$rollback_backup"
  test ! -e "${rollback_backup}.metadata.json"

  retained_fixture="$scratch/build-$style-retained.rs"
  retained_backup="$scratch/build-$style-retained.backup"
  cp "$fixture.original" "$retained_fixture"
  cp "$retained_fixture" "$retained_fixture.original"
  set +e
  SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES=after,before \
    python3 "$overlay_tool" apply "$retained_fixture" "$retained_backup" >/dev/null 2>&1
  retained_status=$?
  set -e
  test "$retained_status" != 0
  test -s "$retained_backup"
  test -s "${retained_backup}.metadata.json"
  python3 - "$retained_backup" "${retained_backup}.metadata.json" <<'PY'
from pathlib import Path
import stat
import sys

assert stat.S_IMODE(Path(sys.argv[1]).stat().st_mode) == 0o600
assert stat.S_IMODE(Path(sys.argv[2]).stat().st_mode) == 0o600
assert stat.S_IMODE(Path(sys.argv[1]).parent.stat().st_mode) == 0o700
PY
  python3 "$overlay_tool" restore "$retained_fixture" "$retained_backup"
  cmp "$retained_fixture.original" "$retained_fixture"
  test ! -e "$retained_backup"
  test ! -e "${retained_backup}.metadata.json"
done

mkdir -p "$test_sidecar_dir"
printf 'foreign file must survive\n' >"$test_sidecar_sentinel"
SHACKCQ_TEST_GENERATED_SIDECARS=1 \
SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-success"
test "$(cat "$test_sidecar_sentinel")" = 'foreign file must survive'
for owned in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
  test ! -e "$test_sidecar_dir/$owned-x86_64-pc-windows-gnu.exe"
done
test -z "$(git -C "$repo/third_party/nexus" status --short)"

set +e
SHACKCQ_TEST_GENERATED_SIDECARS=1 \
SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=failure \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-failure"
failure_status=$?
set -e
test "$failure_status" = 73
test "$(cat "$test_sidecar_sentinel")" = 'foreign file must survive'
for owned in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
  test ! -e "$test_sidecar_dir/$owned-x86_64-pc-windows-gnu.exe"
done
test -z "$(git -C "$repo/third_party/nexus" status --short)"

set +e
SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES=before \
  SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-apply-failure" \
  >/dev/null 2>&1
apply_failure_status=$?
set -e
test "$apply_failure_status" != 0
test -z "$(git -C "$repo/third_party/nexus" status --short)"

retained_log="$scratch/candidate-retained.log"
set +e
SHACKCQ_TEST_OVERLAY_ATOMIC_FAILURES=after,before \
  SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP=success \
  "$candidate" windows-x64 "$repo/build/overlay-cleanup-retained" \
  >"$retained_log" 2>&1
retained_candidate_status=$?
set -e
test "$retained_candidate_status" != 0
retained_dir=$(sed -n 's/^Nexus overlay recovery retained at: //p' "$retained_log" | tail -n 1)
test -n "$retained_dir"
test -s "$retained_dir/build.rs.preimage"
test -s "$retained_dir/build.rs.preimage.metadata.json"
python3 "$overlay_tool" restore "$source_file" "$retained_dir/build.rs.preimage"
rm -rf "$retained_dir"
test -z "$(git -C "$repo/third_party/nexus" status --short)"

echo "NEXUS_WINDOWS_OVERLAY_CLEANUP_OK success=clean failure=clean recovery-retained=proven fftw-link-path=executable generated-sidecars=exact-owned-lock-contended-term-clean"
