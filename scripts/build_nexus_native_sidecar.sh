#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
target=${CARGO_BUILD_TARGET:-$(rustc -vV | sed -n 's/^host: //p')}
cargo build --locked --release --features live-audio --manifest-path "$repo/desktop/nexus-runtime/Cargo.toml" --target "$target"
suffix=
case "$target" in *windows*) suffix=.exe;; esac
mkdir -p "$repo/desktop/shackcq-tauri/binaries"
cp "$repo/desktop/nexus-runtime/target/$target/release/shackcq-nexus-runtime$suffix" "$repo/desktop/shackcq-tauri/binaries/shackcq-nexus-runtime-$target$suffix"
echo "prepared ShackCQ Nexus runtime sidecar for $target"
