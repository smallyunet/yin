#!/usr/bin/env sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
yin=${YIN_BIN:-"$root/../../../target/release/yin"}
trace_dir=${TRACE_DIR:-"$root/runtime/traces"}

mkdir -p "$root/runtime/notes" "$trace_dir"
cp "$root/fixtures/welcome.txt" "$root/runtime/notes/welcome.txt"

"$yin" --guard "$root/main.yin" \
  --input "$root/inputs/read.json" \
  --host "$root/host.json" \
  --trace "$trace_dir/read-$(date +%s).jsonl"

echo "Run the write fixture with explicit approval:"
echo "$yin --guard $root/main.yin --input $root/inputs/write.json --host $root/host.json --trace $trace_dir/write-\$(date +%s).jsonl --approve notes.write"
