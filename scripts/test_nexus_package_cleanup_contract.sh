#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
macos="$repo/scripts/build_nexus_desktop_macos.sh"

bash -n "$candidate"
sh -n "$macos"
test "$(grep -c '^trap cleanup EXIT' "$candidate")" = 1
test "$(grep -c '^trap cleanup EXIT HUP INT TERM$' "$macos")" = 1
grep -F 'taskkill.exe /PID "$main_pid" /T /F' "$candidate" >/dev/null
grep -F 'pgrep -f "$mount_point/.*/shackcq-(desktop|stationd|nexus-runtime)"' "$macos" >/dev/null
! grep -Eq 'taskkill\.exe .* /IM|(^|[[:space:]])pkill([[:space:]]|$)' "$candidate" "$macos"
! grep -Eq 'main_rc.*124|main_rc" -eq 124' "$candidate"

codesign_line=$(grep -n 'codesign --force --deep --sign' "$macos" | cut -d: -f1)
manifest_line=$(grep -n -- '--manifest "$manifest"' "$macos" | cut -d: -f1)
test "$manifest_line" -gt "$codesign_line"

echo 'NEXUS_PACKAGE_CLEANUP_CONTRACT_OK exact-owned-processes=true clean-exit-required=true post-sign-manifest=true'
