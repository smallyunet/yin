# Deterministic policy profile v1

`deterministic-policy-v1` is the portable contract subset introduced in Yin
0.15. It is designed for pure decisions over normalized JSON, not for arbitrary
Yin programs or untrusted-code sandboxing.

## Allowed boundary

- one exact UTF-8 JSON input through `read-all`;
- strict `decode-json` and deterministic `encode-json`;
- `Int`, `Bool`, and `String` values;
- immutable records, variants, vectors, `Option`, and `Result`;
- typed functions, ordered policies, conditionals, and exhaustive matching;
- pure arithmetic, comparison, boolean, vector, and string operations.

## Rejected constructs

- `Float`, float literals, and `parse-float`;
- `Any`, because runtime-dependent values weaken the portable contract;
- `set!` mutation;
- `args`, `print`, and `read-text`;
- source tool declarations and `invoke`.

`read-all` is the only allowed host import. It returns the immutable input bytes
provided in the execution envelope. Clock, randomness, environment variables,
filesystem access, network access, and tool implementations are unavailable.

## Deliberate limitations

The Java reference evaluator has no instruction fuel, precise memory accounting,
or portable bytecode verifier. Direct and mutual recursion therefore remain
language features but are not safe for hostile contract deployment in 0.15.
The next VM milestone must introduce bounded execution before accepting
untrusted source.

Integer behavior remains the normative Yin `Int` behavior for this release. A
portable bytecode specification must replace implementation-dependent overflow
with an explicit checked or fixed-width rule before cross-runtime consensus is
claimed.
