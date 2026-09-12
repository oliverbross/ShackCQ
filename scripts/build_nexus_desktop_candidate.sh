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
    ;;
  linux-x86_64)
    target=x86_64-unknown-linux-gnu
    bundles=deb,appimage
    ;;
  *)
    echo "usage: $0 {windows-x64|linux-x86_64} OUTPUT_DIRECTORY" >&2
    exit 64
    ;;
esac
test -n "$output" || { echo "output directory is required" >&2; exit 64; }

test "$(git -C "$repo" rev-parse HEAD)" = "${GITHUB_SHA:-$(git -C "$repo" rev-parse HEAD)}"
git -C "$repo" merge-base --is-ancestor 68cebdc2991cf9754477e29ac82228de6f8b8107 HEAD
test "$(git -C "$repo/third_party/nexus" rev-parse HEAD)" = 7618390658f8f92431dec0ac65979b84f2c0fb76
python3 "$repo/scripts/check_nexus_native_desktop.py"
python3 "$repo/desktop/shackcq-tauri/scripts/verify-shared-ui.py"
test "$(npx --yes "@tauri-apps/cli@${tauri_cli_version}" --version | awk '{print $NF}')" = "$tauri_cli_version"

if [ "$platform" = windows-x64 ]; then
  for tool in x86_64-w64-mingw32-gcc x86_64-w64-mingw32-g++ x86_64-w64-mingw32-gfortran cmake curl make ninja makensis sha256sum tar; do
    command -v "$tool" >/dev/null || { echo "missing Windows cross tool: $tool" >&2; exit 1; }
  done
  fftw_version=3.3.10
  fftw_sha256=56c932549852cddcfafdab3820b0200c7742675be92179e59e6215b340e26467
  export FFTW_MINGW_PREFIX="${RUNNER_TEMP:-$repo/build}/shackcq-fftw-mingw"
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

CARGO_BUILD_TARGET="$target" "$repo/scripts/build_nexus_native_sidecar.sh"
tauri_args=(build --ci --no-sign --target "$target" --bundles "$bundles")
if [ "$platform" = linux-x86_64 ]; then
  tauri_args+=(--config '{"bundle":{"linux":{"deb":{"depends":["libwebkit2gtk-4.1-0","libasound2","libssl3","libsecret-1-0","libgfortran5","libfftw3-single3","libstdc++6","libgcc-s1"]}}}}')
fi
tauri_args+=(-- --locked)
(
  cd "$repo/desktop/shackcq-tauri"
  npx --yes "@tauri-apps/cli@${tauri_cli_version}" "${tauri_args[@]}"
)
git -C "$repo" diff --exit-code -- \
  desktop/nexus-runtime/Cargo.lock desktop/shackcq-tauri/Cargo.lock

bundle_root="$repo/desktop/shackcq-tauri/target/$target/release/bundle"
main_executable="$repo/desktop/shackcq-tauri/target/$target/release/shackcq-desktop"
sidecar="$repo/desktop/shackcq-tauri/binaries/shackcq-nexus-runtime-$target"
[ "$platform" = windows-x64 ] && { main_executable="$main_executable.exe"; sidecar="$sidecar.exe"; }
test -s "$main_executable"
test -s "$sidecar"
mkdir -p "$output"
sidecar_name=$(basename "$sidecar")
case "$sidecar_name" in
  *.exe) staged_sidecar="$output/${sidecar_name%.exe}-UNSIGNED-UNNOTARIZED.exe" ;;
  *) staged_sidecar="$output/${sidecar_name}-UNSIGNED-UNNOTARIZED" ;;
esac
cp "$sidecar" "$staged_sidecar"

case "$platform" in
  windows-x64)
    mapfile -d '' packages < <(find "$bundle_root/nsis" -maxdepth 1 -type f -name '*.exe' -print0)
    [ "${#packages[@]}" -eq 1 ] || { echo "expected one NSIS installer, found ${#packages[@]}" >&2; exit 1; }
    package_name=$(basename "${packages[0]}" .exe)
    cp "${packages[0]}" "$output/${package_name}-UNSIGNED-UNNOTARIZED.exe"
    {
      echo 'MAIN_EXECUTABLE'
      x86_64-w64-mingw32-objdump -p "$main_executable" | grep 'DLL Name:' || true
      echo 'NEXUS_RUNTIME_SIDECAR'
      x86_64-w64-mingw32-objdump -p "$staged_sidecar" | grep 'DLL Name:' || true
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
    {
      for binary in "$main_executable" "$staged_sidecar"; do
        echo "BINARY=$(basename "$binary")"
        file -b "$binary"
        readelf -d "$binary"
        ldd "$binary"
      done
    } > "$output/LINUX_ELF_DEPENDENCIES.txt"
    [ "$(grep -c 'ELF 64-bit.*x86-64' "$output/LINUX_ELF_DEPENDENCIES.txt")" -eq 2 ]
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
RUN_ACCEPTANCE=NOT_PERFORMED
CANONICAL_LOGBOOK_AGENT=NOT_BUNDLED_REQUIRES_SEPARATE_SHACKCQ_AGENT
PHYSICAL_AUDIO_CAT_ACCEPTANCE=PENDING
RF_TX_ACCEPTANCE=NOT_AUTHORIZED
PRODUCTION_DIGI_TX=DISABLED
EOF
(
  cd "$output"
  sha256sum ./* > SHA256SUMS.txt
)
test -s "$output/SHA256SUMS.txt"
