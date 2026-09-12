#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
macos="$repo/scripts/build_nexus_desktop_macos.sh"
legal="$repo/scripts/stage_nexus_package_legal.sh"
cross_workflow="$repo/.github/workflows/nexus-desktop-cross-platform-candidate.yml"
stationd_sidecar="$repo/desktop/shackcq-tauri/scripts/build-stationd-sidecar.sh"
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
sh -n "$legal"
sh -n "$stationd_sidecar"
python3 -m py_compile "$repo/scripts/claim_package_sidecar_lock.py"
python3 -m py_compile "$repo/scripts/write_nexus_package_metadata.py"
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
linux_acceptance=$(sed -n '/^accept_linux_payload()/,/^}/p' "$candidate")
printf '%s\n' "$linux_acceptance" | grep -F 'if [ "$label" = APPIMAGE ]; then' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'launch_path="$payload_root/AppRun"' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'launch_path=$main_path' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'launch_cwd="$payload_tmp/home"' >/dev/null
test "$(printf '%s\n' "$linux_acceptance" | grep -Fc 'launch_cwd=$payload_root')" = 1
printf '%s\n' "$linux_acceptance" | grep -F 'cd "$launch_cwd"' >/dev/null
test "$(printf '%s\n' "$linux_acceptance" | grep -Fc 'launch_env+=("APPDIR=$payload_root")')" = 1
printf '%s\n' "$linux_acceptance" | grep -F '"${launch_env[@]}"' >/dev/null
! printf '%s\n' "$linux_acceptance" | grep -F '${LD_LIBRARY_PATH:+' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'SHACKCQ_PACKAGE_RUNTIME_HERMETIC=1 "$agent_path" --package-runtime-probe' >/dev/null
grep -F 'windeployqt.exe" --release --no-translations' "$candidate" >/dev/null
! grep -F 'export PATH="$qt_runtime_bin:$PATH"' "$candidate" >/dev/null
grep -F 'stationd_dependencies=$(PATH="$qt_runtime_bin:$PATH" ldd "$stationd_executable" 2>&1)' "$candidate" >/dev/null
grep -F 'PATH="$qt_runtime_bin:$PATH" "$stationd_executable" --version' "$candidate" >/dev/null
grep -F '.package-windows-runtime-$target' "$candidate" >/dev/null
grep -F 'audit_windows_payload "$nsis_extract" "$windows_app_root"' "$candidate" >/dev/null
windows_acceptance=$(sed -n '/^accept_windows_payload()/,/^}/p' "$candidate")
printf '%s\n' "$windows_acceptance" | grep -F 'app_root=$(dirname "$agent_path")' >/dev/null
printf '%s\n' "$windows_acceptance" | grep -F 'packaged_windows_path="$app_root:$windows_system32:$windows_root"' >/dev/null
printf '%s\n' "$windows_acceptance" | grep -F 'runtime_probe=$(run_packaged_windows_binary "$agent_path" --package-runtime-probe)' >/dev/null
printf '%s\n' "$windows_acceptance" | grep -F 'run_packaged_windows_binary "$helper_path"' >/dev/null
printf '%s\n' "$windows_acceptance" | grep -F 'PATH="$packaged_windows_path"' >/dev/null
! printf '%s\n' "$windows_acceptance" | grep -F 'PATH="$qt_runtime_bin:$PATH"' >/dev/null
windows_audit=$(sed -n '/^audit_windows_payload()/,/^}/p' "$candidate")
printf '%s\n' "$windows_audit" | grep -F 'find "$object_dir" -maxdepth 1' >/dev/null
printf '%s\n' "$windows_audit" | grep -F 'find "$app_root" -maxdepth 1' >/dev/null
! printf '%s\n' "$windows_audit" | grep -F 'find "$payload_root" -type f -iname "$dependency"' >/dev/null
test "$(grep -Fc -- '--package-runtime-probe' "$candidate")" -ge 2
grep -F 'stage_linux_qt_runtime "$appimage_extract/squashfs-root" APPIMAGE' "$candidate" >/dev/null
grep -F 'stage_linux_qt_runtime "$deb_extract" DEB' "$candidate" >/dev/null
grep -F "rpath='\$ORIGIN/../lib/shackcq'" "$candidate" >/dev/null
grep -F "rpath='\$ORIGIN/../lib'" "$candidate" >/dev/null
grep -F "patchelf --set-rpath '\$ORIGIN'" "$candidate" >/dev/null
grep -F "plugin_rpath='\$ORIGIN/../../lib'" "$candidate" >/dev/null
grep -F "plugin_rpath='\$ORIGIN/../..'" "$candidate" >/dev/null
grep -F 'final_appimage_extract' "$candidate" >/dev/null
grep -F 'final_deb_extract' "$candidate" >/dev/null
grep -F -- '--package-runtime-probe' "$candidate" >/dev/null
grep -F 'libqsqlite.so' "$candidate" >/dev/null
grep -F "grep -Eq '/(home/runner|Users|opt/hostedtoolcache|__w)/|[A-Za-z]:" "$candidate" >/dev/null
grep -F 'mksquashfs "$appimage_extract/squashfs-root"' "$candidate" >/dev/null
grep -F 'QCoreApplication::setLibraryPaths(packagedPluginPaths)' "$repo/desktop/src/app/stationd_main.cpp" >/dev/null
grep -F 'qEnvironmentVariableIntValue("SHACKCQ_PACKAGE_RUNTIME_HERMETIC") == 1' "$repo/desktop/src/app/stationd_main.cpp" >/dev/null
! grep -F 'QCoreApplication::addLibraryPath(path)' "$repo/desktop/src/app/stationd_main.cpp" >/dev/null
grep -F 'refresh_debian_metadata "$deb_extract"' "$candidate" >/dev/null
grep -F 'DEBIAN/md5sums' "$candidate" >/dev/null
grep -F 'Installed-Size:' "$candidate" >/dev/null
grep -F 'legal/PACKAGE_MANIFEST.json' "$candidate" >/dev/null
for packaged_legal in NEXUS-COPYING NEXUS-NOTICE HAMLIB-COPYING \
    HAMLIB-COPYING.LIB HAMLIB-LICENSE OPUS-COPYING \
    Qt-LGPL-3.0-only.txt Qt-GPL-3.0-only.txt ICU-73-LICENSE.txt ICU-74-LICENSE.txt \
    OPENSSL-LICENSE.txt FFTW-COPYING; do
  grep -F "$packaged_legal" "$candidate" >/dev/null
done
grep -F 'Contents/Resources/PACKAGE_MANIFEST.json' "$macos" >/dev/null
grep -F 'Contents/Resources/OPUS-COPYING' "$macos" >/dev/null
metadata_probe="$contract_scratch/PACKAGE_MANIFEST.json"
python3 "$repo/scripts/write_nexus_package_metadata.py" --output "$metadata_probe" \
  --source "$(git -C "$repo" rev-parse HEAD)" --web test-web --nexus test-nexus \
  --frontend-content-sha test-content --platform test-platform --qt test-qt \
  --rust test-rust --tauri-cli test-tauri
python3 - "$metadata_probe" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1]))
assert data["sharedDigiFrontendContentSha256"] == "test-content"
assert data["rustToolchain"] == "test-rust" and data["tauriCliVersion"] == "test-tauri"
assert data["transmit"] == {
    "encoderCallableWithoutPhysicalOutput": False,
    "automaticSequenceImplemented": False,
    "physicalAudioOutputAvailable": False,
    "productionEnabled": False,
}
assert data["signing"] == "UNSIGNED"
assert data["notarization"] == "NOT_APPLICABLE"
assert data["browserLocalBridge"] == "DISABLED_UNTIL_EXPLICIT_PAIRING"
assert data["canonicalLogbook"] == "BUNDLED_AGENT_NATIVE_INGRESS"
assert "FST4W" in data["rxModes"] and "WSPR" in data["rxModes"]
PY
mac_metadata_probe="$contract_scratch/MAC_PACKAGE_MANIFEST.json"
python3 "$repo/scripts/write_nexus_package_metadata.py" --output "$mac_metadata_probe" \
  --source test-source --web test-web --nexus test-nexus \
  --frontend-content-sha test-content --platform macos-arm64 --qt 6.11.2 \
  --rust test-rust --tauri-cli test-tauri
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["signing"] == "AD_HOC_ONLY" and d["notarization"] == "NOT_PERFORMED" and d["platformBaseline"] == "macOS 13 arm64"' \
  "$mac_metadata_probe"
legal_probe="$contract_scratch/legal"
sh "$legal" "$legal_probe" "$repo" 6.10.2 windows-x64
for legal_name in COPYING NOTICE THIRD_PARTY_NOTICES.txt Qt-LGPL-3.0-only.txt Qt-GPL-3.0-only.txt \
    ICU-73-LICENSE.txt ICU-74-LICENSE.txt OPENSSL-LICENSE.txt FFTW-COPYING LEGAL_PROVENANCE.txt NEXUS-COPYING NEXUS-NOTICE HAMLIB-COPYING \
    HAMLIB-COPYING.LIB HAMLIB-LICENSE; do
  test -s "$legal_probe/$legal_name"
done
grep -F 'QT_RUNTIME_VERSION=6.10.2' "$legal_probe/LEGAL_PROVENANCE.txt" >/dev/null
grep -F 'FFTW_STATIC_WINDOWS_VERSION=3.3.10' "$legal_probe/LEGAL_PROVENANCE.txt" >/dev/null
grep -F 'OPENSSL_WINDOWS_RUNTIME_EXPECTED=3.6.4' "$legal_probe/LEGAL_PROVENANCE.txt" >/dev/null
grep -F 'exact Qt runtime' "$repo/desktop/resources/THIRD_PARTY_NOTICES.txt" >/dev/null
grep -F 'WSPR receive and' "$repo/NOTICE" >/dev/null
! grep -F 'WSPR is withheld' "$repo/NOTICE" >/dev/null
socket_name=shackcq-package-32767-32767.sock
case "$socket_name" in
  [A-Za-z0-9]* ) ;;
  * ) exit 1 ;;
esac
case "$socket_name" in
  *[!A-Za-z0-9._-]* ) exit 1 ;;
esac
test "${#socket_name}" -le 96

! grep -F 'OPENSSL_ROOT_DIR: C:/msys64/mingw64' "$cross_workflow" >/dev/null
grep -F 'test -s /mingw64/include/openssl/ssl.h' "$cross_workflow" >/dev/null
grep -F 'test -s /mingw64/lib/libcrypto.dll.a' "$cross_workflow" >/dev/null
grep -F 'test -s /mingw64/lib/libssl.dll.a' "$cross_workflow" >/dev/null
grep -F 'export OPENSSL_ROOT_DIR="$(cygpath -m /mingw64)"' "$cross_workflow" >/dev/null
grep -F "SHACKCQ_VERBOSE_STATIOND_BUILD: '1'" "$cross_workflow" >/dev/null
grep -F 'cmake --build "$build_dir" --target shackcq-stationd shackcq-hamlib-helper -j4 --verbose' "$stationd_sidecar" >/dev/null

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
