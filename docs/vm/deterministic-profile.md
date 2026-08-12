# Deterministic policy profile v1

`deterministic-policy-v1` is the portable contract subset introduced in Yin
0.15. Yin 0.16 lowers a stricter portion of it to `portable-bytecode-v1` for
pure decisions over normalized JSON, not arbitrary Yin programs.

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

## Portable bytecode restrictions

The Rust VM rejects explicit `fun`, `range`, and all policy-to-policy calls.
That excludes direct and mutual recursion and unbounded source-level iteration.
The admitted evaluator is finite, and runtime work is charged against fuel.
These restrictions apply only to compiled contracts; the general Yin language
and Java reference evaluator retain their existing function and recursion rules.

Portable integer operands are signed 64-bit values in the Rust VM. The current
compiler only emits Java `Int` values, so its accepted range is narrower. Exact
cross-runtime overflow semantics and a byte-accurate memory limit remain future
work; 0.16 does not claim consensus compatibility or hostile-process isolation.
