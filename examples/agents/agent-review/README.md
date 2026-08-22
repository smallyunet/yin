# Typed agent review demo

This demo exercises the ordered-policy and structured-contract workflow first
shipped in Yin 0.13:

```text
untrusted stdin JSON
  -> strict ReviewRequest decoding
  -> top-to-bottom typed policy
  -> exhaustive Decision variant
  -> deterministic stdout JSON
```

Build Yin, then run one input:

```bash
cargo build --release --workspace
./examples/agents/agent-review/run.sh examples/agents/agent-review/inputs/approve.json
```

The output is raw JSON suitable for `jq` or another process:

```json
{"tag":"Approve","requestId":"req-approve","reason":"within automatic policy"}
```

Run every maintained path:

```bash
for input in examples/agents/agent-review/inputs/*.json; do
  echo "==> $input"
  ./examples/agents/agent-review/run.sh "$input"
done
```

The fixtures demonstrate:

- ordinary automatic approval
- explicit risk-policy rejection
- a typed `NeedsInput` decision
- approval after optional transfer context is supplied
- strict missing-field diagnostics
- strict wrong-type diagnostics with `$.amount`
- strict unknown-field diagnostics with `$.debug`

The decision code uses `request.risk` field access and ordered `when` rules.
The first match wins and the explicit `otherwise` is the automatic-approval
fallback, so the complete decision order is visible without nested `if` forms.

`--json` reserves standard output for the final JSON response. Calls to `print`
are redirected to standard error. A program must finish with `String` or
`(Result String E)`; an `Err` payload is encoded as JSON and produces a non-zero
exit status.
