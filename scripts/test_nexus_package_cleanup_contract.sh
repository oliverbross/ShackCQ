#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
candidate="$repo/scripts/build_nexus_desktop_candidate.sh"
macos="$repo/scripts/build_nexus_desktop_macos.sh"
legal="$repo/scripts/stage_nexus_package_legal.sh"
cross_workflow="$repo/.github/workflows/nexus-desktop-cross-platform-candidate.yml"
stationd_sidecar="$repo/desktop/shackcq-tauri/scripts/build-stationd-sidecar.sh"
xvfb_supervisor="$repo/scripts/supervise_xvfb.py"
xvfb_supervisor_test="$repo/scripts/test_xvfb_supervisor.py"
pidfd_guard="$repo/scripts/pidfd_process_guard.py"
pidfd_guard_test="$repo/scripts/test_pidfd_process_guard.py"
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
python3 -m py_compile "$xvfb_supervisor" "$xvfb_supervisor_test" "$pidfd_guard" "$pidfd_guard_test"
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
printf '%s\n' "$linux_acceptance" | grep -F 'python3 "$repo/scripts/supervise_xvfb.py"' >/dev/null
! printf '%s\n' "$linux_acceptance" | grep -F 'xvfb-run' >/dev/null
! printf '%s\n' "$linux_acceptance" | grep -F 'pgrep' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'SHACKCQ_AGENT_ADMIN_SOCKET="$main_socket"' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F '["result"]["processId"]' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F '! linux_owned_agent_has_exited' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'linux_owned_agent_has_exited' >/dev/null
printf '%s\n' "$linux_acceptance" | grep -F 'stop_owned_xvfb' >/dev/null
grep -F 'owned Xvfb supervisor did not publish bounded completion' "$candidate" >/dev/null
grep -F 'owned Xvfb supervisor completion is invalid' "$candidate" >/dev/null
grep -F 'signal.pidfd_send_signal' "$xvfb_supervisor" >/dev/null
! grep -F 'kill "$xvfb' "$candidate" >/dev/null
grep -F 'pidfd_process_guard" terminate' "$candidate" >/dev/null
grep -F 'pidfd_process_guard" check-exited' "$candidate" >/dev/null
! grep -F 'kill -TERM "$linux_owned_agent_pid"' "$candidate" >/dev/null
grep -F 'signal.pidfd_send_signal' "$pidfd_guard" >/dev/null
grep -F 'fn shutdown(&mut self)' "$repo/desktop/shackcq-tauri/src/main.rs" >/dev/null
grep -F 'matches!(event, tauri::RunEvent::Exit)' "$repo/desktop/shackcq-tauri/src/main.rs" >/dev/null
grep -F 'agent.shutdown();' "$repo/desktop/shackcq-tauri/src/main.rs" >/dev/null
grep -F '"processId", QCoreApplication::applicationPid()' "$repo/desktop/src/app/stationd_main.cpp" >/dev/null
grep -F 'windeployqt.exe" --release --no-translations' "$candidate" >/dev/null
! grep -F 'export PATH="$qt_runtime_bin:$PATH"' "$candidate" >/dev/null
! grep -F 'stationd_dependencies=' "$candidate" >/dev/null
! grep -F 'SHACKCQ_TEST_WINDOWS_AGENT_PIPE' "$candidate" >/dev/null
! grep -F 'shackcq-windows-agent-proof' "$candidate" >/dev/null
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
python3 - "$cross_workflow" <<'PY'
from pathlib import Path
import re
import sys

lines = Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()


def unique_line(value: str) -> int:
    matches = [index for index, line in enumerate(lines) if line == value]
    assert len(matches) == 1, (value, matches)
    return matches[0]


def step_block(start: int) -> list[str]:
    end = start + 1
    while end < len(lines) and not lines[end].startswith("      - "):
        end += 1
    return lines[start:end]


def job_end(start: int) -> int:
    for index in range(start + 1, len(lines)):
        if re.fullmatch(r"  [A-Za-z0-9_-]+:", lines[index]):
            return index
    return len(lines)


windows_job = unique_line("  windows-x64-gnu-cross:")
windows_gate = unique_line("      - name: Verify Windows-safe package cleanup contract")
windows_build = unique_line("      - name: Build unsigned Windows x64 candidate")
linux_job = unique_line("  linux-x86-64:")
linux_gate = unique_line("      - name: Verify Linux package lifecycle and cleanup contract")
linux_build = unique_line("      - name: Build unsigned Linux x86_64 candidates")
static_env = unique_line("          SHACKCQ_PACKAGE_CONTRACT_STATIC_ONLY: '1'")
windows_job_end = job_end(windows_job)
linux_job_end = job_end(linux_job)

assert windows_job < windows_gate < windows_build < windows_job_end
assert linux_job < linux_gate < linux_build < linux_job_end
assert static_env in range(windows_gate, windows_build)
assert step_block(windows_gate) == [
    "      - name: Verify Windows-safe package cleanup contract",
    "        shell: msys2 {0}",
    "        env:",
    "          SHACKCQ_PACKAGE_CONTRACT_STATIC_ONLY: '1'",
    "        run: scripts/test_nexus_package_cleanup_contract.sh",
]
assert step_block(linux_gate) == [
    "      - name: Verify Linux package lifecycle and cleanup contract",
    "        run: scripts/test_nexus_package_cleanup_contract.sh",
]
assert step_block(windows_build) == [
    "      - name: Build unsigned Windows x64 candidate",
    "        env:",
    "          RUSTUP_TOOLCHAIN: ${{ env.RUST_TOOLCHAIN }}",
    "          SHACKCQ_QT_PREFIX: ${{ env.QT_ROOT_DIR }}",
    "          SHACKCQ_VERBOSE_STATIOND_BUILD: '1'",
    "        shell: msys2 {0}",
    "        run: |",
    '          node_bin=$(cygpath -u "$SHACKCQ_NODE_BIN_WINDOWS")',
    '          nsis_bin=$(cygpath -u "$SHACKCQ_NSIS_BIN_WINDOWS")',
    '          cargo_bin=$(cygpath -u "$SHACKCQ_CARGO_BIN_WINDOWS")',
    '          export PATH="$node_bin:$nsis_bin:$cargo_bin:$PATH"',
    "          command -v npx.cmd",
    "          command -v makensis.exe",
    "          command -v cargo.exe",
    "          test -s /mingw64/include/openssl/ssl.h",
    "          test -s /mingw64/lib/libcrypto.dll.a",
    "          test -s /mingw64/lib/libssl.dll.a",
    '          export OPENSSL_ROOT_DIR="$(cygpath -m /mingw64)"',
    '          sh scripts/build_nexus_desktop_candidate.sh windows-x64 "$GITHUB_WORKSPACE/artifacts/windows-x64"',
]
assert step_block(linux_build) == [
    "      - name: Build unsigned Linux x86_64 candidates",
    "        env:",
    "          RUSTUP_TOOLCHAIN: ${{ env.RUST_TOOLCHAIN }}",
    "          SHACKCQ_QT_PREFIX: ${{ env.QT_ROOT_DIR }}",
    '        run: scripts/build_nexus_desktop_candidate.sh linux-x86_64 "$GITHUB_WORKSPACE/artifacts/linux-x86_64"',
]
PY
codesign_line=$(grep -n 'codesign --force --deep --sign' "$macos" | cut -d: -f1)
manifest_line=$(grep -n -- '--manifest "$manifest"' "$macos" | cut -d: -f1)
test "$manifest_line" -gt "$codesign_line"

contract_platform=$(uname -s)
if [ "${SHACKCQ_PACKAGE_CONTRACT_STATIC_ONLY:-0}" = 1 ]; then
  if [ "$contract_platform" = Linux ]; then
    echo 'static-only package cleanup contract is forbidden on Linux' >&2
    exit 1
  fi
  echo 'NEXUS_PACKAGE_CLEANUP_STATIC_CONTRACT_OK exact-owned-processes=true clean-exit-required=true post-sign-manifest=true'
  exit 0
fi

if [ "$contract_platform" = Linux ]; then
  python3 "$xvfb_supervisor_test" >"$contract_scratch/xvfb-cleanup.log"
  grep -Fx 'XVFB_SUPERVISOR_TEST_OK normal=true invalid=true early=true escalation=true pidfd-failure=true' \
    "$contract_scratch/xvfb-cleanup.log" >/dev/null
  python3 "$pidfd_guard_test" >"$contract_scratch/pidfd-guard.log"
  grep -Fx 'PIDFD_PROCESS_GUARD_TEST_OK normal=true mismatch=true exited=true escalation=true' \
    "$contract_scratch/pidfd-guard.log" >/dev/null
fi

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

echo 'NEXUS_PACKAGE_CLEANUP_CONTRACT_OK exact-owned-processes=true clean-exit-required=true post-sign-manifest=true linux-admin-socket=bounded-name mac-term=143-lock-clean'
