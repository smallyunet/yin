# Typed agent review demo

This demo exercises the complete Yin 0.11 structured-contract workflow:

```text
untrusted stdin JSON
  -> strict ReviewRequest decoding
  -> typed policy function
  -> exhaustive Decision variant
  -> deterministic stdout JSON
```

Build Yin, then run one input:

```bash
./mvnw package
./examples/agent-review/run.sh examples/agent-review/inputs/approve.json
```

The output is raw JSON suitable for `jq` or another process:

```json
{"tag":"Approve","requestId":"req-approve","reason":"within automatic policy"}
```

Run every maintained path:

```bash
for input in examples/agent-review/inputs/*.json; do
  echo "==> $input"
  ./examples/agent-review/run.sh "$input"
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

`--json` reserves standard output for the final JSON response. Calls to `print`
are redirected to standard error. A program must finish with `String` or
`(Result String E)`; an `Err` payload is encoded as JSON and produces a non-zero
exit status.
