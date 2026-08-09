# Web3 transaction guard demo

This demo places a deterministic Yin policy boundary between an AI agent and a
wallet or transaction executor:

```text
normalized transaction intent JSON
  -> strict TransactionIntent decoding
  -> deterministic safety policy
  -> exhaustive TransactionDecision
  -> raw JSON for the wallet host
```

Build and run it locally:

```bash
./mvnw package
./examples/web3/transaction-guard/run.sh \
  examples/web3/transaction-guard/inputs/approve.json | jq
```

The approved fixture produces:

```json
{
  "tag": "Approve",
  "requestId": "tx-approve",
  "reason": "simulation and policy checks passed"
}
```

The maintained fixtures cover automatic approval, unlimited approval, a high
USD value, failed simulation, an unverified contract, an unsupported chain, a
contract upgrade, an invalid address, and a typed JSON boundary error.
The invalid-address coverage includes both malformed short and empty values.

## Trust boundary

Yin does not query a chain, decode ABI, simulate, sign, or broadcast. The host
must normalize those results and remains responsible for execution. This demo
shows where Yin already adds value: an AI model can propose an intent, but it
cannot bypass deterministic decoding and review policy.

`rawAmount` is a decimal `String`, so the full `uint256` value remains exact.
`valueUsd` is a host-provided `Float` used only for policy thresholds, never for
settlement. Address checking currently verifies the `0x` prefix and 42-character
shape; cryptographic checksum and byte validation belong in a future Web3 type
layer.
