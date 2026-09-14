#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
platform=${1:-}
output=${2:-}
tauri_cli_version=${TAURI_CLI_VERSION:-2.11.4}
nexus_windows_path_patch="$repo/patches/nexus-tempo-fast-windows-path.patch"
nexus_windows_path_overlay_tool="$repo/scripts/apply_nexus_windows_path_overlay.py"
nexus_windows_path_target="$repo/third_party/nexus/crates/tempo-fast-sys/build.rs"
sidecar_lock_tool="$repo/scripts/claim_package_sidecar_lock.py"
pidfd_process_guard="$repo/scripts/pidfd_process_guard.py"
stationd_readiness="$repo/scripts/wait_stationd_ready.py"
nexus_patch_applied=0
nexus_overlay_recovery_required=0
nexus_overlay_backup_dir=
nexus_overlay_backup=
fftw_build=
stationd_pid=
stationd_executable=
owned_stationd_token=
owned_stationd_socket=
main_pid=
linux_owned_agent_pid=
linux_owned_agent_path=
linux_owned_agent_socket=
xvfb_supervisor_pid=
xvfb_marker=
xvfb_stop=
xvfb_done=
windows_owned_agent_pid=
windows_owned_agent_path=
windows_owned_agent_socket=
packaged_windows_path=
cleanup_paths=()
generated_sidecar_dir=
generated_sidecar_lock=
generated_sidecars=()
capture_windows_owned_agent() {
  local native_path observed_pid
  native_path=$(cygpath -w "$1")
  observed_pid=$(SHACKCQ_ACCEPT_AGENT_PATH="$native_path" \
    SHACKCQ_ACCEPT_AGENT_SOCKET="$2" powershell.exe -NoProfile -NonInteractive \
      -Command '$p=Get-CimInstance Win32_Process | Where-Object { [StringComparer]::OrdinalIgnoreCase.Equals($_.ExecutablePath,$env:SHACKCQ_ACCEPT_AGENT_PATH) -and $_.CommandLine -like ("*"+$env:SHACKCQ_ACCEPT_AGENT_SOCKET+"*") } | Select-Object -First 1 -ExpandProperty ProcessId; if($p){$p}' \
      | tr -d '\r' | head -n 1)
  case "$observed_pid" in
    ''|*[!0-9]*) return 0 ;;
    *)
      windows_owned_agent_pid=$observed_pid
      windows_owned_agent_path=$native_path
      windows_owned_agent_socket=$2
      ;;
  esac
}
windows_owned_agent_is_live() {
  [ -n "$windows_owned_agent_pid" ] || return 1
  test "$(SHACKCQ_ACCEPT_AGENT_PID="$windows_owned_agent_pid" \
    SHACKCQ_ACCEPT_AGENT_PATH="$windows_owned_agent_path" \
    SHACKCQ_ACCEPT_AGENT_SOCKET="$windows_owned_agent_socket" \
    powershell.exe -NoProfile -NonInteractive \
      -Command '$p=Get-CimInstance Win32_Process -Filter ("ProcessId="+$env:SHACKCQ_ACCEPT_AGENT_PID); if($p -and [StringComparer]::OrdinalIgnoreCase.Equals($p.ExecutablePath,$env:SHACKCQ_ACCEPT_AGENT_PATH) -and $p.CommandLine -like ("*"+$env:SHACKCQ_ACCEPT_AGENT_SOCKET+"*")){"LIVE"}' \
      | tr -d '\r')" = LIVE
}
stop_windows_owned_agent() {
  if windows_owned_agent_is_live; then
    taskkill.exe /PID "$windows_owned_agent_pid" /T /F >/dev/null 2>&1 || true
  fi
  windows_owned_agent_pid=
  windows_owned_agent_path=
  windows_owned_agent_socket=
}
linux_owned_agent_has_exited() {
  [ -n "$linux_owned_agent_pid" ] || return 0
  python3 "$pidfd_process_guard" check-exited --pid "$linux_owned_agent_pid" \
    --executable "$linux_owned_agent_path" --socket "$linux_owned_agent_socket" >/dev/null
}
stop_linux_owned_agent() {
  [ -n "$linux_owned_agent_pid" ] || return 0
  if ! python3 "$pidfd_process_guard" terminate --pid "$linux_owned_agent_pid" \
    --executable "$linux_owned_agent_path" --socket "$linux_owned_agent_socket" >/dev/null; then
    echo "refusing or failing to terminate unverified GUI-owned Agent" >&2
    return 1
  fi
  linux_owned_agent_pid=
  linux_owned_agent_path=
  linux_owned_agent_socket=
}
stop_owned_xvfb() {
  local wait_status supervisor_running=1
  [ -n "$xvfb_supervisor_pid" ] || return 0
  [ -n "$xvfb_marker" ] && [ -n "$xvfb_stop" ] && [ -n "$xvfb_done" ] || {
    echo "owned Xvfb supervisor state is incomplete" >&2
    return 1
  }
  python3 - "$xvfb_stop" "$xvfb_marker" <<'PY'
import os
from pathlib import Path
import sys

destination = Path(sys.argv[1])
temporary = destination.with_name(destination.name + ".tmp")
descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
    stream.write(sys.argv[2] + "\n")
    stream.flush()
    os.fsync(stream.fileno())
os.replace(temporary, destination)
PY
  for _ in {1..800}; do
    [ -s "$xvfb_done" ] && break
    sleep 0.025
  done
  if [ ! -s "$xvfb_done" ]; then
    echo "owned Xvfb supervisor did not publish bounded completion" >&2
    return 1
  fi
  for _ in {1..80}; do
    if ! jobs -pr | grep -qx "$xvfb_supervisor_pid"; then
      supervisor_running=0
      break
    fi
    sleep 0.025
  done
  if [ "$supervisor_running" != 0 ]; then
    echo "owned Xvfb supervisor did not exit after completion" >&2
    return 1
  fi
  if wait "$xvfb_supervisor_pid"; then
    wait_status=0
  else
    wait_status=$?
  fi
  if [ "$wait_status" != 0 ]; then
    echo "owned Xvfb supervisor exited with status $wait_status" >&2
    return 1
  fi
  if ! python3 - "$xvfb_done" "$xvfb_marker" <<'PY'
import json
from pathlib import Path
import sys

outcome = json.loads(Path(sys.argv[1]).read_text())
assert outcome == {
    "marker": sys.argv[2],
    "state": "STOPPED",
    "status": outcome["status"],
    "termination": outcome["termination"],
}
assert outcome["termination"] in {"TERM", "KILL"}
PY
  then
    echo "owned Xvfb supervisor completion is invalid" >&2
    return 1
  fi
  xvfb_supervisor_pid=
  xvfb_marker=
  xvfb_stop=
  xvfb_done=
}
stop_owned_stationd() {
  if [ -n "$stationd_pid" ]; then
    if [ -n "$owned_stationd_socket" ]; then
      if [ "${platform:-}" = windows-x64 ] && [ -n "$packaged_windows_path" ]; then
        run_packaged_windows_binary "$stationd_executable" \
          --admin-socket "$owned_stationd_socket" --stop \
          --native-owner-token "$owned_stationd_token" >/dev/null 2>&1 || true
      else
        "$stationd_executable" --admin-socket "$owned_stationd_socket" --stop \
          --native-owner-token "$owned_stationd_token" >/dev/null 2>&1 || true
      fi
    else
      "$stationd_executable" --stop --native-owner-token "$owned_stationd_token" \
        >/dev/null 2>&1 || true
    fi
    kill "$stationd_pid" 2>/dev/null || true
    wait "$stationd_pid" 2>/dev/null || true
    stationd_pid=
  fi
}

run_packaged_windows_binary() {
  [ -n "$packaged_windows_path" ] || {
    echo "packaged Windows execution PATH is not initialized" >&2
    return 1
  }
  env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH -u QML2_IMPORT_PATH \
    -u OPENSSL_MODULES -u SSL_CERT_DIR -u SSL_CERT_FILE \
    PATH="$packaged_windows_path" "$@"
}
cleanup() {
  cleanup_status=$?
  trap - EXIT HUP INT TERM
  set +e
  if [ -n "$main_pid" ]; then
    if [ "${platform:-}" = windows-x64 ]; then
      taskkill.exe /PID "$main_pid" /T /F >/dev/null 2>&1 || true
    else
      kill "$main_pid" 2>/dev/null || true
    fi
    wait "$main_pid" 2>/dev/null || true
    main_pid=
  fi
  if [ "${platform:-}" = windows-x64 ]; then
    stop_windows_owned_agent
  fi
  stop_linux_owned_agent || cleanup_status=1
  stop_owned_xvfb || cleanup_status=1
  stop_owned_stationd
  if [ "$nexus_overlay_recovery_required" = 1 ]; then
    if [ -s "$nexus_overlay_backup" ] && [ -s "${nexus_overlay_backup}.metadata.json" ]; then
      if python3 "$nexus_windows_path_overlay_tool" restore \
        "$nexus_windows_path_target" "$nexus_overlay_backup"; then
        nexus_overlay_recovery_required=0
      else
        echo "Nexus overlay recovery retained at: $nexus_overlay_backup_dir" >&2
        cleanup_status=1
      fi
    elif python3 "$nexus_windows_path_overlay_tool" verify-preimage \
      "$nexus_windows_path_target"; then
      nexus_overlay_recovery_required=0
    else
      echo "Nexus overlay state is not recoverable; retained: $nexus_overlay_backup_dir" >&2
      cleanup_status=1
    fi
    nexus_patch_applied=0
  fi
  if [ "$nexus_overlay_recovery_required" = 0 ] && [ -n "$nexus_overlay_backup_dir" ]; then
    rm -rf "$nexus_overlay_backup_dir"
    nexus_overlay_backup_dir=
    nexus_overlay_backup=
  fi
  if [ -n "$fftw_build" ]; then
    rm -rf "$fftw_build"
  fi
  if [ "${#cleanup_paths[@]}" -gt 0 ]; then
    rm -rf "${cleanup_paths[@]}"
  fi
  generated_sidecars_absent=1
  if [ "${#generated_sidecars[@]}" -gt 0 ]; then
    for generated_sidecar in "${generated_sidecars[@]}"; do
      if ! rm -f -- "$generated_sidecar"; then
        echo "failed to remove owned generated sidecar: $generated_sidecar" >&2
        generated_sidecars_absent=0
      fi
      if [ -e "$generated_sidecar" ] || [ -L "$generated_sidecar" ]; then
        echo "owned generated sidecar remains: $generated_sidecar" >&2
        generated_sidecars_absent=0
      fi
    done
  fi
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
  if [ -n "$generated_sidecar_dir" ]; then
    rmdir "$generated_sidecar_dir" 2>/dev/null || true
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

claim_generated_sidecars() {
  local suffix= candidate candidate_lock owner_source owner_started owner_output
  [ "$platform" = windows-x64 ] && suffix=.exe
  local candidate_dir="$repo/desktop/shackcq-tauri/binaries"
  mkdir -p "$candidate_dir"
  candidate_lock="$candidate_dir/.shackcq-package-$target.lock"
  owner_source=$(git -C "$repo" rev-parse HEAD)
  owner_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  owner_output=${output:0:512}
  trap '' HUP INT TERM
  set +e
  python3 "$sidecar_lock_tool" "$candidate_lock" "$$" "${HOSTNAME:-unknown}" \
    "$owner_started" "$owner_source" "$platform" "$owner_output"
  claim_status=$?
  set -e
  if [ "$claim_status" = 0 ]; then
    generated_sidecar_dir=$candidate_dir
    generated_sidecar_lock=$candidate_lock
  fi
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if [ "$claim_status" != 0 ]; then
    if [ "$claim_status" = 17 ]; then
      echo "another package build owns target $target: $candidate_lock" >&2
      echo "inspect the JSON lock and all exact target paths; never auto-break it, and remove it manually only after proving the recorded PID inactive" >&2
    else
      echo "failed to atomically claim package target $target (lock helper status $claim_status): $candidate_lock" >&2
    fi
    return 1
  fi
  local candidates=(
    "$generated_sidecar_dir/shackcq-nexus-runtime-$target$suffix"
    "$generated_sidecar_dir/shackcq-stationd-$target$suffix"
    "$generated_sidecar_dir/shackcq-hamlib-helper-$target$suffix"
  )
  for candidate in "${candidates[@]}"; do
    if [ -e "$candidate" ] || [ -L "$candidate" ]; then
      echo "refusing to overwrite pre-existing generated sidecar: $candidate" >&2
      return 1
    fi
  done
  generated_sidecars=("${candidates[@]}")
}

write_test_generated_sidecars() {
  mkdir -p "$generated_sidecar_dir"
  if [ "${SHACKCQ_TEST_GENERATED_SIDECARS:-}" = directory ]; then
    mkdir "${generated_sidecars[0]}"
  else
    for generated_sidecar in "${generated_sidecars[@]}"; do
      printf 'owned test sidecar\n' >"$generated_sidecar"
    done
  fi
}

configure_windows_fftw_rust_link() {
  local expected_archive fftw_rust_lib fftw_gcc_archive
  expected_archive="$FFTW_MINGW_PREFIX/lib/libfftw3f.a"
  if [ ! -s "$expected_archive" ]; then
    echo "Windows FFTW archive is missing: $expected_archive" >&2
    return 1
  fi
  fftw_rust_lib="$FFTW_MINGW_PREFIX/lib"
  if command -v cygpath >/dev/null 2>&1; then
    fftw_rust_lib=$(cygpath -m "$fftw_rust_lib")
  fi
  case "$fftw_rust_lib" in
    *[[:space:]]*)
      echo "Windows FFTW Rust link path contains whitespace: $fftw_rust_lib" >&2
      return 1
      ;;
    [A-Za-z]:/*) ;;
    *)
      echo "Windows FFTW Rust link path is not a mixed absolute path: $fftw_rust_lib" >&2
      return 1
      ;;
  esac
  export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }-Lnative=$fftw_rust_lib"
  fftw_gcc_archive=$(LIBRARY_PATH="$FFTW_MINGW_PREFIX/lib${LIBRARY_PATH:+:$LIBRARY_PATH}" \
    x86_64-w64-mingw32-gcc -print-file-name=libfftw3f.a)
  if command -v cygpath >/dev/null 2>&1; then
    fftw_gcc_archive=$(cygpath -u "$fftw_gcc_archive")
  fi
  if [ ! -s "$fftw_gcc_archive" ]; then
    echo "Windows linker did not resolve libfftw3f.a: $fftw_gcc_archive" >&2
    return 1
  fi
  if ! cmp "$expected_archive" "$fftw_gcc_archive"; then
    echo "Windows linker resolved a different libfftw3f.a" >&2
    return 1
  fi
  printf 'Windows FFTW Rust link preflight: %s\n' "$fftw_rust_lib/libfftw3f.a"
}

apply_nexus_windows_overlay() {
  nexus_overlay_backup_dir=$(mktemp -d)
  nexus_overlay_backup="$nexus_overlay_backup_dir/build.rs.preimage"
  nexus_overlay_recovery_required=1
  python3 "$nexus_windows_path_overlay_tool" apply \
    "$nexus_windows_path_target" "$nexus_overlay_backup"
  nexus_patch_applied=1
}

case "$platform" in
  windows-x64)
    target=x86_64-pc-windows-gnu
    bundles=nsis
    agent_qt_version=6.10.2
    ;;
  linux-x86_64)
    target=x86_64-unknown-linux-gnu
    bundles=deb,appimage
    agent_qt_version=6.11.2
    ;;
  *)
    echo "usage: $0 {windows-x64|linux-x86_64} OUTPUT_DIRECTORY" >&2
    exit 64
    ;;
esac
test -n "$output" || { echo "output directory is required" >&2; exit 64; }
claim_generated_sidecars

if [ "${SHACKCQ_TEST_WINDOWS_FFTW_LINK:-}" = 1 ]; then
  configure_windows_fftw_rust_link
  printf 'RUSTFLAGS=%s\n' "$RUSTFLAGS"
  exit 0
fi

if [ -n "${SHACKCQ_TEST_SIDECAR_LOCK_READY:-}" ]; then
  printf 'locked\n' >"$SHACKCQ_TEST_SIDECAR_LOCK_READY"
  lock_released=0
  for _ in {1..100}; do
    if [ -e "$SHACKCQ_TEST_SIDECAR_LOCK_READY.release" ]; then
      lock_released=1
      break
    fi
    sleep 0.05
  done
  test "$lock_released" = 1
  exit 0
fi

# Focused lifecycle hook: exercises the production apply/EXIT-cleanup path
# without requiring a Windows toolchain or opening any runtime/hardware path.
case "${SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP:-}" in
  success)
    test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
    apply_nexus_windows_overlay
    [ -z "${SHACKCQ_TEST_GENERATED_SIDECARS:-}" ] || write_test_generated_sidecars
    exit 0
    ;;
  failure)
    test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
    apply_nexus_windows_overlay
    [ -z "${SHACKCQ_TEST_GENERATED_SIDECARS:-}" ] || write_test_generated_sidecars
    exit 73
    ;;
  "") ;;
  *) echo "invalid SHACKCQ_TEST_NEXUS_OVERLAY_CLEANUP value" >&2; exit 64 ;;
esac
qt_prefix=${SHACKCQ_QT_PREFIX:?SHACKCQ_QT_PREFIX is required}
if [ "$platform" = windows-x64 ]; then
  qt_runtime_bin="$qt_prefix/bin"
  if command -v cygpath >/dev/null 2>&1; then
    qt_runtime_bin=$(cygpath -u "$qt_runtime_bin")
  fi
  test -d "$qt_runtime_bin"
fi
source_sha=$(git -C "$repo" rev-parse HEAD)
web_sha=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' \
  "$repo/desktop/shared-digi-ui-snapshot/frontend-manifest.json")
frontend_content_sha=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["contentSha256"])' \
  "$repo/desktop/shared-digi-ui-manifest.json")
rust_version=$(rustc --version | awk '{print $2}')
package_metadata_dir="$repo/desktop/shackcq-tauri/.package-metadata-$target"
cleanup_paths+=("$package_metadata_dir")
sh "$repo/scripts/stage_nexus_package_legal.sh" "$package_metadata_dir" "$repo" "$agent_qt_version" "$platform"
python3 "$repo/scripts/write_nexus_package_metadata.py" \
  --output "$package_metadata_dir/PACKAGE_MANIFEST.json" \
  --source "$source_sha" --web "$web_sha" \
  --frontend-content-sha "$frontend_content_sha" \
  --nexus 7618390658f8f92431dec0ac65979b84f2c0fb76 \
  --platform "$platform" --qt "$agent_qt_version" \
  --rust "$rust_version" --tauri-cli "$tauri_cli_version"
if command -v npx >/dev/null 2>&1; then
  npx_command=npx
elif command -v npx.cmd >/dev/null 2>&1; then
  npx_command=npx.cmd
else
  echo "missing npx command" >&2
  exit 1
fi

test "$(git -C "$repo" rev-parse HEAD)" = "${GITHUB_SHA:-$(git -C "$repo" rev-parse HEAD)}"
git -C "$repo" merge-base --is-ancestor 68cebdc2991cf9754477e29ac82228de6f8b8107 HEAD
test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
python3 "$repo/scripts/check_nexus_native_desktop.py"
python3 "$repo/desktop/shackcq-tauri/scripts/verify-shared-ui.py"
test "$("$npx_command" --yes "@tauri-apps/cli@${tauri_cli_version}" --version | awk '{print $NF}')" = "$tauri_cli_version"

if [ "$platform" = windows-x64 ]; then
  for tool in x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++ x86_64-w64-mingw32-gfortran cmake cmp curl ldd make ninja makensis sha256sum tar; do
    command -v "$tool" >/dev/null || { echo "missing Windows cross tool: $tool" >&2; exit 1; }
  done
  fftw_version=3.3.10
  fftw_sha256=56c932549852cddcfafdab3820b0200c7742675be92179e59e6215b340e26467
  fftw_parent=${RUNNER_TEMP:-$repo/build}
  if command -v cygpath >/dev/null 2>&1; then
    fftw_parent=$(cygpath -u "$fftw_parent")
  fi
  case "$fftw_parent" in
    [A-Za-z]:\\*)
      echo "Windows FFTW prefix was not normalized for MSYS2: $fftw_parent" >&2
      exit 1
      ;;
  esac
  export FFTW_MINGW_PREFIX="$fftw_parent/shackcq-fftw-mingw"
  if [ ! -s "$FFTW_MINGW_PREFIX/lib/libfftw3f.a" ]; then
    fftw_build=$(mktemp -d)
    curl -fsSL "https://www.fftw.org/fftw-${fftw_version}.tar.gz" -o "$fftw_build/fftw.tar.gz"
    echo "$fftw_sha256  $fftw_build/fftw.tar.gz" | sha256sum -c -
    tar -C "$fftw_build" -xzf "$fftw_build/fftw.tar.gz"
    (
      cd "$fftw_build/fftw-${fftw_version}"
      ./configure --host=x86_64-w64-mingw32 --enable-float --enable-static \
        --disable-shared --prefix="$FFTW_MINGW_PREFIX"
      make -j"$(nproc)"
      make install
    )
    test -s "$FFTW_MINGW_PREFIX/lib/libfftw3f.a"
  fi
  test -s "$FFTW_MINGW_PREFIX/lib/pkgconfig/fftw3f.pc"
  export PKG_CONFIG_PATH="$FFTW_MINGW_PREFIX/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
  fftw_cmake_prefix=$FFTW_MINGW_PREFIX
  if command -v cygpath >/dev/null 2>&1; then
    fftw_cmake_prefix=$(cygpath -w "$FFTW_MINGW_PREFIX")
  fi
  export CMAKE_PREFIX_PATH="$fftw_cmake_prefix${CMAKE_PREFIX_PATH:+;$CMAKE_PREFIX_PATH}"
  test "$(pkg-config --modversion fftw3f)" = "$fftw_version"
  test "$(/mingw64/bin/openssl.exe version | awk '{print $1, $2}')" = "OpenSSL 3.6.4"
  configure_windows_fftw_rust_link
  boost_version_header=/mingw64/include/boost/version.hpp
  test -s "$boost_version_header"
  boost_version=$(awk '/^#define BOOST_VERSION / { print $3 }' "$boost_version_header")
  test -n "$boost_version" && test "$boost_version" -ge 107000
  printf 'Windows Boost header preflight: BOOST_VERSION=%s\n' "$boost_version"
  apply_nexus_windows_overlay
  cargo test --locked --manifest-path "$repo/desktop/nexus-runtime/Cargo.toml" \
    --target "$target" --no-run
else
  for tool in gfortran cmake ninja pkg-config; do
    command -v "$tool" >/dev/null || { echo "missing Linux build tool: $tool" >&2; exit 1; }
  done
  pkg-config --exists fftw3f alsa webkit2gtk-4.1
  cargo test --locked --manifest-path "$repo/desktop/nexus-runtime/Cargo.toml" --target "$target"
fi

agent_build="$repo/build/desktop/nexus-$platform"
hamlib_root="$repo/build/desktop/nexus-hamlib-$platform"
sh "$repo/scripts/build_hamlib_posix.sh" "$hamlib_root" \
  "$repo/core/third_party/hamlib" "$repo/build/desktop/nexus-hamlib-build-$platform"
cmake -S "$repo/desktop" -B "$agent_build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$qt_prefix" \
  -DSHACKCQ_BUILD_TESTS=OFF \
  -DSHACKCQ_BUILD_NATIVE_DIGI=OFF \
  -DSHACKCQ_QT_VERSION="$agent_qt_version" \
  -DSHACKCQ_REQUIRE_HAMLIB=ON \
  -DSHACKCQ_HAMLIB_ROOT="$hamlib_root"
export SHACKCQ_DESKTOP_BUILD_DIR="$agent_build"
export SHACKCQ_TAURI_TARGET="$target"
sh "$repo/desktop/shackcq-tauri/scripts/build-stationd-sidecar.sh"
test -s "$agent_build/_deps/opus-src/COPYING"
cp "$agent_build/_deps/opus-src/COPYING" "$package_metadata_dir/OPUS-COPYING"
windows_runtime_dir=
if [ "$platform" = windows-x64 ]; then
  stationd_executable="$agent_build/shackcq-stationd.exe"
  test -x "$stationd_executable"
  windows_runtime_dir="$repo/desktop/shackcq-tauri/.package-windows-runtime-$target"
  cleanup_paths+=("$windows_runtime_dir")
  mkdir -p "$windows_runtime_dir"
  "$qt_runtime_bin/windeployqt.exe" --release --no-translations \
    --dir "$windows_runtime_dir" "$stationd_executable"
  "$qt_runtime_bin/windeployqt.exe" --release --no-translations \
    --dir "$windows_runtime_dir" "$agent_build/shackcq-hamlib-helper.exe"
  for openssl_dll in libcrypto-3-x64.dll libssl-3-x64.dll; do
    test -s "$agent_build/$openssl_dll"
    cp "$agent_build/$openssl_dll" "$windows_runtime_dir/$openssl_dll"
  done
  test -s "$windows_runtime_dir/Qt6Core.dll"
  test -s "$windows_runtime_dir/sqldrivers/qsqlite.dll"
  find "$windows_runtime_dir/tls" -type f -iname 'q*backend.dll' -print -quit | grep -q .
  : >"$package_metadata_dir/MINGW_RUNTIME_PROVENANCE.txt"
  for mingw_runtime in libgcc_s_seh-1.dll libstdc++-6.dll libwinpthread-1.dll; do
    if [ -s "$windows_runtime_dir/$mingw_runtime" ]; then
      mingw_package=$(pacman -Qqo "/mingw64/bin/$mingw_runtime")
      pacman -Q "$mingw_package" >>"$package_metadata_dir/MINGW_RUNTIME_PROVENANCE.txt"
      while IFS= read -r mingw_license; do
        test -s "$mingw_license"
        cp "$mingw_license" \
          "$package_metadata_dir/MINGW-${mingw_package}-$(basename "$mingw_license")"
      done < <(pacman -Ql "$mingw_package" | \
        awk '$2 ~ /\/share\/licenses\// && $2 !~ /\/$/ { print $2 }')
    fi
  done
  test -s "$package_metadata_dir/MINGW_RUNTIME_PROVENANCE.txt"
fi
CARGO_BUILD_TARGET="$target" "$repo/scripts/build_nexus_native_sidecar.sh"
package_config="$repo/desktop/shackcq-tauri/.tauri-package-config-$target.json"
cleanup_paths+=("$package_config")
python3 - "$package_config" "$target" "$platform" "$windows_runtime_dir" <<'PY'
import json
import sys
from pathlib import Path

output, target, platform, windows_runtime = sys.argv[1:]
root = f".package-metadata-{target}"
metadata_root = Path(output).parent / root
if not metadata_root.is_dir():
    raise SystemExit("package metadata directory is missing")
bundle = {"resources": {}}
for source in sorted(path for path in metadata_root.rglob("*") if path.is_file()):
    relative = source.relative_to(metadata_root).as_posix()
    bundle["resources"][f"{root}/{relative}"] = f"legal/{relative}"
if platform == "linux-x86_64":
    bundle["linux"] = {"deb": {"depends": [
        "libwebkit2gtk-4.1-0", "libasound2", "libssl3", "libsecret-1-0",
        "libgfortran5", "libfftw3-single3", "libstdc++6", "libgcc-s1",
    ]}}
if platform == "windows-x64":
    runtime_root = Path(windows_runtime)
    if not runtime_root.is_dir():
        raise SystemExit("Windows runtime staging directory is missing")
    source_root = f".package-windows-runtime-{target}"
    for source in sorted(path for path in runtime_root.rglob("*") if path.is_file()):
        relative = source.relative_to(runtime_root).as_posix()
        bundle["resources"][f"{source_root}/{relative}"] = relative
json.dump({"bundle": bundle}, open(output, "w"), separators=(",", ":"))
PY
tauri_args=(build --ci --no-sign --target "$target" --bundles "$bundles" --config "$package_config")
tauri_args+=(-- --locked)
(
  cd "$repo/desktop/shackcq-tauri"
  "$npx_command" --yes "@tauri-apps/cli@${tauri_cli_version}" "${tauri_args[@]}"
)
git -C "$repo" diff --exit-code -- \
  desktop/nexus-runtime/Cargo.lock desktop/shackcq-tauri/Cargo.lock

bundle_root="$repo/desktop/shackcq-tauri/target/$target/release/bundle"
main_executable="$repo/desktop/shackcq-tauri/target/$target/release/shackcq-desktop"
sidecar="$repo/desktop/shackcq-tauri/binaries/shackcq-nexus-runtime-$target"
agent="$repo/desktop/shackcq-tauri/binaries/shackcq-stationd-$target"
helper="$repo/desktop/shackcq-tauri/binaries/shackcq-hamlib-helper-$target"
[ "$platform" = windows-x64 ] && { main_executable="$main_executable.exe"; sidecar="$sidecar.exe"; agent="$agent.exe"; helper="$helper.exe"; }
test -s "$main_executable"
test -s "$sidecar"
test -s "$agent"
test -s "$helper"
mkdir -p "$output"
sidecar_name=$(basename "$sidecar")
case "$sidecar_name" in
  *.exe) staged_sidecar="$output/${sidecar_name%.exe}-UNSIGNED-UNNOTARIZED.exe" ;;
  *) staged_sidecar="$output/${sidecar_name}-UNSIGNED-UNNOTARIZED" ;;
esac
cp "$sidecar" "$staged_sidecar"

probe_nexus_identity() {
  local packaged_nexus=$1 probe_root=$2 result fixture fixture_json fixture_sha request
  mkdir -p "$probe_root"
  if [ "$platform" = windows-x64 ]; then
    result=$(printf '%s\n' \
      '{"version":1,"commandId":"package-identity","generation":0,"launchNonce":"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn","command":{"type":"IDENTITY"}}' |
      SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
      SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
      SHACKCQ_QUEUE_PATH="$probe_root/queue.json" \
      run_packaged_windows_binary "$packaged_nexus")
  else
    result=$(printf '%s\n' \
      '{"version":1,"commandId":"package-identity","generation":0,"launchNonce":"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn","command":{"type":"IDENTITY"}}' |
      SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
      SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
      SHACKCQ_QUEUE_PATH="$probe_root/queue.json" "$packaged_nexus")
  fi
  python3 - "$result" <<'PY'
import json
import sys

result = json.loads(sys.argv[1])
assert result["ok"] is True
assert result["code"] == "ENGINE_IDENTITY"
assert result["payload"]["engine"] == "kd9taw/Nexus native libtempo"
assert result["payload"]["legacyFallback"] is False
assert result["payload"]["upstreamCommit"] == "7618390658f8f92431dec0ac65979b84f2c0fb76"
assert {"FT8", "FT4", "FST4W", "WSPR"}.issubset(result["payload"]["compiledModes"])
PY
  fixture="$repo/third_party/nexus/crates/ft8/tests/fixtures/ft8_sample.wav"
  fixture_sha=$(sha256sum "$fixture" | awk '{print $1}')
  test "$fixture_sha" = 9feb99c275770a6618538026da7decc6b09eb6cf63121e5168fa86dcdf00c2f5
  fixture_json=$fixture
  if [ "$platform" = windows-x64 ]; then
    fixture_json=$(cygpath -w "$fixture")
    case "$fixture_json" in
      [A-Za-z]:\\*) ;;
      *) echo "Windows recording path was not converted for native JSON: $fixture_json" >&2; exit 1 ;;
    esac
  fi
  request=$(python3 - "$fixture_json" "$fixture_sha" <<'PY'
import json
import sys
print(json.dumps({
    "version": 1,
    "commandId": "package-reference-decode",
    "generation": 1,
    "launchNonce": "nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn",
    "command": {"type": "DECODE_RECORDING_FILE", "parameters": {
        "path": sys.argv[1], "sha256": sys.argv[2], "mode": "FT8"
    }},
}, separators=(",", ":")))
PY
)
  if [ "$platform" = windows-x64 ]; then
    result=$(printf '%s\n' "$request" |
      SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
      SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
      SHACKCQ_QUEUE_PATH="$probe_root/reference-queue.json" \
      run_packaged_windows_binary "$packaged_nexus")
  else
    result=$(printf '%s\n' "$request" |
      SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
      SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
      SHACKCQ_QUEUE_PATH="$probe_root/reference-queue.json" "$packaged_nexus")
  fi
  python3 - "$result" <<'PY'
import json
import sys
result = json.loads(sys.argv[1])
assert result["ok"] is True
assert result["code"] == "REFERENCE_RECORDING_DECODED"
assert result["payload"]["decodeCount"] >= 1
assert "CQ F5RXL IN94" in result["payload"]["messages"]
PY
}

accept_linux_payload() {
  local payload_root=$1 label=$2
  local payload_tmp agent_path nexus_path main_path launch_path launch_cwd socket_name owner_token main_rc runtime_probe
  local main_socket agent_status observed_pid display_number stationd_stderr
  local -a launch_env=()
  payload_tmp=$(mktemp -d)
  cleanup_paths+=("$payload_tmp")
  agent_path=$(find "$payload_root" -type f -name shackcq-stationd -perm -111 -print -quit)
  nexus_path=$(find "$payload_root" -type f -name shackcq-nexus-runtime -perm -111 -print -quit)
  main_path=$(find "$payload_root" -type f -name shackcq-desktop -perm -111 -print -quit)
  test -n "$agent_path" && test -n "$nexus_path" && test -n "$main_path"
  launch_path=$main_path
  launch_cwd="$payload_tmp/home"
  if [ "$label" = APPIMAGE ]; then
    launch_path="$payload_root/AppRun"
    launch_cwd=$payload_root
    launch_env+=("APPDIR=$payload_root")
    test -x "$launch_path"
  fi
  # stationd resolves this bounded logical name beneath its private runtime
  # directory; it intentionally rejects path separators supplied by callers.
  socket_name="shackcq-package-${RANDOM}-${RANDOM}.sock"
  case "$socket_name" in
    [A-Za-z0-9]* ) ;;
    * ) echo "invalid generated stationd socket name" >&2; return 1 ;;
  esac
  case "$socket_name" in
    *[!A-Za-z0-9._-]* )
      echo "invalid generated stationd socket name" >&2
      return 1
      ;;
  esac
  test "${#socket_name}" -le 96
  runtime_probe=$(env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    SHACKCQ_PACKAGE_RUNTIME_HERMETIC=1 "$agent_path" --package-runtime-probe)
  python3 -c 'import json,sys; p=json.loads(sys.argv[1]); assert p["qsqlite"] is True and p["tls"] is True and p["tlsBackend"]' \
    "$runtime_probe"
  printf 'PACKAGED_%s_QT_RUNTIME_OK qsqlite=true tls=true\n' "$label"
  owner_token=$(printf 'b%.0s' {1..64})
  stationd_stderr="$payload_tmp/stationd.stderr"
  : >"$stationd_stderr"
  chmod 0600 "$stationd_stderr"
  env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    "$agent_path" --foreground --native-ingress-only \
      --native-owner-token "$owner_token" --admin-socket "$socket_name" \
      --ephemeral-root "$payload_tmp/agent" --ephemeral-credentials \
      2>"$stationd_stderr" &
  stationd_pid=$!
  stationd_executable=$agent_path
  owned_stationd_socket=$socket_name
  owned_stationd_token=$owner_token
  agent_status=$(env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    python3 "$stationd_readiness" --executable "$agent_path" \
      --socket "$socket_name" --expected-pid "$stationd_pid" \
      --stderr-log "$stationd_stderr" --overall-timeout 5 \
      --probe-timeout 0.25)
  ! env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    "$agent_path" --admin-socket "$socket_name" --stop \
      --native-owner-token "$(printf 'c%.0s' {1..64})" >/dev/null 2>&1
  env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    "$agent_path" --admin-socket "$socket_name" --stop \
      --native-owner-token "$owner_token" >/dev/null
  wait "$stationd_pid"
  stationd_pid=
  owned_stationd_socket=
  owned_stationd_token=
  probe_nexus_identity "$nexus_path" "$payload_tmp/nexus"

  mkdir -p "$payload_tmp/home" "$payload_tmp/runtime" "$payload_tmp/config" "$payload_tmp/data"
  chmod 0700 "$payload_tmp/runtime"
  main_socket="shackcq-package-main-$RANDOM-$RANDOM.sock"
  xvfb_marker="shackcq-package-xvfb-$RANDOM-$RANDOM"
  xvfb_stop="$payload_tmp/xvfb-stop"
  xvfb_done="$payload_tmp/xvfb-done.json"
  python3 "$repo/scripts/supervise_xvfb.py" \
    --executable "$(command -v Xvfb)" --marker "$xvfb_marker" \
    --display "$payload_tmp/xvfb-display" --ready "$payload_tmp/xvfb-ready.json" \
    --stop "$xvfb_stop" --done "$xvfb_done" \
    >"$payload_tmp/xvfb.log" 2>&1 &
  xvfb_supervisor_pid=$!
  for _ in {1..40}; do
    [ -s "$payload_tmp/xvfb-ready.json" ] && [ -s "$payload_tmp/xvfb-display" ] && break
    [ -s "$xvfb_done" ] && break
    sleep 0.05
  done
  test -s "$payload_tmp/xvfb-ready.json"
  test -s "$payload_tmp/xvfb-display"
  display_number=$(tr -d '\r\n' <"$payload_tmp/xvfb-display")
  case "$display_number" in
    ''|*[!0-9]*) echo "owned Xvfb did not publish a display number" >&2; return 1 ;;
  esac
  set +e
  (
    cd "$launch_cwd"
    timeout --signal=TERM --kill-after=2s 8s env \
      DISPLAY=":$display_number" \
      HOME="$payload_tmp/home" XDG_RUNTIME_DIR="$payload_tmp/runtime" \
      XDG_CONFIG_HOME="$payload_tmp/config" XDG_DATA_HOME="$payload_tmp/data" \
      "${launch_env[@]}" \
      SHACKCQ_AGENT_ADMIN_SOCKET="$main_socket" \
      SHACKCQ_AGENT_EPHEMERAL_ROOT="$payload_tmp/main-agent" \
      SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
      SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS=1500 \
      env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
      "$launch_path"
  ) >"$payload_tmp/main.log" 2>&1 &
  main_pid=$!
  set -e
  observed_pid=
  for _ in {1..160}; do
    if agent_status=$(env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
      "$agent_path" --admin-socket "$main_socket" --status 2>/dev/null); then
      observed_pid=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["result"]["processId"])' \
        "$agent_status" 2>/dev/null || true)
      case "$observed_pid" in
        ''|*[!0-9]*) ;;
        *) break ;;
      esac
    fi
    kill -0 "$main_pid" 2>/dev/null || break
    sleep 0.05
  done
  case "$observed_pid" in
    ''|*[!0-9]*) echo "packaged GUI-owned Agent process ID was not observed" >&2; return 1 ;;
  esac
  linux_owned_agent_pid=$observed_pid
  linux_owned_agent_path=$(readlink "/proc/$observed_pid/exe" 2>/dev/null || true)
  linux_owned_agent_socket=$main_socket
  test "$linux_owned_agent_path" = "$agent_path"
  ! linux_owned_agent_has_exited
  set +e
  wait "$main_pid"
  main_rc=$?
  main_pid=
  set -e
  if [ "$main_rc" -ne 0 ]; then
    cat "$payload_tmp/main.log" >&2
    return 1
  fi
  ! env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH \
    "$agent_path" --admin-socket "$main_socket" --status >/dev/null 2>&1
  linux_owned_agent_has_exited
  linux_owned_agent_pid=
  linux_owned_agent_path=
  linux_owned_agent_socket=
  stop_owned_xvfb
  printf 'PACKAGED_%s_SAFE_LAUNCH_OK hardware=not-opened tx=disabled\n' "$label"
  rm -rf "$payload_tmp"
}

stage_linux_qt_runtime() {
  local payload_root=$1 layout=$2 lib_dir plugin_dir binary rpath plugin_rpath plugin
  local changed object dependency_name dependency_path destination
  case "$layout" in
    APPIMAGE)
      lib_dir="$payload_root/usr/lib"
      plugin_dir="$payload_root/usr/plugins"
      rpath='$ORIGIN/../lib'
      plugin_rpath='$ORIGIN/../../lib'
      ;;
    DEB)
      lib_dir="$payload_root/usr/lib/shackcq"
      plugin_dir="$lib_dir/plugins"
      rpath='$ORIGIN/../lib/shackcq'
      plugin_rpath='$ORIGIN/../..'
      ;;
    *) echo "unknown Linux payload layout: $layout" >&2; return 1 ;;
  esac
  mkdir -p "$lib_dir" "$plugin_dir/sqldrivers" "$plugin_dir/tls"
  for binary in "$payload_root/usr/bin/shackcq-stationd" \
      "$payload_root/usr/bin/shackcq-hamlib-helper"; do
    test -x "$binary"
    patchelf --set-rpath "$rpath" "$binary"
  done
  for plugin in "$qt_prefix/plugins/sqldrivers/libqsqlite.so" \
      "$qt_prefix/plugins/tls/libqcertonlybackend.so" \
      "$qt_prefix/plugins/tls/libqopensslbackend.so"; do
    test -s "$plugin"
    case "$plugin" in
      */sqldrivers/*) cp -L "$plugin" "$plugin_dir/sqldrivers/" ;;
      */tls/*) cp -L "$plugin" "$plugin_dir/tls/" ;;
    esac
  done
  # Close the Qt/ICU graph recursively from the actual sidecars and selected
  # plugins. System GTK/WebKit/OpenSSL dependencies remain declared platform
  # dependencies; Qt and ICU are package-owned and may never resolve via CI.
  changed=1
  while [ "$changed" = 1 ]; do
    changed=0
    while IFS= read -r -d '' object; do
      while IFS=$'\t' read -r dependency_name dependency_path; do
        case "$dependency_name" in
          libQt6*.so*|libicu*.so*) ;;
          *) continue ;;
        esac
        test -s "$dependency_path"
        destination="$lib_dir/$dependency_name"
        if [ ! -s "$destination" ]; then
          cp -L "$dependency_path" "$destination"
          changed=1
        fi
      done < <(LD_LIBRARY_PATH="$lib_dir:$qt_prefix/lib" ldd "$object" | \
        awk '$2 == "=>" && $3 ~ /^\// { print $1 "\t" $3 }')
    done < <(find "$payload_root/usr/bin" "$lib_dir" "$plugin_dir" -type f -print0)
  done
  find "$lib_dir" -maxdepth 1 -type f -name 'lib*.so*' -exec patchelf --set-rpath '$ORIGIN' {} +
  find "$plugin_dir" -type f -name '*.so' -exec patchelf --set-rpath "$plugin_rpath" {} +
}

refresh_debian_metadata() {
  local payload_root=$1 installed_kib
  (
    cd "$payload_root"
    find usr -type f -print0 | LC_ALL=C sort -z | xargs -0 md5sum
  ) >"$payload_root/DEBIAN/md5sums"
  installed_kib=$(du -sk "$payload_root/usr" | awk '{print $1}')
  python3 - "$payload_root/DEBIAN/control" "$installed_kib" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
text = path.read_text()
line = f"Installed-Size: {int(sys.argv[2])}"
if re.search(r"(?m)^Installed-Size:.*$", text):
    text = re.sub(r"(?m)^Installed-Size:.*$", line, text)
else:
    text = text.rstrip() + "\n" + line + "\n"
path.write_text(text)
PY
}

audit_linux_payload() {
  local payload_root=$1 label=$2 binary object dependency_report dynamic
  dependency_report=$3
  for binary in "$payload_root/usr/bin/shackcq-desktop" \
      "$payload_root/usr/bin/shackcq-nexus-runtime" \
      "$payload_root/usr/bin/shackcq-stationd" \
      "$payload_root/usr/bin/shackcq-hamlib-helper"; do
    test -x "$binary"
    {
      printf 'PAYLOAD=%s BINARY=%s\n' "$label" "$(basename "$binary")"
      file -b "$binary"
      readelf -d "$binary"
      env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH ldd "$binary"
    } >>"$dependency_report"
  done
  while IFS= read -r -d '' object; do
    file -b "$object" | grep -q '^ELF ' || continue
    {
      printf 'PAYLOAD=%s OBJECT=%s\n' "$label" "${object#"$payload_root"/}"
      readelf -d "$object"
      dynamic=$(readelf -d "$object" 2>/dev/null || true)
      if [ -n "$dynamic" ]; then
        env -u LD_LIBRARY_PATH -u QT_PLUGIN_PATH -u QML2_IMPORT_PATH ldd "$object"
      fi
    } >>"$dependency_report"
  done < <(find "$payload_root" -type f -print0 | LC_ALL=C sort -z)
  ! grep -q 'not found' "$dependency_report"
  ! grep -Eq '/(home/runner|Users|opt/hostedtoolcache|__w)/|[A-Za-z]:[/\\]' "$dependency_report"
  python3 - "$dependency_report" <<'PY'
import re
import sys

for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if "(RPATH)" not in line and "(RUNPATH)" not in line:
        continue
    match = re.search(r"Library (?:rpath|runpath): \[([^]]*)\]", line)
    if not match:
        raise SystemExit(f"unparseable loader path: {line.rstrip()}")
    for entry in match.group(1).split(":"):
        if entry and not (entry == "$ORIGIN" or entry.startswith("$ORIGIN/")):
            raise SystemExit(f"non-package-relative loader path: {entry}")
PY
}

audit_windows_payload() {
  local payload_root=$1 app_root=$2 report=$3 object object_dir dependency packaged_dependency
  case "$app_root" in
    "$payload_root"/*) ;;
    *) echo "Windows application root escapes extracted payload: $app_root" >&2; return 1 ;;
  esac
  : >"$report"
  while IFS= read -r -d '' object; do
    file -b "$object" | grep -Eqi 'PE32\+.*x86-64' || continue
    printf 'OBJECT=%s\n' "${object#"$payload_root"/}" >>"$report"
    x86_64-w64-mingw32-objdump -p "$object" | grep 'DLL Name:' >>"$report" || true
    while IFS= read -r dependency; do
      test -n "$dependency"
      object_dir=$(dirname "$object")
      packaged_dependency=$(find "$object_dir" -maxdepth 1 -type f -iname "$dependency" -print -quit)
      if [ -z "$packaged_dependency" ]; then
        packaged_dependency=$(find "$app_root" -maxdepth 1 -type f -iname "$dependency" -print -quit)
      fi
      if [ -n "$packaged_dependency" ]; then
        continue
      fi
      if [ -e "/c/Windows/System32/$dependency" ]; then
        continue
      fi
      case "$dependency" in
        api-ms-win-*.dll|ext-ms-win-*.dll) continue ;;
      esac
      echo "unresolved packaged Windows PE dependency: $dependency from $object" >&2
      return 1
    done < <(x86_64-w64-mingw32-objdump -p "$object" | \
      sed -n 's/^[[:space:]]*DLL Name: //p')
  done < <(find "$payload_root" -type f \( -iname '*.exe' -o -iname '*.dll' \) -print0)
  grep -Fq 'Qt6Core.dll' "$report"
  grep -Fq 'libcrypto-3-x64.dll' "$report"
}

accept_windows_payload() {
  local payload_root=$1
  local payload_tmp app_root agent_path nexus_path helper_path main_path socket_name owner_token main_rc main_socket runtime_probe
  local windows_root windows_system32
  payload_tmp=$(mktemp -d)
  cleanup_paths+=("$payload_tmp")
  agent_path=$(find "$payload_root" -type f -iname shackcq-stationd.exe -print -quit)
  test -n "$agent_path"
  app_root=$(dirname "$agent_path")
  nexus_path=$(find "$app_root" -maxdepth 1 -type f -iname shackcq-nexus-runtime.exe -print -quit)
  helper_path=$(find "$app_root" -maxdepth 1 -type f -iname shackcq-hamlib-helper.exe -print -quit)
  main_path=$(find "$app_root" -maxdepth 1 -type f -iname shackcq-desktop.exe -print -quit)
  if [ -z "$main_path" ]; then
    main_path=$(find "$app_root" -maxdepth 1 -type f -iname 'ShackCQ Desktop.exe' -print -quit)
  fi
  test -n "$nexus_path" && test -n "$helper_path" && test -n "$main_path"
  windows_root=${SYSTEMROOT:-${SystemRoot:-C:\\Windows}}
  windows_root=$(cygpath -u "$windows_root")
  windows_system32="$windows_root/System32"
  test -d "$windows_root" && test -d "$windows_system32"
  packaged_windows_path="$app_root:$windows_system32:$windows_root"
  case "$packaged_windows_path" in
    *"$qt_prefix"*|*'/mingw64/'*|*'/msys64/'*|*'/home/runner/'*|*'/opt/hostedtoolcache/'*)
      echo "packaged Windows execution PATH is not hermetic: $packaged_windows_path" >&2
      return 1
      ;;
  esac
  runtime_probe=$(run_packaged_windows_binary "$agent_path" --package-runtime-probe)
  python3 -c 'import json,sys; p=json.loads(sys.argv[1]); assert p["qsqlite"] is True and p["tls"] is True and p["tlsBackend"]; assert p["tlsBuildVersion"].startswith("OpenSSL 3.6.4"); assert p["tlsRuntimeVersion"].startswith("OpenSSL 3.6.4")' \
    "$runtime_probe"
  echo 'PACKAGED_WINDOWS_QT_RUNTIME_OK qsqlite=true tls=true'
  printf '%s\n' '{"requestId":"package-helper-close","epoch":1,"operation":"close","parameters":{}}' | \
    run_packaged_windows_binary "$helper_path"
  echo 'PACKAGED_WINDOWS_HAMLIB_HELPER_CLOSE_OK hardware=not-opened'
  socket_name="shackcq-package-$RANDOM-$RANDOM"
  owner_token=$(printf 'b%.0s' {1..64})
  run_packaged_windows_binary "$agent_path" --foreground --native-ingress-only \
    --native-owner-token "$owner_token" --admin-socket "$socket_name" \
    --ephemeral-root "$payload_tmp/agent" --ephemeral-credentials &
  stationd_pid=$!
  stationd_executable=$agent_path
  owned_stationd_socket=$socket_name
  owned_stationd_token=$owner_token
  for _ in {1..50}; do
    run_packaged_windows_binary "$agent_path" --admin-socket "$socket_name" --status >/dev/null 2>&1 && break
    sleep 0.1
  done
  run_packaged_windows_binary "$agent_path" --admin-socket "$socket_name" --status >/dev/null
  ! run_packaged_windows_binary "$agent_path" --admin-socket "$socket_name" --stop \
      --native-owner-token "$(printf 'c%.0s' {1..64})" >/dev/null 2>&1
  run_packaged_windows_binary "$agent_path" --admin-socket "$socket_name" --stop \
    --native-owner-token "$owner_token" >/dev/null
  wait "$stationd_pid"
  stationd_pid=
  owned_stationd_socket=
  owned_stationd_token=
  probe_nexus_identity "$nexus_path" "$payload_tmp/nexus"

  mkdir -p "$payload_tmp/home"
  main_socket="shackcq-package-main-$RANDOM-$RANDOM"
  env -u QT_PLUGIN_PATH -u QT_QPA_PLATFORM_PLUGIN_PATH -u QML2_IMPORT_PATH \
    -u OPENSSL_MODULES -u SSL_CERT_DIR -u SSL_CERT_FILE \
    PATH="$packaged_windows_path" \
    HOME="$payload_tmp/home" APPDATA="$payload_tmp/home/AppData/Roaming" \
    LOCALAPPDATA="$payload_tmp/home/AppData/Local" \
    SHACKCQ_AGENT_ADMIN_SOCKET="$main_socket" \
    SHACKCQ_AGENT_EPHEMERAL_ROOT="$payload_tmp/main-agent" \
    SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
    SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS=1500 "$main_path" \
    >"$payload_tmp/main.log" 2>&1 &
  main_pid=$!
  main_rc=125
  for _ in {1..80}; do
    capture_windows_owned_agent "$agent_path" "$main_socket"
    if ! kill -0 "$main_pid" 2>/dev/null; then
      capture_windows_owned_agent "$agent_path" "$main_socket"
      set +e
      wait "$main_pid"
      main_rc=$?
      set -e
      main_pid=
      break
    fi
    sleep 0.1
  done
  if [ "$main_rc" -ne 0 ]; then
    cat "$payload_tmp/main.log" >&2
    return 1
  fi
  sleep 1
  capture_windows_owned_agent "$agent_path" "$main_socket"
  if windows_owned_agent_is_live; then
    echo "packaged Windows Agent remained after GUI exit" >&2
    return 1
  fi
  stop_windows_owned_agent
  echo 'PACKAGED_WINDOWS_SAFE_LAUNCH_OK hardware=not-opened tx=disabled'
  rm -rf "$payload_tmp"
}

case "$platform" in
  windows-x64)
    mapfile -d '' packages < <(find "$bundle_root/nsis" -maxdepth 1 -type f -name '*.exe' -print0)
    [ "${#packages[@]}" -eq 1 ] || { echo "expected one NSIS installer, found ${#packages[@]}" >&2; exit 1; }
    package_name=$(basename "${packages[0]}" .exe)
    cp "${packages[0]}" "$output/${package_name}-UNSIGNED-UNNOTARIZED.exe"
    nsis_extract=$(mktemp -d)
    cleanup_paths+=("$nsis_extract")
    7z x -y -o"$nsis_extract" "${packages[0]}" >/dev/null
    for packaged in shackcq-nexus-runtime.exe shackcq-stationd.exe shackcq-hamlib-helper.exe; do
      7z l "${packages[0]}" | grep -Fq "$packaged"
    done
    for metadata in legal/COPYING legal/NOTICE legal/THIRD_PARTY_NOTICES.txt \
        legal/Qt-LGPL-3.0-only.txt legal/Qt-GPL-3.0-only.txt legal/ICU-73-LICENSE.txt legal/ICU-74-LICENSE.txt legal/OPENSSL-LICENSE.txt legal/LEGAL_PROVENANCE.txt legal/NEXUS-COPYING \
        legal/NEXUS-NOTICE legal/HAMLIB-COPYING legal/HAMLIB-COPYING.LIB \
        legal/HAMLIB-LICENSE legal/OPUS-COPYING legal/FFTW-COPYING legal/PACKAGE_MANIFEST.json; do
      7z l "${packages[0]}" | grep -Fq "$metadata"
    done
    for runtime_file in Qt6Core.dll sqldrivers/qsqlite.dll \
        libcrypto-3-x64.dll libssl-3-x64.dll; do
      7z l "${packages[0]}" | grep -Fq "$runtime_file"
    done
    7z l "${packages[0]}" | grep -Eq 'tls[/\\]q[^/\\]*backend\.dll'
    windows_agent=$(find "$nsis_extract" -type f -iname shackcq-stationd.exe -print -quit)
    test -n "$windows_agent"
    windows_app_root=$(dirname "$windows_agent")
    audit_windows_payload "$nsis_extract" "$windows_app_root" "$output/WINDOWS_PE_IMPORTS.txt"
    accept_windows_payload "$nsis_extract"
    rm -rf "$nsis_extract"
    x86_64-w64-mingw32-objdump -f "$main_executable" | grep -q 'pei-x86-64'
    x86_64-w64-mingw32-objdump -f "$staged_sidecar" | grep -q 'pei-x86-64'
    7z l "${packages[0]}" | grep -Fq 'legal/MINGW_RUNTIME_PROVENANCE.txt'
    7z l "${packages[0]}" | grep -Fq 'legal/MINGW-'
    ;;
  linux-x86_64)
    mapfile -d '' debs < <(find "$bundle_root/deb" -maxdepth 1 -type f -name '*.deb' -print0)
    mapfile -d '' appimages < <(find "$bundle_root/appimage" -maxdepth 1 -type f -name '*.AppImage' -print0)
    [ "${#debs[@]}" -eq 1 ] || { echo "expected one Debian package, found ${#debs[@]}" >&2; exit 1; }
    [ "${#appimages[@]}" -eq 1 ] || { echo "expected one AppImage, found ${#appimages[@]}" >&2; exit 1; }
    deb_name=$(basename "${debs[0]}" .deb)
    appimage_name=$(basename "${appimages[0]}" .AppImage)
    appimage_extract=$(mktemp -d)
    cleanup_paths+=("$appimage_extract")
    (
      cd "$appimage_extract"
      "${appimages[0]}" --appimage-extract >/dev/null
    ) >/dev/null
    stage_linux_qt_runtime "$appimage_extract/squashfs-root" APPIMAGE
    appimage_offset=$("${appimages[0]}" --appimage-offset)
    dd if="${appimages[0]}" of="$appimage_extract/runtime" bs=1 count="$appimage_offset" status=none
    mksquashfs "$appimage_extract/squashfs-root" "$appimage_extract/payload.squashfs" \
      -noappend -root-owned -quiet
    cat "$appimage_extract/runtime" "$appimage_extract/payload.squashfs" \
      >"$output/${appimage_name}-UNSIGNED-UNNOTARIZED.AppImage"
    chmod 0755 "$output/${appimage_name}-UNSIGNED-UNNOTARIZED.AppImage"
    final_appimage_extract=$(mktemp -d)
    cleanup_paths+=("$final_appimage_extract")
    (
      cd "$final_appimage_extract"
      "$output/${appimage_name}-UNSIGNED-UNNOTARIZED.AppImage" --appimage-extract >/dev/null
    ) >/dev/null
    (
      cd "$final_appimage_extract/squashfs-root"
      find . -print | LC_ALL=C sort
    ) >"$output/APPIMAGE_CONTENTS.txt"
    accept_linux_payload "$final_appimage_extract/squashfs-root" APPIMAGE
    deb_extract=$(mktemp -d)
    cleanup_paths+=("$deb_extract")
    dpkg-deb -R "${debs[0]}" "$deb_extract"
    stage_linux_qt_runtime "$deb_extract" DEB
    refresh_debian_metadata "$deb_extract"
    dpkg-deb --root-owner-group --build "$deb_extract" \
      "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb" >/dev/null
    dpkg-deb -c "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb" > "$output/DEBIAN_CONTENTS.txt"
    final_deb_extract=$(mktemp -d)
    cleanup_paths+=("$final_deb_extract")
    dpkg-deb -R "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb" "$final_deb_extract"
    (
      cd "$final_deb_extract"
      md5sum -c DEBIAN/md5sums >/dev/null
    )
    expected_installed_kib=$(du -sk "$final_deb_extract/usr" | awk '{print $1}')
    actual_installed_kib=$(sed -n 's/^Installed-Size: //p' "$final_deb_extract/DEBIAN/control")
    test "$actual_installed_kib" = "$expected_installed_kib"
    accept_linux_payload "$final_deb_extract" DEB
    for packaged in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
      grep -Fq "$packaged" "$output/DEBIAN_CONTENTS.txt"
      grep -Fq "$packaged" "$output/APPIMAGE_CONTENTS.txt"
    done
    : > "$output/LINUX_ELF_DEPENDENCIES.txt"
    audit_linux_payload "$final_appimage_extract/squashfs-root" APPIMAGE "$output/LINUX_ELF_DEPENDENCIES.txt"
    audit_linux_payload "$final_deb_extract" DEB "$output/LINUX_ELF_DEPENDENCIES.txt"
    [ "$(grep -c 'ELF 64-bit.*x86-64' "$output/LINUX_ELF_DEPENDENCIES.txt")" -eq 8 ]
    grep -Fq 'usr/lib/shackcq/libQt6Core.so.6' "$output/DEBIAN_CONTENTS.txt"
    grep -Fq 'usr/lib/shackcq/plugins/sqldrivers/libqsqlite.so' "$output/DEBIAN_CONTENTS.txt"
    for metadata in legal/COPYING legal/NOTICE legal/THIRD_PARTY_NOTICES.txt \
        legal/Qt-LGPL-3.0-only.txt legal/Qt-GPL-3.0-only.txt legal/ICU-73-LICENSE.txt legal/ICU-74-LICENSE.txt legal/OPENSSL-LICENSE.txt legal/LEGAL_PROVENANCE.txt legal/NEXUS-COPYING \
        legal/NEXUS-NOTICE legal/HAMLIB-COPYING legal/HAMLIB-COPYING.LIB \
        legal/HAMLIB-LICENSE legal/OPUS-COPYING legal/FFTW-COPYING legal/PACKAGE_MANIFEST.json; do
      grep -Fq "$metadata" "$output/DEBIAN_CONTENTS.txt"
      grep -Fq "$metadata" "$output/APPIMAGE_CONTENTS.txt"
    done
    test -s "$final_deb_extract/DEBIAN/md5sums"
    grep -Eq '^Installed-Size: [1-9][0-9]*$' "$final_deb_extract/DEBIAN/control"
    rm -rf "$appimage_extract" "$final_appimage_extract" "$deb_extract" "$final_deb_extract"
    dpkg-deb --info "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb" > "$output/DEBIAN_PACKAGE_INFO.txt"
    python3 - "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb" <<'PY'
import re
import subprocess
import sys

depends = subprocess.check_output(["dpkg-deb", "-f", sys.argv[1], "Depends"], text=True)
names = {re.split(r"[ (]", item.strip(), maxsplit=1)[0] for item in depends.split(",")}
required = {"libgfortran5", "libfftw3-single3", "libstdc++6", "libgcc-s1"}
missing = sorted(required - names)
if missing:
    raise SystemExit("Debian package is missing runtime dependencies: " + ", ".join(missing))
PY
    ;;
esac

nexus_overlay_applied=NO
if [ "$platform" = windows-x64 ]; then
  nexus_overlay_applied=YES
fi
nexus_overlay_sha=$(sha256sum "$nexus_windows_path_patch" | awk '{print $1}')
nexus_overlay_tool_sha=$(sha256sum "$nexus_windows_path_overlay_tool" | awk '{print $1}')
cat > "$output/CANDIDATE_STATUS.txt" <<EOF
PRODUCT=ShackCQ Nexus Desktop
VERSION=0.2.0
PLATFORM=$platform
TARGET=$target
SOURCE_SHA=$(git -C "$repo" rev-parse HEAD)
NATIVE_BASE_SHA=68cebdc2991cf9754477e29ac82228de6f8b8107
NEXUS_SHA=$(git -C "$repo/third_party/nexus" rev-parse HEAD)
NEXUS_WINDOWS_BUILD_OVERLAY=patches/nexus-tempo-fast-windows-path.patch
NEXUS_WINDOWS_BUILD_OVERLAY_SHA256=$nexus_overlay_sha
NEXUS_WINDOWS_BUILD_OVERLAY_TOOL=scripts/apply_nexus_windows_path_overlay.py
NEXUS_WINDOWS_BUILD_OVERLAY_TOOL_SHA256=$nexus_overlay_tool_sha
NEXUS_WINDOWS_BUILD_OVERLAY_APPLIED=$nexus_overlay_applied
SHARED_DIGI_WEB_SHA=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["webCommit"])' "$repo/desktop/shared-digi-ui-manifest.json")
SIGNING_STATUS=UNSIGNED
NOTARIZATION_STATUS=UNNOTARIZED
DISTRIBUTION=GITHUB_WORKFLOW_ARTIFACT_ONLY
RUN_ACCEPTANCE=PACKAGED_PAYLOAD_NATIVE_INGRESS_NEXUS_IDENTITY_AND_BOUNDED_GUI_SMOKE
PACKAGE_SCOPE=TAURI_DIGI_DESKTOP_WITH_OWNED_NATIVE_INGRESS_AGENT_AND_HAMLIB_HELPER
CANONICAL_LOGBOOK_AGENT=BUNDLED_CONNECT_EXISTING_OR_OWNED_NO_HARDWARE_AUTOCONNECT
WINDOWS_CANONICAL_LOGBOOK_AGENT_BUILD=CI_PROOF_REQUIRED_NATIVE_MINGW_QT_6_10_2
LINUX_CANONICAL_LOGBOOK_AGENT_BUILD=CI_PROOF_REQUIRED_NATIVE_QT_6_11_2
PHYSICAL_AUDIO_CAT_ACCEPTANCE=PENDING
RF_TX_ACCEPTANCE=NOT_AUTHORIZED
PRODUCTION_DIGI_TX=DISABLED
EOF
(
  cd "$output"
  sha256sum ./* > SHA256SUMS.txt
)
test -s "$output/SHA256SUMS.txt"
