#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: verify-vsix.sh VERSION VSIX}"
vsix="${2:?usage: verify-vsix.sh VERSION VSIX}"

test -s "$vsix"
verify_dir="$(mktemp -d)"
trap 'rm -rf "$verify_dir"' EXIT
unzip -Z1 "$vsix" > "$verify_dir/entries"
grep -Fxq "extension/dist/extension.js" "$verify_dir/entries"
grep -Fxq "extension/images/icon.png" "$verify_dir/entries"
grep -Fxq "extension/syntaxes/yin.tmLanguage.json" "$verify_dir/entries"
unzip -p "$vsix" extension/package.json | node -e '
  let value = "";
  process.stdin.on("data", chunk => value += chunk).on("end", () => {
    const manifest = JSON.parse(value);
    if (manifest.version !== process.argv[1]) process.exit(1);
    if (manifest.publisher !== "smallyu") process.exit(1);
    if (manifest.icon !== "images/icon.png") process.exit(1);
    if (manifest.galleryBanner?.color !== "#0f172a") process.exit(1);
  });
' "$version"
