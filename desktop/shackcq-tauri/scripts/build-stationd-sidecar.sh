#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
target=$(rustc -vV | sed -n 's/^host: //p')
cmake --build "$repo_dir/build/desktop/release" --target shackcq-stationd -j4
mkdir -p "$repo_dir/desktop/shackcq-tauri/binaries"
cp "$repo_dir/build/desktop/release/shackcq-stationd" \
  "$repo_dir/desktop/shackcq-tauri/binaries/shackcq-stationd-$target"
