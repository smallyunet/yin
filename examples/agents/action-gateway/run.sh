#!/usr/bin/env sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
example="$root/examples/agents/action-gateway"
jar=${YIN_JAR:-"$root/target/yin-0.17.0.jar"}
work=$(mktemp -d "${TMPDIR:-/tmp}/yin-gateway.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

mkdir -p "$example/runtime"

java -jar "$jar" --approval-request "$example/main.yin" \
  --intent "$example/inputs/create.json" \
  --host "$example/host.json" \
  --out "$work/approval.json" \
  --approved-by "local-demo-human" \
  --expires-in-seconds 300

java -jar "$jar" --gateway "$example/main.yin" \
  --intent "$example/inputs/create.json" \
  --host "$example/host.json" \
  --trace "$work/trace.jsonl" \
  --approval "$work/approval.json" \
  --nonce-store "$work/used-approvals.jsonl"

java -jar "$jar" --replay "$work/trace.jsonl"
echo "ticket fixture: $example/runtime/tickets.jsonl"
