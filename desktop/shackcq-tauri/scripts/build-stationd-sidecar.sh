#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
target=${SHACKCQ_TAURI_TARGET:-$(rustc -vV | sed -n 's/^host: //p')}
build_dir=${SHACKCQ_DESKTOP_BUILD_DIR:-"$repo_dir/build/desktop/release"}
suffix=
case "$target" in *-windows-*) suffix=.exe ;; esac
cmake --build "$build_dir" --target shackcq-stationd shackcq-hamlib-helper -j4
mkdir -p "$repo_dir/desktop/shackcq-tauri/binaries"
cp "$build_dir/shackcq-stationd$suffix" \
  "$repo_dir/desktop/shackcq-tauri/binaries/shackcq-stationd-$target$suffix"
cp "$build_dir/shackcq-hamlib-helper$suffix" \
  "$repo_dir/desktop/shackcq-tauri/binaries/shackcq-hamlib-helper-$target$suffix"
