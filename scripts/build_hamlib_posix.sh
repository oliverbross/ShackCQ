#!/bin/sh
set -eu

install_root=${1:?Hamlib install root required}
source_root=${2:-"$(pwd)/core/third_party/hamlib"}
build_root=${3:-"${TMPDIR:-/tmp}/shackcq-hamlib-build"}

source_root=$(cd "$source_root" && pwd)
repo_root=$(cd "$source_root/../../.." && pwd)
case "$build_root" in
  ""|"/") echo "unsafe Hamlib build root" >&2; exit 2 ;;
esac
test -f "$repo_root/docs/radio/hamlib/SOURCE_MANIFEST.json"
test -x "$source_root/configure"
rm -rf "$build_root"
mkdir -p "$build_root" "$install_root/include/hamlib" "$install_root/lib"
build_root=$(cd "$build_root" && pwd)
install_root=$(cd "$install_root" && pwd)
cd "$build_root"
"$source_root/configure" \
  --disable-shared --enable-static --with-pic \
  --without-libusb --without-indi --without-readline --without-cxx-binding \
  --disable-parallel --disable-html-matrix --disable-pytest \
  --disable-dependency-tracking
make -C src -j2 libhamlib.la ACLOCAL=: AUTOCONF=: AUTOHEADER=: AUTOMAKE=:
cp src/.libs/libhamlib.a "$install_root/lib/libhamlib.a"
cp include/hamlib/config.h "$install_root/include/hamlib/config.h"
cp -R "$source_root/include/hamlib/." "$install_root/include/hamlib/"
test -s "$install_root/lib/libhamlib.a"
