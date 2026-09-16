#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:?usage: build_nexus_desktop_macos.sh OUTPUT_DIRECTORY}
tauri_cli_version=${TAURI_CLI_VERSION:-2.11.4}
sidecar_lock_tool="$repo/scripts/claim_package_sidecar_lock.py"
review_build=${SHACKCQ_ISOLATED_REVIEW_BUILD:-0}
package_only=${SHACKCQ_MACOS_PACKAGE_ONLY:-0}
assembly_only=${SHACKCQ_MACOS_ASSEMBLY_ONLY:-0}
signing_identity=${SHACKCQ_MACOS_SIGNING_IDENTITY:-}
notary_profile=${SHACKCQ_MACOS_NOTARY_PROFILE:-}
metadata_signing_state=AD_HOC_ONLY
metadata_notarization_state=NOT_PERFORMED
[ -z "$signing_identity" ] || metadata_signing_state=DEVELOPER_ID_VERIFIED
[ -z "$notary_profile" ] || metadata_notarization_state=ACCEPTED_STAPLED
app_name="ShackCQ Desktop"
package_state="UNSIGNED-UNNOTARIZED"
[ -z "$signing_identity" ] || package_state="SIGNED-NOT-NOTARIZED"
[ -z "$notary_profile" ] || package_state="RC1-NOTARIZED"
dmg_name="ShackCQ-Desktop-macOS-arm64-0.2.1-$package_state.dmg"
if [ "$review_build" = 1 ]; then
  app_name="ShackCQ Desktop Isolated Review"
  dmg_name="ShackCQ-Desktop-Isolated-Review-macOS-arm64-0.2.1-$package_state.dmg"
fi
compiled_app="$repo/desktop/shackcq-tauri/target/release/bundle/macos/$app_name.app"
app="$compiled_app"
packaging_revision=$(git -C "$repo" rev-parse HEAD)
compiled_source_revision=${SHACKCQ_COMPILED_SOURCE_REVISION:-$packaging_revision}
qt_prefix=${QT_PREFIX:?QT_PREFIX must name an official Qt 6.11.2 macOS installation}
qtwebengine_prefix=${QTWEBENGINE_PREFIX:-}
brotli_prefix=${BROTLI_PREFIX:-}
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
notary_root=
generated_sidecar_dir=
generated_sidecar_lock=
generated_nexus=
generated_stationd=
generated_hamlib_helper=
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
  [ -z "$notary_root" ] || rm -rf "$notary_root"
  generated_sidecars_absent=1
  for generated_sidecar in "$generated_nexus" "$generated_stationd" "$generated_hamlib_helper"; do
    if [ -n "$generated_sidecar" ]; then
      if ! rm -f -- "$generated_sidecar"; then
        echo "failed to remove owned generated sidecar: $generated_sidecar" >&2
        generated_sidecars_absent=0
      fi
      if [ -e "$generated_sidecar" ] || [ -L "$generated_sidecar" ]; then
        echo "owned generated sidecar remains: $generated_sidecar" >&2
        generated_sidecars_absent=0
      fi
    fi
  done
  if [ -n "$generated_sidecar_lock" ]; then
    if [ "$generated_sidecars_absent" != 1 ]; then
      echo "generated sidecar ownership lock retained: $generated_sidecar_lock" >&2
      echo "inspect the lock JSON and the three exact target paths; after proving the recorded PID inactive, remove only those owned paths and then this lock file" >&2
      cleanup_status=1
    else
      lock_remove_failed=0
      if [ "${SHACKCQ_TEST_LOCK_REMOVE_FAILURE:-}" = 1 ]; then
        lock_remove_failed=1
      elif ! rm -f -- "$generated_sidecar_lock"; then
        lock_remove_failed=1
      fi
      if [ "$lock_remove_failed" != 0 ] || \
        [ -e "$generated_sidecar_lock" ] || [ -L "$generated_sidecar_lock" ]; then
        echo "metadata-bearing generated sidecar ownership lock retained: $generated_sidecar_lock" >&2
        echo "inspect the lock JSON and exact target paths; after proving the recorded PID inactive, remove only the owned paths and then this lock file" >&2
        cleanup_status=1
      fi
    fi
  fi
  [ -z "$generated_sidecar_dir" ] || rmdir "$generated_sidecar_dir" 2>/dev/null || true
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

claim_generated_sidecars() {
  sidecar_dir="$repo/desktop/shackcq-tauri/binaries"
  mkdir -p "$sidecar_dir"
  sidecar_lock="$sidecar_dir/.shackcq-package-aarch64-apple-darwin.lock"
  owner_source=$(git -C "$repo" rev-parse HEAD)
  owner_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  trap '' HUP INT TERM
  set +e
  python3 "$sidecar_lock_tool" "$sidecar_lock" "$$" "${HOSTNAME:-unknown}" \
    "$owner_started" "$owner_source" "macos-arm64" "$output"
  claim_status=$?
  set -e
  if [ "$claim_status" = 0 ]; then
    generated_sidecar_dir=$sidecar_dir
    generated_sidecar_lock=$sidecar_lock
  fi
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if [ "$claim_status" != 0 ]; then
    if [ "$claim_status" = 17 ]; then
      echo "another package build owns target aarch64-apple-darwin: $sidecar_lock" >&2
      echo "inspect the JSON lock and all exact target paths; never auto-break it, and remove it manually only after proving the recorded PID inactive" >&2
    else
      echo "failed to atomically claim package target aarch64-apple-darwin (lock helper status $claim_status): $sidecar_lock" >&2
    fi
    return 1
  fi
  candidate_nexus="$sidecar_dir/shackcq-nexus-runtime-aarch64-apple-darwin"
  candidate_stationd="$sidecar_dir/shackcq-stationd-aarch64-apple-darwin"
  candidate_hamlib_helper="$sidecar_dir/shackcq-hamlib-helper-aarch64-apple-darwin"
  for candidate_sidecar in "$candidate_nexus" "$candidate_stationd" "$candidate_hamlib_helper"; do
    { test ! -e "$candidate_sidecar" && test ! -L "$candidate_sidecar"; } || {
      echo "refusing to overwrite pre-existing generated sidecar: $candidate_sidecar" >&2
      return 1
    }
  done
  generated_nexus=$candidate_nexus
  generated_stationd=$candidate_stationd
  generated_hamlib_helper=$candidate_hamlib_helper
}

test ! -e "$output/COMPONENT_MANIFEST.json"
test ! -e "$output/$dmg_name"

if [ -n "${SHACKCQ_TEST_MAC_SIDECAR_LOCK_READY:-}" ]; then
  claim_generated_sidecars
  if [ "${SHACKCQ_TEST_MAC_GENERATED_SIDECARS:-}" = 1 ]; then
    for generated_sidecar in "$generated_nexus" "$generated_stationd" "$generated_hamlib_helper"; do
      printf 'owned mac test sidecar\n' >"$generated_sidecar"
    done
  fi
  printf 'locked\n' >"$SHACKCQ_TEST_MAC_SIDECAR_LOCK_READY"
  lock_released=0
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 \
    21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do
    if [ -e "$SHACKCQ_TEST_MAC_SIDECAR_LOCK_READY.release" ]; then
      lock_released=1
      break
    fi
    sleep 0.05
  done
  test "$lock_released" = 1
  exit 0
fi

case "$package_only" in 0|1) ;; *) echo "SHACKCQ_MACOS_PACKAGE_ONLY must be 0 or 1" >&2; exit 2;; esac
case "$assembly_only" in 0|1) ;; *) echo "SHACKCQ_MACOS_ASSEMBLY_ONLY must be 0 or 1" >&2; exit 2;; esac
test "$assembly_only" = 0 || test "$package_only" = 1 || { echo "Assembly-only requires package-only mode" >&2; exit 2; }
test -z "$notary_profile" || test -n "$signing_identity" || { echo "Notarization requires a Developer ID signing identity" >&2; exit 2; }
test -d "$qt_prefix" || { echo "Requested Qt prefix is unavailable: $qt_prefix" >&2; exit 2; }
qt_prefix=$(CDPATH= cd -- "$qt_prefix" && pwd -P)
qtpaths="$qt_prefix/bin/qtpaths"
macdeployqt="$qt_prefix/bin/macdeployqt"
test -x "$qtpaths" || { echo "Selected Qt qtpaths is unavailable: $qtpaths" >&2; exit 2; }
test -x "$macdeployqt" || { echo "Selected Qt macdeployqt is unavailable: $macdeployqt" >&2; exit 2; }
reported_qt_prefix=$("$qtpaths" --query QT_INSTALL_PREFIX)
reported_qt_version=$("$qtpaths" --query QT_VERSION)
qt_plugin_dir=$("$qtpaths" --query QT_INSTALL_PLUGINS)
test "$reported_qt_prefix" = "$qt_prefix" || {
  echo "Qt prefix mismatch: requested=$qt_prefix imported=$reported_qt_prefix" >&2
  exit 2
}
test "$reported_qt_version" = 6.11.2 || {
  echo "Qt version mismatch: expected=6.11.2 selected=$reported_qt_version prefix=$qt_prefix" >&2
  exit 2
}
case "$qt_plugin_dir" in "$qt_prefix"/*) ;; *)
  echo "Qt plugin directory mismatch: prefix=$qt_prefix plugins=$qt_plugin_dir" >&2
  exit 2
esac
for required_plugin in \
  "$qt_plugin_dir/tls/libqcertonlybackend.dylib" \
  "$qt_plugin_dir/tls/libqsecuretransportbackend.dylib" \
  "$qt_plugin_dir/sqldrivers/libqsqlite.dylib"; do
  test -f "$required_plugin" || { echo "Required selected-Qt plugin unavailable: $required_plugin" >&2; exit 2; }
  file "$required_plugin" | grep 'arm64' >/dev/null || { echo "Required selected-Qt plugin lacks arm64: $required_plugin" >&2; exit 2; }
  if otool -L "$required_plugin" | grep -E '/opt/(homebrew|local)|/Users/.*/Qt/' | grep -v "$qt_prefix/" >/dev/null; then
    echo "Selected-Qt plugin links another developer runtime: $required_plugin" >&2
    exit 2
  fi
done
if [ -n "$qtwebengine_prefix" ]; then
  qtwebengine_prefix=$(CDPATH= cd -- "$qtwebengine_prefix" && pwd -P)
  case "$qtwebengine_prefix" in "$qt_prefix"|"$qt_prefix"/*) ;; *)
    echo "Qt WebEngine prefix would mix Qt installations: qt=$qt_prefix webengine=$qtwebengine_prefix" >&2
    exit 2
  esac
  test -d "$qtwebengine_prefix/lib"
fi
if [ -n "$brotli_prefix" ]; then
  brotli_prefix=$(CDPATH= cd -- "$brotli_prefix" && pwd -P)
  test -f "$brotli_prefix/lib/libbrotlidec.1.dylib"
  test -f "$brotli_prefix/lib/libbrotlicommon.1.dylib"
  file "$brotli_prefix/lib/libbrotlidec.1.dylib" | grep 'arm64' >/dev/null
  file "$brotli_prefix/lib/libbrotlicommon.1.dylib" | grep 'arm64' >/dev/null
fi

test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
python3 "$repo/scripts/check_nexus_native_desktop.py"
python3 "$repo/desktop/shackcq-tauri/scripts/verify-shared-ui.py"
cmake -S "$repo/desktop" -B "$build_dir" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$qt_prefix" \
  -DQt6_DIR="$qt_prefix/lib/cmake/Qt6" \
  -DQt6Core_DIR="$qt_prefix/lib/cmake/Qt6Core" \
  -DQt6Network_DIR="$qt_prefix/lib/cmake/Qt6Network" \
  -DQt6Sql_DIR="$qt_prefix/lib/cmake/Qt6Sql" \
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
for cache_entry in CMAKE_PREFIX_PATH Qt6_DIR Qt6Core_DIR Qt6Network_DIR Qt6Sql_DIR; do
  cache_value=$(sed -n "s/^$cache_entry:[^=]*=//p" "$build_dir/CMakeCache.txt")
  case "$cache_value" in "$qt_prefix"|"$qt_prefix"/*) ;; *)
    echo "CMake Qt cache mismatch: $cache_entry=$cache_value selected=$qt_prefix" >&2
    exit 2
  esac
done
printf 'QT_PREFLIGHT_OK prefix=%s version=%s deploy=%s plugins=%s cache=%s\n' \
  "$qt_prefix" "$reported_qt_version" "$macdeployqt" "$qt_plugin_dir" "$build_dir/CMakeCache.txt"

if [ "$package_only" = 1 ]; then
  test -d "$compiled_app" || { echo "Retained compiled app is unavailable: $compiled_app" >&2; exit 2; }
  git -C "$repo" cat-file -e "$compiled_source_revision^{commit}"
  if [ "$review_build" != 1 ]; then
    git -C "$repo" merge-base --is-ancestor "$compiled_source_revision" "$packaging_revision" || {
      echo "Normal package-only recovery requires an ancestor compiled source" >&2
      exit 2
    }
    git -C "$repo" diff --quiet "$compiled_source_revision..$packaging_revision" -- \
      desktop/shackcq-tauri desktop/src desktop/include || {
      echo "Normal package-only recovery refuses changed compiled inputs" >&2
      exit 2
    }
    test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$compiled_app/Contents/Info.plist")" = "online.shackcq.desktop" || {
      echo "Normal package-only recovery requires the normal Desktop bundle" >&2
      exit 2
    }
  fi
  assembly_root="$output/assembly"
  test ! -e "$assembly_root" || { echo "Assembly destination already exists: $assembly_root" >&2; exit 2; }
  mkdir -p "$assembly_root"
  ditto "$compiled_app" "$assembly_root/$app_name.app"
  app="$assembly_root/$app_name.app"
  rm -rf -- "$app/Contents/Frameworks" "$app/Contents/PlugIns" "$app/Contents/lib" "$app/Contents/_CodeSignature"
  cp "$repo/desktop/shackcq-tauri/target/release/shackcq-desktop" "$app/Contents/MacOS/shackcq-desktop"
  cp "$repo/desktop/shackcq-tauri/target/release/shackcq-stationd" "$app/Contents/MacOS/shackcq-stationd"
  cp "$repo/desktop/shackcq-tauri/target/release/shackcq-hamlib-helper" "$app/Contents/MacOS/shackcq-hamlib-helper"
  cp "$repo/desktop/shackcq-tauri/target/release/shackcq-nexus-runtime" "$app/Contents/MacOS/shackcq-nexus-runtime"
  chmod 755 "$app/Contents/MacOS/shackcq-desktop" "$app/Contents/MacOS/shackcq-stationd" \
    "$app/Contents/MacOS/shackcq-hamlib-helper" "$app/Contents/MacOS/shackcq-nexus-runtime"
else
  claim_generated_sidecars
  "$repo/scripts/build_nexus_native_sidecar.sh"
  sh "$repo/scripts/build_hamlib_posix.sh" "$hamlib_root" \
    "$repo/core/third_party/hamlib" "$repo/build/desktop/nexus-hamlib-build-macos-13"
  sh "$repo/scripts/build_openssl_macos.sh" "$openssl_root" \
    "$repo/build/desktop/nexus-openssl-build-macos-13"
  "$repo/desktop/shackcq-tauri/scripts/build-stationd-sidecar.sh"
  (
    cd "$repo/desktop/shackcq-tauri"
    if [ "$review_build" = 1 ]; then
      npx --yes "@tauri-apps/cli@$tauri_cli_version" build --ci --no-sign \
        --bundles app --features isolated-review --config tauri.review.conf.json -- --locked
    else
      npx --yes "@tauri-apps/cli@$tauri_cli_version" build --ci --no-sign --bundles app -- --locked
    fi
  )
fi

stationd="$app/Contents/MacOS/shackcq-stationd"
nexus="$app/Contents/MacOS/shackcq-nexus-runtime"
hamlib_helper="$app/Contents/MacOS/shackcq-hamlib-helper"
test -x "$stationd"
test -x "$nexus"
test -x "$hamlib_helper"
mkdir -p "$app/Contents/PlugIns/tls"
cp "$qt_plugin_dir/tls/libqcertonlybackend.dylib" \
  "$qt_plugin_dir/tls/libqsecuretransportbackend.dylib" \
  "$app/Contents/PlugIns/tls/"
mkdir -p "$app/Contents/PlugIns/sqldrivers"
cp "$qt_plugin_dir/sqldrivers/libqsqlite.dylib" \
  "$app/Contents/PlugIns/sqldrivers/"
set -- "$app" -no-strip -no-plugins \
  -executable="$stationd" -executable="$nexus" -executable="$hamlib_helper" \
  -executable="$app/Contents/PlugIns/tls/libqcertonlybackend.dylib" \
  -executable="$app/Contents/PlugIns/tls/libqsecuretransportbackend.dylib" \
  -executable="$app/Contents/PlugIns/sqldrivers/libqsqlite.dylib" \
  -libpath="$qt_prefix/lib"
[ -z "$qtwebengine_prefix" ] || set -- "$@" -libpath="$qtwebengine_prefix/lib"
[ -z "$brotli_prefix" ] || set -- "$@" -libpath="$brotli_prefix/lib"
"$macdeployqt" "$@"
mkdir -p "$app/Contents/Resources"
sh "$repo/scripts/stage_nexus_package_legal.sh" "$app/Contents/Resources" "$repo" 6.11.2 macos-arm64
test -s "$build_dir/_deps/opus-src/COPYING"
cp "$build_dir/_deps/opus-src/COPYING" "$app/Contents/Resources/OPUS-COPYING"
python3 "$repo/scripts/write_nexus_package_metadata.py" \
  --output "$app/Contents/Resources/PACKAGE_MANIFEST.json" \
  --source "$compiled_source_revision" \
  --web "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "$repo/desktop/shared-digi-ui-snapshot/frontend-manifest.json")" \
  --frontend-content-sha "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["contentSha256"])' "$repo/desktop/shared-digi-ui-manifest.json")" \
  --nexus 7618390658f8f92431dec0ac65979b84f2c0fb76 \
  --platform macos-arm64 --qt 6.11.2 \
  --rust "$(rustc --version | awk '{print $2}')" \
  --tauri-cli "$tauri_cli_version" \
  --signing-state "$metadata_signing_state" \
  --notarization-state "$metadata_notarization_state"
printf '%s\n' "$compiled_source_revision" > "$app/Contents/Resources/COMPILED_SOURCE_REVISION.txt"
printf '%s\n' "$packaging_revision" > "$app/Contents/Resources/PACKAGING_REVISION.txt"
python3 "$repo/scripts/audit_macos_nexus_desktop.py" --repair-install-ids "$app"
manifest="$output/COMPONENT_MANIFEST.json"
mkdir -p "$output"
if [ -n "$signing_identity" ]; then
  for code_root in "$app/Contents/Frameworks" "$app/Contents/PlugIns"; do
    if [ -d "$code_root" ]; then
      find "$code_root" -type f -print | while IFS= read -r candidate; do
        if file "$candidate" | grep -q 'Mach-O'; then
          codesign --force --options runtime --timestamp --sign "$signing_identity" "$candidate"
        fi
      done
    fi
  done
  if [ -d "$app/Contents/Frameworks" ]; then
    find "$app/Contents/Frameworks" -type d -name '*.framework' -print | while IFS= read -r framework; do
      codesign --force --options runtime --timestamp --sign "$signing_identity" "$framework"
    done
  fi
  for executable in "$hamlib_helper" "$stationd" "$nexus"; do
    codesign --force --options runtime --timestamp --sign "$signing_identity" "$executable"
  done
  codesign --force --options runtime --timestamp --entitlements \
    "$repo/desktop/shackcq-tauri/Entitlements.plist" --sign "$signing_identity" \
    "$app/Contents/MacOS/shackcq-desktop"
  codesign --force --options runtime --timestamp --entitlements \
    "$repo/desktop/shackcq-tauri/Entitlements.plist" --sign "$signing_identity" "$app"
else
  codesign --force --deep --sign - "$app"
fi
codesign --verify --deep --strict --verbose=2 "$app"
if [ -n "$notary_profile" ]; then
  notary_root=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-desktop-notary.XXXXXX")
  ditto -c -k --sequesterRsrc --keepParent "$app" "$notary_root/ShackCQ-Desktop.zip"
  xcrun notarytool submit "$notary_root/ShackCQ-Desktop.zip" \
    --keychain-profile "$notary_profile" --wait --output-format json \
    >"$output/NOTARIZATION.json"
  jq -e '.status == "Accepted"' "$output/NOTARIZATION.json" >/dev/null
  xcrun stapler staple "$app"
  xcrun stapler validate "$app"
fi
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

if [ "$assembly_only" = 1 ]; then
  printf 'PACKAGE_ASSEMBLY_ONLY_OK app=%s\n' "$app"
  exit 0
fi

stage=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-nexus-desktop.XXXXXX")
ditto "$app" "$stage/$app_name.app"
ln -s /Applications "$stage/Applications"
dmg="$output/$dmg_name"
hdiutil create -quiet -volname "$app_name" -srcfolder "$stage" -ov -format UDZO "$dmg"
if [ -n "$signing_identity" ]; then
  codesign --force --timestamp --sign "$signing_identity" "$dmg"
  codesign --verify --verbose=2 "$dmg"
fi
hdiutil verify "$dmg"

attach_output=$(hdiutil attach -readonly -nobrowse "$dmg")
mount_point=$(printf '%s\n' "$attach_output" | awk -F '\t' '$3 ~ /^\/Volumes\// {print $3; exit}')
test -n "$mount_point"
mounted_app="$mount_point/$app_name.app"
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

for launch in 1; do
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
printf 'MOUNTED_DMG_ACCEPTANCE_OK stationd=isolated nexus=reference-recording gui=cold-safe-exit-1-of-1 stranded=none\n'

shasum -a 256 "$dmg" > "$output/SHA256SUMS.txt"
printf '%s\n' "$dmg"
