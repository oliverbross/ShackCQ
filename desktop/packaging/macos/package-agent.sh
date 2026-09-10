#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 5 ]; then
  echo "usage: $0 APP_BUNDLE OUTPUT_DIRECTORY VERSION [SIGNING_IDENTITY] [NOTARY_PROFILE]" >&2
  exit 64
fi

app_bundle=$1
output_directory=$2
version=$3
signing_identity=${4:-}
notary_profile=${5:-}

test -d "$app_bundle/Contents/MacOS"
test -x "$app_bundle/Contents/MacOS/ShackCQAgent"
test -x "$app_bundle/Contents/MacOS/shackcq-stationd"
test -x "$app_bundle/Contents/MacOS/shackcq-hamlib-helper"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_bundle/Contents/Info.plist")" = "app.shackcq.agent"

mkdir -p "$output_directory"
base_name="ShackCQAgent-macOS-arm64-$version"
zip_path="$output_directory/$base_name.zip"
dmg_path="$output_directory/$base_name.dmg"
notary_zip="$output_directory/$base_name-notary.zip"

if [ -n "$signing_identity" ]; then
  codesign --force --deep --options runtime --timestamp --sign "$signing_identity" "$app_bundle"
  codesign --verify --deep --strict --verbose=2 "$app_bundle"
fi

if [ -n "$notary_profile" ]; then
  test -n "$signing_identity"
  ditto -c -k --sequesterRsrc --keepParent "$app_bundle" "$notary_zip"
  xcrun notarytool submit "$notary_zip" --keychain-profile "$notary_profile" --wait
  xcrun stapler staple "$app_bundle"
  xcrun stapler validate "$app_bundle"
fi

ditto -c -k --sequesterRsrc --keepParent "$app_bundle" "$zip_path"
test "$(zipinfo -1 "$zip_path" | sed -n '1p')" = "ShackCQAgent.app/"
test -n "$(zipinfo -1 "$zip_path" | grep '^ShackCQAgent.app/Contents/MacOS/ShackCQAgent$')"

dmg_stage=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-agent-dmg.XXXXXX")
ditto "$app_bundle" "$dmg_stage/ShackCQAgent.app"
ln -s /Applications "$dmg_stage/Applications"
hdiutil create -quiet -volname "ShackCQ Agent" -srcfolder "$dmg_stage" -ov -format UDZO "$dmg_path"

if [ -n "$signing_identity" ]; then
  codesign --force --timestamp --sign "$signing_identity" "$dmg_path"
  codesign --verify --verbose=2 "$dmg_path"
fi
if [ -n "$notary_profile" ]; then
  xcrun notarytool submit "$dmg_path" --keychain-profile "$notary_profile" --wait
  xcrun stapler staple "$dmg_path"
  xcrun stapler validate "$dmg_path"
fi

shasum -a 256 "$zip_path" "$dmg_path" > "$output_directory/SHA256SUMS.txt"
echo "$zip_path"
echo "$dmg_path"
