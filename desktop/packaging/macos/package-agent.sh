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
lipo "$app_bundle/Contents/MacOS/ShackCQAgent" -verify_arch arm64
for executable in "$app_bundle/Contents/MacOS/ShackCQAgent" \
                  "$app_bundle/Contents/MacOS/shackcq-stationd" \
                  "$app_bundle/Contents/MacOS/shackcq-hamlib-helper"; do
  if otool -L "$executable" | grep -Eq '^[[:space:]]+/(opt|usr/local)/'; then
    echo "unbundled local dependency in $executable" >&2
    exit 65
  fi
done

mkdir -p "$output_directory"
base_name="ShackCQAgent-macOS-arm64-$version"
zip_path="$output_directory/$base_name.zip"
dmg_path="$output_directory/$base_name.dmg"
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-agent-package.XXXXXX")
notary_zip="$temporary_root/notary.zip"
dmg_stage="$temporary_root/dmg"
mkdir -p "$dmg_stage"
trap 'rm -rf "$temporary_root"' EXIT HUP INT TERM

if [ -n "$signing_identity" ]; then
  # Sign nested code from the inside out. Using --deep for signing can leave
  # third-party framework layouts (notably Qt) with invalid or ambiguous seals.
  for code_root in "$app_bundle/Contents/Frameworks" "$app_bundle/Contents/PlugIns"; do
    if [ -d "$code_root" ]; then
      find "$code_root" -type f -print | while IFS= read -r candidate; do
        if file "$candidate" | grep -q 'Mach-O'; then
          codesign --force --options runtime --timestamp --sign "$signing_identity" "$candidate"
        fi
      done
    fi
  done
  if [ -d "$app_bundle/Contents/Frameworks" ]; then
    find "$app_bundle/Contents/Frameworks" -type d -name '*.framework' -print | while IFS= read -r framework; do
      codesign --force --options runtime --timestamp --sign "$signing_identity" "$framework"
    done
  fi
  codesign --force --options runtime --timestamp --sign "$signing_identity" \
    "$app_bundle/Contents/MacOS/shackcq-hamlib-helper"
  codesign --force --options runtime --timestamp --sign "$signing_identity" \
    "$app_bundle/Contents/MacOS/shackcq-stationd"
  codesign --force --options runtime --timestamp --sign "$signing_identity" \
    "$app_bundle/Contents/MacOS/ShackCQAgent"
  codesign --force --options runtime --timestamp --sign "$signing_identity" "$app_bundle"
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
