#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
macos="$repo/scripts/build_nexus_desktop_macos.sh"

bash -n "$candidate"
sh -n "$macos"
test "$(grep -c '^trap cleanup EXIT' "$candidate")" = 1
grep -F "trap 'exit 143' TERM" "$candidate" >/dev/null
test "$(grep -c '^trap cleanup EXIT HUP INT TERM$' "$macos")" = 1
! grep -F 'rm -rf "$repo/desktop/shackcq-tauri/binaries"' "$candidate" "$macos"
grep -F 'rm -f -- "$generated_sidecar"' "$candidate" "$macos" >/dev/null
grep -F 'rmdir "$generated_sidecar_dir"' "$candidate" "$macos" >/dev/null
grep -F '.shackcq-package-$target.lock' "$candidate" >/dev/null
grep -F '.shackcq-package-aarch64-apple-darwin.lock' "$macos" >/dev/null
grep -F '[ -e "$candidate" ] || [ -L "$candidate" ]' "$candidate" >/dev/null
grep -F 'generated sidecar ownership lock retained:' "$candidate" "$macos" >/dev/null
grep -F 'after proving the recorded PID inactive' "$candidate" "$macos" >/dev/null
grep -F 'OWNER.json' "$candidate" "$macos" >/dev/null
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

codesign_line=$(grep -n 'codesign --force --deep --sign' "$macos" | cut -d: -f1)
manifest_line=$(grep -n -- '--manifest "$manifest"' "$macos" | cut -d: -f1)
test "$manifest_line" -gt "$codesign_line"

echo 'NEXUS_PACKAGE_CLEANUP_CONTRACT_OK exact-owned-processes=true clean-exit-required=true post-sign-manifest=true linux-admin-socket=bounded-name'
