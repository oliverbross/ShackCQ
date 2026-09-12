#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:?usage: build_nexus_desktop_macos.sh OUTPUT_DIRECTORY}
tauri_cli_version=${TAURI_CLI_VERSION:-2.11.4}
app="$repo/desktop/shackcq-tauri/target/release/bundle/macos/ShackCQ Desktop.app"
qt_prefix=${QT_PREFIX:?QT_PREFIX must name an official Qt 6.11.2 macOS installation}
macdeployqt="$qt_prefix/bin/macdeployqt"
qtwebengine_prefix=${QTWEBENGINE_PREFIX:-$(brew --prefix qtwebengine)}
brotli_prefix=${BROTLI_PREFIX:-$(brew --prefix brotli)}
build_dir="$repo/build/desktop/nexus-macos-13-portable"
hamlib_root="$repo/build/desktop/nexus-hamlib-macos-13"
openssl_root="$repo/build/desktop/nexus-openssl-macos-13"
export SHACKCQ_DESKTOP_BUILD_DIR="$build_dir"
export MACOSX_DEPLOYMENT_TARGET=13.0

acceptance_root=
stage=
mount_point=
mounted_acceptance=
stationd_pid=
mounted_stationd_pid=
mounted_main_pid=
stationd=
mounted_stationd=
acceptance_socket=
mounted_socket=
owner_token=
mounted_owner=
cleanup() {
  cleanup_status=$?
  trap - EXIT HUP INT TERM
  set +e
  if [ -n "$mounted_main_pid" ]; then
    kill "$mounted_main_pid" 2>/dev/null || true
    wait "$mounted_main_pid" 2>/dev/null || true
  fi
  if [ -n "$mount_point" ]; then
    mounted_processes=$(pgrep -f "$mount_point/.*/shackcq-(desktop|stationd|nexus-runtime)" 2>/dev/null || true)
    if [ -n "$mounted_processes" ]; then
      kill $mounted_processes 2>/dev/null || true
      sleep 0.2
      remaining_processes=$(pgrep -f "$mount_point/.*/shackcq-(desktop|stationd|nexus-runtime)" 2>/dev/null || true)
      [ -z "$remaining_processes" ] || kill -9 $remaining_processes 2>/dev/null || true
      for owned_pid in $mounted_processes $remaining_processes; do
        wait "$owned_pid" 2>/dev/null || true
      done
    fi
  fi
  if [ -n "$mounted_stationd_pid" ]; then
    "$mounted_stationd" --admin-socket "$mounted_socket" --stop \
      --native-owner-token "$mounted_owner" >/dev/null 2>&1 || true
    kill "$mounted_stationd_pid" 2>/dev/null || true
    wait "$mounted_stationd_pid" 2>/dev/null || true
  fi
  if [ -n "$stationd_pid" ]; then
    "$stationd" --admin-socket "$acceptance_socket" --stop \
      --native-owner-token "$owner_token" >/dev/null 2>&1 || true
    kill "$stationd_pid" 2>/dev/null || true
    wait "$stationd_pid" 2>/dev/null || true
  fi
  if [ -n "$mount_point" ]; then
    hdiutil detach "$mount_point" >/dev/null 2>&1 || true
  fi
  [ -z "$acceptance_root" ] || rm -rf "$acceptance_root"
  [ -z "$stage" ] || rm -rf "$stage"
  [ -z "$mounted_acceptance" ] || rm -rf "$mounted_acceptance"
  exit "$cleanup_status"
}
trap cleanup EXIT HUP INT TERM

test ! -e "$output/COMPONENT_MANIFEST.json"
test ! -e "$output/ShackCQ-Desktop-macOS-arm64-0.2.0-UNSIGNED-UNNOTARIZED.dmg"

test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
test -x "$macdeployqt"
test -d "$qtwebengine_prefix/lib"
test -d "$brotli_prefix/lib"
python3 "$repo/scripts/check_nexus_native_desktop.py"
python3 "$repo/desktop/shackcq-tauri/scripts/verify-shared-ui.py"
"$repo/scripts/build_nexus_native_sidecar.sh"
sh "$repo/scripts/build_hamlib_posix.sh" "$hamlib_root" \
  "$repo/core/third_party/hamlib" "$repo/build/desktop/nexus-hamlib-build-macos-13"
sh "$repo/scripts/build_openssl_macos.sh" "$openssl_root" \
  "$repo/build/desktop/nexus-openssl-build-macos-13"
cmake -S "$repo/desktop" -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$qt_prefix" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 \
  -DSHACKCQ_BUILD_TESTS=OFF \
  -DSHACKCQ_BUILD_NATIVE_DIGI=OFF \
  -DSHACKCQ_REQUIRE_HAMLIB=ON \
  -DSHACKCQ_HAMLIB_ROOT="$hamlib_root" \
  -DOPENSSL_ROOT_DIR="$openssl_root" \
  -DOPENSSL_INCLUDE_DIR="$openssl_root/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$openssl_root/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$openssl_root/lib/libssl.a" \
  -DOPENSSL_USE_STATIC_LIBS=TRUE
"$repo/desktop/shackcq-tauri/scripts/build-stationd-sidecar.sh"
(
  cd "$repo/desktop/shackcq-tauri"
  npx --yes "@tauri-apps/cli@$tauri_cli_version" build --ci --no-sign --bundles app -- --locked
)

stationd="$app/Contents/MacOS/shackcq-stationd"
nexus="$app/Contents/MacOS/shackcq-nexus-runtime"
hamlib_helper="$app/Contents/MacOS/shackcq-hamlib-helper"
test -x "$stationd"
test -x "$nexus"
test -x "$hamlib_helper"
"$macdeployqt" "$app" -no-strip -no-plugins \
  -executable="$stationd" -executable="$nexus" -executable="$hamlib_helper" \
  -libpath="$qt_prefix/lib" -libpath="$qtwebengine_prefix/lib" \
  -libpath="$brotli_prefix/lib"
mkdir -p "$app/Contents/PlugIns/tls"
cp "$qt_prefix/plugins/tls/libqcertonlybackend.dylib" \
  "$qt_prefix/plugins/tls/libqsecuretransportbackend.dylib" \
  "$app/Contents/PlugIns/tls/"
mkdir -p "$app/Contents/PlugIns/sqldrivers"
cp "$qt_prefix/plugins/sqldrivers/libqsqlite.dylib" \
  "$app/Contents/PlugIns/sqldrivers/"
mkdir -p "$app/Contents/Resources"
cp "$repo/COPYING" "$repo/NOTICE" "$app/Contents/Resources/"
python3 "$repo/scripts/audit_macos_nexus_desktop.py" --repair-install-ids "$app"
manifest="$output/COMPONENT_MANIFEST.json"
mkdir -p "$output"
codesign --force --deep --sign - "$app"
codesign --verify --deep --strict --verbose=2 "$app"
python3 "$repo/scripts/audit_macos_nexus_desktop.py" --manifest "$manifest" "$app"

# Launch only the newly assembled headless sidecar, with a unique socket,
# disposable paths, an in-memory credential vault, and hardware autoconnect off.
acceptance_root=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-packaged-acceptance.XXXXXX")
acceptance_socket="shackcq-package-$$"
owner_token=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
status_file="$acceptance_root/status.json"
log_file="$acceptance_root/stationd.log"
"$stationd" --foreground --native-ingress-only \
  --native-owner-token "$owner_token" --admin-socket "$acceptance_socket" \
  --ephemeral-root "$acceptance_root/state" --ephemeral-credentials \
  >"$log_file" 2>&1 &
stationd_pid=$!
ready=0
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if "$stationd" --admin-socket "$acceptance_socket" --status >"$status_file" 2>/dev/null; then
    ready=1
    break
  fi
  sleep 0.1
done
if [ "$ready" != 1 ]; then
  kill "$stationd_pid" 2>/dev/null || true
  wait "$stationd_pid" 2>/dev/null || true
  cat "$log_file" >&2
  exit 1
fi
jq -e '.ok == true and .result.hardwareAutoconnect == false and .result.nativeIngress.available == true' \
  "$status_file" >/dev/null
"$stationd" --admin-socket "$acceptance_socket" \
  --native-owner-token "$owner_token" --stop >/dev/null
wait "$stationd_pid"
stationd_pid=
printf 'PACKAGED_NATIVE_INGRESS_ONLY_OK hardwareAutoconnect=false\n'

fixture="$repo/third_party/nexus/crates/ft8/tests/fixtures/ft8_sample.wav"
fixture_sha=$(shasum -a 256 "$fixture" | awk '{print $1}')
test "$fixture_sha" = 9feb99c275770a6618538026da7decc6b09eb6cf63121e5168fa86dcdf00c2f5
python3 - "$fixture" "$fixture_sha" <<'PY' >"$acceptance_root/reference-request.json"
import json
import sys
print(json.dumps({"version":1,"commandId":"package-reference-decode","generation":1,
  "launchNonce":"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn",
  "command":{"type":"DECODE_RECORDING_FILE","parameters":{"path":sys.argv[1],
  "sha256":sys.argv[2],"mode":"FT8"}}}, separators=(",", ":")))
PY
SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
SHACKCQ_QUEUE_PATH="$acceptance_root/reference-queue.json" \
  "$nexus" <"$acceptance_root/reference-request.json" \
  >"$acceptance_root/reference-result.json"
jq -e '.ok == true and .code == "REFERENCE_RECORDING_DECODED" and
  .payload.decodeCount >= 1 and (.payload.messages | index("CQ F5RXL IN94") != null)' \
  "$acceptance_root/reference-result.json" >/dev/null
printf 'PACKAGED_REFERENCE_RECORDING_OK source=REFERENCE_RECORDING expected="CQ F5RXL IN94"\n'
rm -rf "$acceptance_root"
acceptance_root=

stage=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-nexus-desktop.XXXXXX")
ditto "$app" "$stage/ShackCQ Desktop.app"
ln -s /Applications "$stage/Applications"
dmg="$output/ShackCQ-Desktop-macOS-arm64-0.2.0-UNSIGNED-UNNOTARIZED.dmg"
hdiutil create -quiet -volname "ShackCQ Desktop" -srcfolder "$stage" -ov -format UDZO "$dmg"
hdiutil verify "$dmg"

attach_output=$(hdiutil attach -readonly -nobrowse "$dmg")
mount_point=$(printf '%s\n' "$attach_output" | awk -F '\t' '$3 ~ /^\/Volumes\// {print $3; exit}')
test -n "$mount_point"
mounted_app="$mount_point/ShackCQ Desktop.app"
mounted_stationd="$mounted_app/Contents/MacOS/shackcq-stationd"
mounted_nexus="$mounted_app/Contents/MacOS/shackcq-nexus-runtime"
mounted_main="$mounted_app/Contents/MacOS/shackcq-desktop"
test -x "$mounted_stationd" && test -x "$mounted_nexus" && test -x "$mounted_main"
mounted_acceptance=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-mounted-acceptance.XXXXXX")

mounted_socket="shackcq-mounted-$$"
mounted_owner=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
"$mounted_stationd" --foreground --native-ingress-only \
  --native-owner-token "$mounted_owner" --admin-socket "$mounted_socket" \
  --ephemeral-root "$mounted_acceptance/agent" --ephemeral-credentials \
  >"$mounted_acceptance/stationd.log" 2>&1 &
mounted_stationd_pid=$!
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  "$mounted_stationd" --admin-socket "$mounted_socket" --status \
    >"$mounted_acceptance/status.json" 2>/dev/null && break
  sleep 0.1
done
jq -e '.ok == true and .result.hardwareAutoconnect == false and .result.nativeIngress.available == true' \
  "$mounted_acceptance/status.json" >/dev/null
"$mounted_stationd" --admin-socket "$mounted_socket" \
  --native-owner-token "$mounted_owner" --stop >/dev/null
wait "$mounted_stationd_pid"
mounted_stationd_pid=

python3 - "$fixture" "$fixture_sha" <<'PY' >"$mounted_acceptance/reference-request.json"
import json
import sys
print(json.dumps({"version":1,"commandId":"mounted-reference-decode","generation":1,
  "launchNonce":"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn",
  "command":{"type":"DECODE_RECORDING_FILE","parameters":{"path":sys.argv[1],
  "sha256":sys.argv[2],"mode":"FT8"}}}, separators=(",", ":")))
PY
SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
SHACKCQ_QUEUE_PATH="$mounted_acceptance/reference-queue.json" \
  "$mounted_nexus" <"$mounted_acceptance/reference-request.json" \
  >"$mounted_acceptance/reference-result.json"
jq -e '.ok == true and .code == "REFERENCE_RECORDING_DECODED" and
  (.payload.messages | index("CQ F5RXL IN94") != null)' \
  "$mounted_acceptance/reference-result.json" >/dev/null

for launch in 1 2 3; do
  mkdir -p "$mounted_acceptance/home-$launch" \
    "$mounted_acceptance/config-$launch" "$mounted_acceptance/data-$launch"
  SHACKCQ_AGENT_ADMIN_SOCKET="shackcq-mounted-main-$$-$launch" \
  SHACKCQ_AGENT_EPHEMERAL_ROOT="$mounted_acceptance/main-agent-$launch" \
  SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
  SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS=1500 \
  HOME="$mounted_acceptance/home-$launch" \
  XDG_CONFIG_HOME="$mounted_acceptance/config-$launch" \
  XDG_DATA_HOME="$mounted_acceptance/data-$launch" \
    "$mounted_main" >"$mounted_acceptance/main-$launch.log" 2>&1 &
  mounted_main_pid=$!
  main_exited=0
  main_rc=125
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60; do
    if ! kill -0 "$mounted_main_pid" 2>/dev/null; then
      set +e
      wait "$mounted_main_pid"
      main_rc=$?
      set -e
      mounted_main_pid=
      main_exited=1
      break
    fi
    sleep 0.1
  done
  if [ "$main_exited" != 1 ] || [ "$main_rc" != 0 ]; then
    cat "$mounted_acceptance/main-$launch.log" >&2
    exit 1
  fi
done
sleep 0.5
! pgrep -f "$mount_point/.*/shackcq-(desktop|stationd|nexus-runtime)" >/dev/null
printf 'MOUNTED_DMG_ACCEPTANCE_OK stationd=isolated nexus=reference-recording gui=cold-safe-exit-3-of-3 stranded=none\n'

shasum -a 256 "$dmg" > "$output/SHA256SUMS.txt"
printf '%s\n' "$dmg"
