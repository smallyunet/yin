#!/usr/bin/env sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
jar=${YIN_JAR:-"$root/../../../target/yin-0.14.0.jar"}
trace_dir=${TRACE_DIR:-"$root/runtime/traces"}

mkdir -p "$root/runtime/notes" "$trace_dir"
cp "$root/fixtures/welcome.txt" "$root/runtime/notes/welcome.txt"

java -jar "$jar" --guard "$root/main.yin" \
  --input "$root/inputs/read.json" \
  --host "$root/host.json" \
  --trace "$trace_dir/read-$(date +%s).jsonl"

echo "Run the write fixture with explicit approval:"
echo "java -jar $jar --guard $root/main.yin --input $root/inputs/write.json --host $root/host.json --trace $trace_dir/write-\$(date +%s).jsonl --approve notes.write"
