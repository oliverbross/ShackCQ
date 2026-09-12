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
python3 "$repo/scripts/audit_macos_nexus_desktop.py" --manifest "$manifest" "$app"
codesign --force --deep --sign - "$app"
codesign --verify --deep --strict --verbose=2 "$app"

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
printf 'PACKAGED_NATIVE_INGRESS_ONLY_OK hardwareAutoconnect=false\n'
rm -rf "$acceptance_root"

stage=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-nexus-desktop.XXXXXX")
trap 'rm -rf "$stage"' EXIT HUP INT TERM
ditto "$app" "$stage/ShackCQ Desktop.app"
ln -s /Applications "$stage/Applications"
dmg="$output/ShackCQ-Desktop-macOS-arm64-0.2.0-UNSIGNED-UNNOTARIZED.dmg"
hdiutil create -quiet -volname "ShackCQ Desktop" -srcfolder "$stage" -ov -format UDZO "$dmg"
hdiutil verify "$dmg"
shasum -a 256 "$dmg" > "$output/SHA256SUMS.txt"
printf '%s\n' "$dmg"
