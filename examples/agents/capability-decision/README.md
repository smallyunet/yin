# Deterministic capability decision

This example is the executable Yin 0.15 contract-profile boundary. An Agent asks
to use `wallet.swap`; the policy returns an approval, rejection, or explicit
approval requirement. It never performs the swap and holds no wallet key.

Validate that the source uses only `deterministic-policy-v1`:

```bash
java -jar target/yin-0.17.0.jar --contract-check main.yin
```

Evaluate one request:

```bash
java -jar target/yin-0.17.0.jar --contract-run main.yin \
  --input inputs/approve.json
```

The result envelope binds the exact program, input, and structured decision with
SHA-256 digests. Repeating the same program and input on the same Yin release
produces the same envelope. The `Approve` result names a capability and a
request-specific maximum, but it is not itself a signed or replay-protected
capability token; issuing and enforcing such a token remains a host concern.
