#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

destination=${1:?destination directory is required}
repo=${2:?repository root is required}
qt_version=${3:?Qt runtime version is required}
platform=${4:?package platform is required}
mkdir -p "$destination"
cp "$repo/COPYING" "$repo/NOTICE" "$repo/desktop/resources/THIRD_PARTY_NOTICES.txt" "$destination/"
cp "$repo/third_party/nexus/COPYING" "$destination/NEXUS-COPYING"
cp "$repo/third_party/nexus/NOTICE" "$destination/NEXUS-NOTICE"
cp "$repo/core/third_party/hamlib/COPYING" "$destination/HAMLIB-COPYING"
cp "$repo/core/third_party/hamlib/COPYING.LIB" "$destination/HAMLIB-COPYING.LIB"
cp "$repo/core/third_party/hamlib/LICENSE" "$destination/HAMLIB-LICENSE"

curl -fsSL "https://raw.githubusercontent.com/qt/qtbase/v${qt_version}/LICENSES/LGPL-3.0-only.txt" \
  -o "$destination/Qt-LGPL-3.0-only.txt"
curl -fsSL "https://raw.githubusercontent.com/qt/qtbase/v${qt_version}/LICENSES/GPL-3.0-only.txt" \
  -o "$destination/Qt-GPL-3.0-only.txt"
curl -fsSL https://raw.githubusercontent.com/unicode-org/icu/release-74-2/LICENSE \
  -o "$destination/ICU-74-LICENSE.txt"
curl -fsSL https://raw.githubusercontent.com/unicode-org/icu/release-73-2/icu4c/LICENSE \
  -o "$destination/ICU-73-LICENSE.txt"
curl -fsSL https://raw.githubusercontent.com/openssl/openssl/openssl-3.6.4/LICENSE.txt \
  -o "$destination/OPENSSL-LICENSE.txt"
curl -fsSL https://raw.githubusercontent.com/FFTW/fftw3/fftw-3.3.10/COPYING \
  -o "$destination/FFTW-COPYING"
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/Qt-LGPL-3.0-only.txt" da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/Qt-GPL-3.0-only.txt" 8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/ICU-74-LICENSE.txt" 17510cf7a58b4879b887ec05a45d72cf1b73544dd9ec7e72f20110ed104229ee
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/ICU-73-LICENSE.txt" f3005e195ff74d8812cc1f182a1c446fab678d70a10e3dada497585befee5416
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/OPENSSL-LICENSE.txt" 7d5450cb2d142651b8afa315b5f238efc805dad827d91ba367d8516bc9d49e7a
python3 -c 'import hashlib,pathlib,sys; p=pathlib.Path(sys.argv[1]); actual=hashlib.sha256(p.read_bytes()).hexdigest(); expected=sys.argv[2]; raise SystemExit(0 if actual == expected else f"license hash mismatch for {p}: {actual}")' \
  "$destination/FFTW-COPYING" 231f7edcc7352d7734a96eef0b8030f77982678c516876fcb81e25b32d68564c
printf '%s\n' \
  "QT_RUNTIME_VERSION=$qt_version" \
  "QT_LICENSE_SOURCE=https://github.com/qt/qtbase/tree/v${qt_version}/LICENSES" \
  "QT_LGPL3_SHA256=da7eabb7bafdf7d3ae5e9f223aa5bdc1eece45ac569dc21b3b037520b4464768" \
  "QT_GPL3_SHA256=8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903" \
  "ICU_73_LICENSE_SOURCE=https://github.com/unicode-org/icu/blob/release-73-2/icu4c/LICENSE" \
  "ICU_73_LICENSE_SHA256=f3005e195ff74d8812cc1f182a1c446fab678d70a10e3dada497585befee5416" \
  "ICU_74_LICENSE_SOURCE=https://github.com/unicode-org/icu/blob/release-74-2/LICENSE" \
  "ICU_74_LICENSE_SHA256=17510cf7a58b4879b887ec05a45d72cf1b73544dd9ec7e72f20110ed104229ee" \
  "OPENSSL_LICENSE_SOURCE=https://github.com/openssl/openssl/blob/openssl-3.6.4/LICENSE.txt" \
  "OPENSSL_LICENSE_SHA256=7d5450cb2d142651b8afa315b5f238efc805dad827d91ba367d8516bc9d49e7a" \
  "OPENSSL_WINDOWS_RUNTIME_EXPECTED=$([ "$platform" = windows-x64 ] && printf 3.6.4 || printf NOT_APPLICABLE)" \
  "FFTW_LICENSE_SOURCE=https://github.com/FFTW/fftw3/blob/fftw-3.3.10/COPYING" \
  "FFTW_LICENSE_SHA256=231f7edcc7352d7734a96eef0b8030f77982678c516876fcb81e25b32d68564c" \
  "FFTW_STATIC_WINDOWS_VERSION=$([ "$platform" = windows-x64 ] && printf 3.3.10 || printf NOT_APPLICABLE)" \
  >"$destination/LEGAL_PROVENANCE.txt"
