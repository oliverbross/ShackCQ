#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
macos="$repo/scripts/build_nexus_desktop_macos.sh"
contract_scratch=$(mktemp -d)
PYTHONPYCACHEPREFIX="$contract_scratch/pycache"
export PYTHONPYCACHEPREFIX
mac_sidecar_dir="$repo/desktop/shackcq-tauri/binaries"
mac_lock="$mac_sidecar_dir/.shackcq-package-aarch64-apple-darwin.lock"
mac_sentinel="$mac_sidecar_dir/unrelated-mac-sentinel-$$.keep"
mac_pid=
mac_lock_owner_pid=
cleanup_contract_test() {
  cleanup_status=$?
  trap - EXIT
  set +e
  if [ -n "$mac_pid" ]; then
    kill "$mac_pid" 2>/dev/null || true
    wait "$mac_pid" 2>/dev/null || true
  fi
  if [ -n "$mac_lock_owner_pid" ] && { [ -e "$mac_lock" ] || [ -L "$mac_lock" ]; }; then
    lock_matches=0
    if python3 - "$mac_lock" "$mac_lock_owner_pid" <<'PY'
import json
from pathlib import Path
import sys

owner = json.loads(Path(sys.argv[1]).read_text())
raise SystemExit(0 if owner.get("pid") == int(sys.argv[2]) else 1)
PY
    then
      lock_matches=1
    fi
    if [ "$lock_matches" = 1 ] && ! kill -0 "$mac_lock_owner_pid" 2>/dev/null; then
      owned_absent=1
      for owned_name in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
        owned_path="$mac_sidecar_dir/$owned_name-aarch64-apple-darwin"
        rm -f -- "$owned_path" || owned_absent=0
        { [ ! -e "$owned_path" ] && [ ! -L "$owned_path" ]; } || owned_absent=0
      done
      if [ "$owned_absent" = 1 ]; then
        rm -f -- "$mac_lock"
      else
        echo "refusing to release mac package lock while an owned path remains: $mac_lock" >&2
        cleanup_status=1
      fi
    else
      echo "refusing to remove unverified or live mac package lock: $mac_lock" >&2
      cleanup_status=1
    fi
  fi
  rm -f -- "$mac_sentinel"
  rmdir "$mac_sidecar_dir" 2>/dev/null || true
  rm -rf "$contract_scratch"
  exit "$cleanup_status"
}
trap cleanup_contract_test EXIT

bash -n "$candidate"
sh -n "$macos"
python3 -m py_compile "$repo/scripts/claim_package_sidecar_lock.py"
test "$(grep -c '^trap cleanup EXIT' "$candidate")" = 1
grep -F "trap 'exit 143' TERM" "$candidate" >/dev/null
test "$(grep -c '^trap cleanup EXIT$' "$macos")" = 1
grep -F "trap 'exit 129' HUP" "$macos" >/dev/null
grep -F "trap 'exit 130' INT" "$macos" >/dev/null
grep -F "trap 'exit 143' TERM" "$macos" >/dev/null
! grep -F 'rm -rf "$repo/desktop/shackcq-tauri/binaries"' "$candidate" "$macos"
grep -F 'rm -f -- "$generated_sidecar"' "$candidate" "$macos" >/dev/null
grep -F 'rmdir "$generated_sidecar_dir"' "$candidate" "$macos" >/dev/null
grep -F '.shackcq-package-$target.lock' "$candidate" >/dev/null
grep -F '.shackcq-package-aarch64-apple-darwin.lock' "$macos" >/dev/null
grep -F '[ -e "$candidate" ] || [ -L "$candidate" ]' "$candidate" >/dev/null
grep -F 'generated sidecar ownership lock retained:' "$candidate" "$macos" >/dev/null
grep -F 'after proving the recorded PID inactive' "$candidate" "$macos" >/dev/null
grep -F 'claim_package_sidecar_lock.py' "$candidate" "$macos" >/dev/null
grep -F 'metadata-bearing generated sidecar ownership lock retained:' "$candidate" "$macos" >/dev/null
grep -F 'taskkill.exe /PID "$main_pid" /T /F' "$candidate" >/dev/null
grep -F 'pgrep -f "$mount_point/.*/shackcq-(desktop|stationd|nexus-runtime)"' "$macos" >/dev/null
! grep -Eq 'taskkill\.exe .* /IM|(^|[[:space:]])pkill([[:space:]]|$)' "$candidate" "$macos"
! grep -Eq 'main_rc.*124|main_rc" -eq 124' "$candidate"

grep -F 'socket_name="shackcq-package-${RANDOM}-${RANDOM}.sock"' "$candidate" >/dev/null
! sed -n '/^accept_linux_payload()/,/^}/p' "$candidate" | grep -Eq -- '--admin-socket "\$payload_tmp/'
socket_name=shackcq-package-32767-32767.sock
case "$socket_name" in
  [A-Za-z0-9]* ) ;;
  * ) exit 1 ;;
esac
case "$socket_name" in
  *[!A-Za-z0-9._-]* ) exit 1 ;;
esac
test "${#socket_name}" -le 96

mkdir -p "$mac_sidecar_dir"
test ! -e "$mac_lock" && test ! -L "$mac_lock"
for mac_owned in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
  mac_owned_path="$mac_sidecar_dir/$mac_owned-aarch64-apple-darwin"
  test ! -e "$mac_owned_path" && test ! -L "$mac_owned_path"
done
printf 'foreign file must survive\n' >"$mac_sentinel"
mac_ready="$contract_scratch/mac-lock-ready"
QT_PREFIX=/nonexistent SHACKCQ_TEST_MAC_GENERATED_SIDECARS=1 \
SHACKCQ_TEST_MAC_SIDECAR_LOCK_READY="$mac_ready" \
  "$macos" "$contract_scratch/output" >"$contract_scratch/mac.log" 2>&1 &
mac_pid=$!
mac_lock_owner_pid=$mac_pid
mac_observed=0
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 \
  21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do
  if [ -s "$mac_ready" ]; then
    mac_observed=1
    break
  fi
  sleep 0.05
done
test "$mac_observed" = 1
test -s "$mac_lock"
python3 - "$mac_lock" "$mac_lock_owner_pid" <<'PY'
import json
from pathlib import Path
import sys

owner = json.loads(Path(sys.argv[1]).read_text())
assert owner["pid"] == int(sys.argv[2])
PY
kill -TERM "$mac_pid"
set +e
wait "$mac_pid"
mac_status=$?
set -e
mac_pid=
test "$mac_status" = 143
test ! -e "$mac_lock" && test ! -L "$mac_lock"
for mac_owned in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
  mac_owned_path="$mac_sidecar_dir/$mac_owned-aarch64-apple-darwin"
  test ! -e "$mac_owned_path" && test ! -L "$mac_owned_path"
done
test "$(cat "$mac_sentinel")" = 'foreign file must survive'
rm -f -- "$mac_sentinel"
rmdir "$mac_sidecar_dir" 2>/dev/null || true

codesign_line=$(grep -n 'codesign --force --deep --sign' "$macos" | cut -d: -f1)
manifest_line=$(grep -n -- '--manifest "$manifest"' "$macos" | cut -d: -f1)
test "$manifest_line" -gt "$codesign_line"

echo 'NEXUS_PACKAGE_CLEANUP_CONTRACT_OK exact-owned-processes=true clean-exit-required=true post-sign-manifest=true linux-admin-socket=bounded-name mac-term=143-lock-clean'
