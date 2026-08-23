#!/usr/bin/env bash
set -euo pipefail

version="${1:?usage: verify-rust-runtime.sh VERSION [YIN]}"
yin="${2:-target/release/yin}"
test "$("$yin" --version)" = "Yin $version"

for program in tests/*.yin examples/algorithms/*.yin examples/modules/main.yin; do
  "$yin" "$program" >/dev/null
done

test "$("$yin" examples/algorithms/quicksort.yin)" = "[1 2 3 4 5 6 7 8 9]"
test "$("$yin" examples/cli/parse-values.yin 10 bad 32)" = "42"

result="$(printf '%s' '{"host":"localhost","port":"8080"}' | "$yin" --json examples/config-validator/main.yin)"
node -e '
  const value = JSON.parse(process.argv[1]);
  if (value.tag !== "Valid" || value.config.mode !== "development") process.exit(1);
' "$result"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp examples/agents/action-gateway/server.mjs "$work/server.mjs"
cp examples/agents/action-gateway/host.json "$work/host.json"
cp examples/agents/action-gateway/inputs/create.json "$work/intent.json"
"$yin" --approval-request examples/agents/action-gateway/main.yin \
  --intent "$work/intent.json" --host "$work/host.json" \
  --out "$work/approval.json" --approved-by ci-human --expires-in-seconds 300
gateway_result="$("$yin" --gateway examples/agents/action-gateway/main.yin \
  --intent "$work/intent.json" --host "$work/host.json" --trace "$work/trace.jsonl" \
  --approval "$work/approval.json" --nonce-store "$work/nonces.jsonl")"
test "$("$yin" --replay "$work/trace.jsonl")" = "$gateway_result"
test -s "$work/runtime/tickets.jsonl"
