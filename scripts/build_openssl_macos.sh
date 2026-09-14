#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

install_root=${1:?OpenSSL install root required}
build_root=${2:?OpenSSL build root required}
version=3.6.4
sha256=9bffaa1ad1e07b354c21bd3324ec02fa15579f45a7d0494b3e74bc449b7333ef
archive="$build_root/openssl-$version.tar.gz"
source="$build_root/openssl-$version"

if [ -s "$install_root/lib/libcrypto.a" ] && [ -s "$install_root/lib/libssl.a" ]; then
  exit 0
fi
mkdir -p "$build_root" "$install_root"
curl -fsSL "https://github.com/openssl/openssl/releases/download/openssl-$version/openssl-$version.tar.gz" -o "$archive"
echo "$sha256  $archive" | shasum -a 256 -c -
rm -rf "$source"
tar -C "$build_root" -xzf "$archive"
(
  cd "$source"
  MACOSX_DEPLOYMENT_TARGET=13.0 ./Configure darwin64-arm64-cc \
    no-shared no-tests no-docs --prefix="$install_root" --openssldir="$install_root/ssl"
  make -j4 build_libs
  make install_dev
)
test -s "$install_root/lib/libcrypto.a"
test -s "$install_root/lib/libssl.a"
