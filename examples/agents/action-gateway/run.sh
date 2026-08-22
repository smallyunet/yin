#!/usr/bin/env sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
example="$root/examples/agents/action-gateway"
yin=${YIN_BIN:-"$root/target/release/yin"}
work=$(mktemp -d "${TMPDIR:-/tmp}/yin-gateway.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

mkdir -p "$example/runtime"

"$yin" --approval-request "$example/main.yin" \
  --intent "$example/inputs/create.json" \
  --host "$example/host.json" \
  --out "$work/approval.json" \
  --approved-by "local-demo-human" \
  --expires-in-seconds 300

"$yin" --gateway "$example/main.yin" \
  --intent "$example/inputs/create.json" \
  --host "$example/host.json" \
  --trace "$work/trace.jsonl" \
  --approval "$work/approval.json" \
  --nonce-store "$work/used-approvals.jsonl"

"$yin" --replay "$work/trace.jsonl"
echo "ticket fixture: $example/runtime/tickets.jsonl"
