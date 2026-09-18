#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

artifact=${1:?usage: launch_isolated_macos_review.sh REVIEW_APP_OR_DMG DESKTOP_REVIEW_SERVE_PROFILE_JSON}
profile=${2:?usage: launch_isolated_macos_review.sh REVIEW_APP_OR_DMG DESKTOP_REVIEW_SERVE_PROFILE_JSON}

test -f "$profile" || { echo "Desktop review fixture profile is unavailable: $profile" >&2; exit 2; }
profile_origin=$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); print(value.get("origin", ""))' "$profile")
profile_certificate=$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); print(value.get("tlsCertificate", ""))' "$profile")
profile_pid=$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); print(value.get("launcherPid", ""))' "$profile")
profile_review=$(python3 -c 'import json,sys; value=json.load(open(sys.argv[1])); print("true" if value.get("desktopReview") is True else "false")' "$profile")

test "$profile_review" = true || { echo "Fixture profile is not a desktop-review-serve profile: $profile" >&2; exit 2; }
test "$profile_origin" = https://localhost:18443 || { echo "Fixture origin must be exactly https://localhost:18443; got: $profile_origin" >&2; exit 2; }
case "$profile_pid" in ''|*[!0-9]*) echo "Fixture profile has no valid launcher PID: $profile" >&2; exit 2;; esac
kill -0 "$profile_pid" 2>/dev/null || { echo "Desktop review fixture is not running (launcher PID $profile_pid). Restart the existing ShackCQ-Web desktop-review-serve fixture and use its new serve-ready.json." >&2; exit 3; }
test -f "$profile_certificate" && test -r "$profile_certificate" || { echo "Fixture public CA certificate is unavailable: $profile_certificate" >&2; exit 2; }
! grep -Eq 'BEGIN (EC |RSA )?PRIVATE KEY' "$profile_certificate" || { echo "Fixture TLS input contains a private key; refusing launch" >&2; exit 2; }
test "$(grep -c 'BEGIN CERTIFICATE' "$profile_certificate" || true)" = 1 || { echo "Fixture TLS input must contain exactly one public CA certificate" >&2; exit 2; }
openssl x509 -in "$profile_certificate" -noout -checkend 0 >/dev/null 2>&1 || { echo "Fixture TLS certificate is invalid or expired" >&2; exit 2; }
openssl x509 -in "$profile_certificate" -noout -text 2>/dev/null | grep -q 'CA:TRUE' || { echo "Fixture TLS certificate is not a CA certificate" >&2; exit 2; }
curl --fail --silent --show-error --cacert "$profile_certificate" "$profile_origin/api/v1/health" >/dev/null || {
  echo "Desktop review HTTPS fixture failed certificate-verified health check at $profile_origin" >&2
  exit 3
}

mount_point=
review_root=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-isolated-review-launch.XXXXXX")
cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ -n "$mount_point" ]; then hdiutil detach "$mount_point" >/dev/null 2>&1 || true; fi
  case "$review_root" in "${TMPDIR:-/tmp}"/shackcq-isolated-review-launch.*) rm -rf "$review_root" ;; esac
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

case "$artifact" in
  *.dmg)
    test -f "$artifact" || { echo "Review DMG is unavailable: $artifact" >&2; exit 2; }
    attach_plist="$review_root/attach.plist"
    hdiutil attach -readonly -nobrowse -plist "$artifact" >"$attach_plist"
    mount_point=$(python3 -c 'import plistlib,sys; p=plistlib.load(open(sys.argv[1],"rb")); print(next((e["mount-point"] for e in p.get("system-entities",[]) if "mount-point" in e),""))' "$attach_plist")
    test -n "$mount_point" || { echo "Review DMG did not expose a mount point" >&2; exit 2; }
    app=$(find "$mount_point" -maxdepth 1 -type d -name '*.app' -print -quit)
    ;;
  *.app) app=$artifact ;;
  *) echo "Review artifact must be a .app bundle or .dmg: $artifact" >&2; exit 2 ;;
esac
test -d "$app" || { echo "Review application bundle is unavailable: $app" >&2; exit 2; }
executable="$app/Contents/MacOS/shackcq-desktop"
test -x "$executable" || { echo "Review application executable is unavailable: $executable" >&2; exit 2; }

mkdir -p "$review_root/home" "$review_root/config" "$review_root/data" "$review_root/agent"
echo "Launching isolated review with verified fixture $profile_origin; close ShackCQ Desktop to finish."
SHACKCQ_ISOLATED_REVIEW_TLS_CERT="$profile_certificate" \
SHACKCQ_AGENT_EPHEMERAL_ROOT="$review_root/agent" \
SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
HOME="$review_root/home" \
XDG_CONFIG_HOME="$review_root/config" \
XDG_DATA_HOME="$review_root/data" \
  "$executable"
