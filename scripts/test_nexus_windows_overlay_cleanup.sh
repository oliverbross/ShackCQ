#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"

test -z "$(git -C "$repo/third_party/nexus" status --short)"
test "$(grep -Ec '^trap (cleanup )?EXIT$|^trap - EXIT$' "$candidate")" = 1

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
