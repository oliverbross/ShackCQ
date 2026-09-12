#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
platform=${1:-}
output=${2:-}
tauri_cli_version=${TAURI_CLI_VERSION:-2.11.4}

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
qt_prefix=${SHACKCQ_QT_PREFIX:?SHACKCQ_QT_PREFIX is required}
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
  for tool in x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++ x86_64-w64-mingw32-gfortran cmake curl make ninja makensis sha256sum tar; do
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
    trap 'rm -rf "$fftw_build"' EXIT
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
if [ "$platform" = windows-x64 ]; then
  isolated_root="${RUNNER_TEMP:-$repo/build}/shackcq-windows-agent-proof"
  owner_token=$(printf 'a%.0s' {1..64})
  stationd_executable="$agent_build/shackcq-stationd.exe"
  rm -rf "$isolated_root"
  "$stationd_executable" --foreground --native-ingress-only \
    --native-owner-token "$owner_token" --ephemeral-root "$isolated_root" \
    --ephemeral-credentials &
  stationd_pid=$!
  stop_stationd() {
    "$stationd_executable" --stop --native-owner-token "$owner_token" >/dev/null 2>&1 || true
    wait "$stationd_pid" 2>/dev/null || true
  }
  trap stop_stationd EXIT
  for _ in {1..40}; do
    "$stationd_executable" --status >/dev/null 2>&1 && break
    sleep 0.1
  done
  "$stationd_executable" --status >/dev/null
  SHACKCQ_TEST_WINDOWS_AGENT_PIPE=1 \
    SHACKCQ_TEST_WINDOWS_AGENT_OWNER_TOKEN="$owner_token" cargo test --locked \
    --manifest-path "$repo/desktop/shackcq-tauri/Cargo.toml" --target "$target" \
    agent_ingress::tests::windows_named_pipe_reaches_the_isolated_qt_agent
  stop_stationd
  trap - EXIT
fi
CARGO_BUILD_TARGET="$target" "$repo/scripts/build_nexus_native_sidecar.sh"
tauri_args=(build --ci --no-sign --target "$target" --bundles "$bundles")
if [ "$platform" = linux-x86_64 ]; then
  tauri_args+=(--config '{"bundle":{"linux":{"deb":{"depends":["libwebkit2gtk-4.1-0","libasound2","libssl3","libsecret-1-0","libgfortran5","libfftw3-single3","libstdc++6","libgcc-s1"]}}}}')
fi
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
  result=$(printf '%s\n' \
    '{"version":1,"commandId":"package-identity","generation":0,"launchNonce":"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn","command":{"type":"IDENTITY"}}' |
    SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
    SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
    SHACKCQ_QUEUE_PATH="$probe_root/queue.json" "$packaged_nexus")
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
  result=$(printf '%s\n' "$request" |
    SHACKCQ_RUNTIME_NONCE=nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn \
    SHACKCQ_QUEUE_KEY_HEX=0000000000000000000000000000000000000000000000000000000000000000 \
    SHACKCQ_QUEUE_PATH="$probe_root/reference-queue.json" "$packaged_nexus")
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
  local payload_tmp agent_path nexus_path main_path socket_path owner_token lib_path main_rc
  payload_tmp=$(mktemp -d)
  agent_path=$(find "$payload_root" -type f -name shackcq-stationd -perm -111 -print -quit)
  nexus_path=$(find "$payload_root" -type f -name shackcq-nexus-runtime -perm -111 -print -quit)
  main_path=$(find "$payload_root" -type f -name shackcq-desktop -perm -111 -print -quit)
  test -n "$agent_path" && test -n "$nexus_path" && test -n "$main_path"
  lib_path=$(find "$payload_root" -type d \( -name lib -o -name lib64 \) -print | paste -sd: -)
  socket_path="$payload_tmp/stationd.sock"
  owner_token=$(printf 'b%.0s' {1..64})
  LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$agent_path" --foreground --native-ingress-only \
      --native-owner-token "$owner_token" --admin-socket "$socket_path" \
      --ephemeral-root "$payload_tmp/agent" --ephemeral-credentials &
  local agent_pid=$!
  for _ in {1..50}; do
    LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
      "$agent_path" --admin-socket "$socket_path" --status >/dev/null 2>&1 && break
    sleep 0.1
  done
  LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$agent_path" --admin-socket "$socket_path" --status >/dev/null
  ! LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$agent_path" --admin-socket "$socket_path" --stop \
      --native-owner-token "$(printf 'c%.0s' {1..64})" >/dev/null 2>&1
  LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$agent_path" --admin-socket "$socket_path" --stop \
      --native-owner-token "$owner_token" >/dev/null
  wait "$agent_pid"
  probe_nexus_identity "$nexus_path" "$payload_tmp/nexus"

  mkdir -p "$payload_tmp/home" "$payload_tmp/runtime" "$payload_tmp/config" "$payload_tmp/data"
  set +e
  timeout --signal=TERM --kill-after=2s 8s xvfb-run -a env \
    HOME="$payload_tmp/home" XDG_RUNTIME_DIR="$payload_tmp/runtime" \
    XDG_CONFIG_HOME="$payload_tmp/config" XDG_DATA_HOME="$payload_tmp/data" \
    SHACKCQ_AGENT_ADMIN_SOCKET="shackcq-package-main-$RANDOM-$RANDOM" \
    SHACKCQ_AGENT_EPHEMERAL_ROOT="$payload_tmp/main-agent" \
    SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
    SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS=1500 \
    LD_LIBRARY_PATH="$lib_path${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$main_path" >"$payload_tmp/main.log" 2>&1
  main_rc=$?
  set -e
  test "$main_rc" -eq 0 -o "$main_rc" -eq 124
  sleep 1
  ! pgrep -f "$payload_root/.*/shackcq-(desktop|stationd|nexus-runtime)" >/dev/null
  printf 'PACKAGED_%s_SAFE_LAUNCH_OK hardware=not-opened tx=disabled\n' "$label"
  rm -rf "$payload_tmp"
}

accept_windows_payload() {
  local payload_root=$1
  local payload_tmp agent_path nexus_path main_path socket_name owner_token main_rc
  payload_tmp=$(mktemp -d)
  agent_path=$(find "$payload_root" -type f -iname shackcq-stationd.exe -print -quit)
  nexus_path=$(find "$payload_root" -type f -iname shackcq-nexus-runtime.exe -print -quit)
  main_path=$(find "$payload_root" -type f -iname shackcq-desktop.exe -print -quit)
  if [ -z "$main_path" ]; then
    main_path=$(find "$payload_root" -type f -iname 'ShackCQ Desktop.exe' -print -quit)
  fi
  test -n "$agent_path" && test -n "$nexus_path" && test -n "$main_path"
  socket_name="shackcq-package-$RANDOM-$RANDOM"
  owner_token=$(printf 'b%.0s' {1..64})
  "$agent_path" --foreground --native-ingress-only \
    --native-owner-token "$owner_token" --admin-socket "$socket_name" \
    --ephemeral-root "$payload_tmp/agent" --ephemeral-credentials &
  local agent_pid=$!
  for _ in {1..50}; do
    "$agent_path" --admin-socket "$socket_name" --status >/dev/null 2>&1 && break
    sleep 0.1
  done
  "$agent_path" --admin-socket "$socket_name" --status >/dev/null
  ! "$agent_path" --admin-socket "$socket_name" --stop \
      --native-owner-token "$(printf 'c%.0s' {1..64})" >/dev/null 2>&1
  "$agent_path" --admin-socket "$socket_name" --stop \
    --native-owner-token "$owner_token" >/dev/null
  wait "$agent_pid"
  probe_nexus_identity "$nexus_path" "$payload_tmp/nexus"

  mkdir -p "$payload_tmp/home"
  set +e
  timeout 8s env HOME="$payload_tmp/home" APPDATA="$payload_tmp/home/AppData/Roaming" \
    LOCALAPPDATA="$payload_tmp/home/AppData/Local" \
    SHACKCQ_AGENT_ADMIN_SOCKET="shackcq-package-main-$RANDOM-$RANDOM" \
    SHACKCQ_AGENT_EPHEMERAL_ROOT="$payload_tmp/main-agent" \
    SHACKCQ_AGENT_EPHEMERAL_CREDENTIALS=1 \
    SHACKCQ_PACKAGE_ACCEPTANCE_EXIT_AFTER_MS=1500 "$main_path" \
    >"$payload_tmp/main.log" 2>&1
  main_rc=$?
  set -e
  test "$main_rc" -eq 0 -o "$main_rc" -eq 124
  sleep 1
  ! tasklist.exe | tr -d '\r' | grep -Eiq 'shackcq-(desktop|stationd|nexus-runtime)\.exe'
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
    7z x -y -o"$nsis_extract" "${packages[0]}" >/dev/null
    for packaged in shackcq-nexus-runtime.exe shackcq-stationd.exe shackcq-hamlib-helper.exe; do
      7z l "${packages[0]}" | grep -Fq "$packaged"
    done
    accept_windows_payload "$nsis_extract"
    rm -rf "$nsis_extract"
    {
      echo 'MAIN_EXECUTABLE'
      x86_64-w64-mingw32-objdump -p "$main_executable" | grep 'DLL Name:' || true
      echo 'NEXUS_RUNTIME_SIDECAR'
      x86_64-w64-mingw32-objdump -p "$staged_sidecar" | grep 'DLL Name:' || true
      echo 'STATION_AGENT'
      x86_64-w64-mingw32-objdump -p "$agent" | grep 'DLL Name:' || true
      echo 'HAMLIB_HELPER'
      x86_64-w64-mingw32-objdump -p "$helper" | grep 'DLL Name:' || true
    } > "$output/WINDOWS_PE_IMPORTS.txt"
    x86_64-w64-mingw32-objdump -f "$main_executable" | grep -q 'pei-x86-64'
    x86_64-w64-mingw32-objdump -f "$staged_sidecar" | grep -q 'pei-x86-64'
    ! grep -Eiq 'lib(gfortran|quadmath|stdc\+\+|winpthread|fftw)[^ ]*\.dll' "$output/WINDOWS_PE_IMPORTS.txt"
    ;;
  linux-x86_64)
    mapfile -d '' debs < <(find "$bundle_root/deb" -maxdepth 1 -type f -name '*.deb' -print0)
    mapfile -d '' appimages < <(find "$bundle_root/appimage" -maxdepth 1 -type f -name '*.AppImage' -print0)
    [ "${#debs[@]}" -eq 1 ] || { echo "expected one Debian package, found ${#debs[@]}" >&2; exit 1; }
    [ "${#appimages[@]}" -eq 1 ] || { echo "expected one AppImage, found ${#appimages[@]}" >&2; exit 1; }
    deb_name=$(basename "${debs[0]}" .deb)
    appimage_name=$(basename "${appimages[0]}" .AppImage)
    cp "${debs[0]}" "$output/${deb_name}-UNSIGNED-UNNOTARIZED.deb"
    cp "${appimages[0]}" "$output/${appimage_name}-UNSIGNED-UNNOTARIZED.AppImage"
    dpkg-deb -c "${debs[0]}" > "$output/DEBIAN_CONTENTS.txt"
    appimage_extract=$(mktemp -d)
    (
      cd "$appimage_extract"
      "${appimages[0]}" --appimage-extract >/dev/null
      find squashfs-root -print | LC_ALL=C sort
    ) > "$output/APPIMAGE_CONTENTS.txt"
    accept_linux_payload "$appimage_extract/squashfs-root" APPIMAGE
    deb_extract=$(mktemp -d)
    dpkg-deb -x "${debs[0]}" "$deb_extract"
    accept_linux_payload "$deb_extract" DEB
    rm -rf "$appimage_extract" "$deb_extract"
    for packaged in shackcq-nexus-runtime shackcq-stationd shackcq-hamlib-helper; do
      grep -Fq "$packaged" "$output/DEBIAN_CONTENTS.txt"
      grep -Fq "$packaged" "$output/APPIMAGE_CONTENTS.txt"
    done
    {
      for binary in "$main_executable" "$staged_sidecar" "$agent" "$helper"; do
        echo "BINARY=$(basename "$binary")"
        file -b "$binary"
        readelf -d "$binary"
        ldd "$binary"
      done
    } > "$output/LINUX_ELF_DEPENDENCIES.txt"
    [ "$(grep -c 'ELF 64-bit.*x86-64' "$output/LINUX_ELF_DEPENDENCIES.txt")" -eq 4 ]
    ! grep -q 'not found' "$output/LINUX_ELF_DEPENDENCIES.txt"
    ! grep -Fq "$repo" "$output/LINUX_ELF_DEPENDENCIES.txt"
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

cat > "$output/CANDIDATE_STATUS.txt" <<EOF
PRODUCT=ShackCQ Nexus Desktop
VERSION=0.2.0
PLATFORM=$platform
TARGET=$target
SOURCE_SHA=$(git -C "$repo" rev-parse HEAD)
NATIVE_BASE_SHA=68cebdc2991cf9754477e29ac82228de6f8b8107
NEXUS_SHA=$(git -C "$repo/third_party/nexus" rev-parse HEAD)
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
