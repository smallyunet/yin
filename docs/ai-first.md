# AI and automation applications

Yin is a typed deterministic language for portable programs and policies across
constrained execution environments. AI is an application domain, not a compiler
target and not the language identity. Agent policy, typed tools, guarded hosts,
deterministic decisions, and the MCP gateway are application profiles over the
same core used by hosted programs and planned consensus targets.

This distinction is deliberate. A local coding interface can already display
tool calls and ask a human for approval; Yin should not claim that reproducing
that UI is sufficient reason for a new language. Nor should Yin compete with a
full organizational policy engine merely by adding more approval syntax.

The AI profiles are useful when a host needs language-level properties that
survive outside one interface: typed input/output contracts, deterministic
decisions, explicit authority injection, portable evaluation, or replayable
execution evidence. Applications that do not need those properties should use
the ordinary language without the profiles.

The unifying model is:

```text
AI or human produces a typed intent
  -> Yin policy evaluates normalized data
  -> Allow | Reject | NeedsApproval
  -> an authority-bearing adapter performs an admitted effect
  -> the host or target records execution evidence
```

The policy may eventually be compiled for more than one target, but the adapter
is target-specific. MCP tool execution, an EVM call, a Solana CPI, and a Bitcoin
signature check are not interchangeable operations.

## Shared language principles

1. Programs are statically checked before execution.
2. Immutable values and stable iteration make results reproducible.
3. Expected absence is `Option`; expected domain failure is `Result`; malformed
   programs and violated language invariants are diagnostics.
4. Boundaries are strict and typed. JSON is the current hosted encoding, not a
   universal ABI for every target.
5. Modules expose a closed public surface and the complete dependency graph is
   checked.
6. Host authority is injected explicitly. Source declarations never grant an
   external capability by themselves.

These principles apply equally to a configuration validator, an Agent decision,
or portable policy logic behind a target-specific adapter.

## Implemented AI profiles

- `policy` is readable first-match syntax that lowers to ordinary typed
  functions and conditionals.
- typed `tool` declarations describe host contracts and authority metadata;
  `invoke` returns explicit outcomes.
- `--guard` is a narrow reference host for demonstrating installed local tools,
  authorization, traces, and replay.
- `deterministic-policy-v1` rejects ambient effects and binds source, input, and
  result digests. The Rust VM executes an even smaller portable subset.
- `--gateway` demonstrates a generic MCP stdio host with closed configuration
  and request-bound evidence.

These are maintained capabilities, not promises of authentication, secure
sandboxing, durable workflow execution, or production policy administration.
Those concerns remain host responsibilities.

MCP JSON-RPC, subprocess lifecycle, local approval files, nonce locking, and
JSONL traces remain hosted adapter concerns. They must not leak into the shared
compiler IR or become requirements for non-Agent programs.

## Relationship to target profiles

Target profiles answer where code runs; application profiles answer what the
code is responsible for. Valid combinations may eventually include:

| Application | Target | Intended boundary |
| --- | --- | --- |
| Agent policy | hosted or Wasm | local preflight and review |
| Agent policy | portable VM | bounded independent decision |
| Agent policy | EVM | on-chain wallet or protocol authorization |
| Agent policy | SVM | account-aware on-chain authorization |
| Action gateway | hosted | capability-gated MCP or local tool execution |
| Spend policy | Bitcoin | consensus spending condition |

The most valuable cross-target case is a pure typed policy evaluated off-chain
before an Agent acts and again on-chain before state changes. Equal behavior is
a conformance goal only for the shared admitted subset; platform adapters and
authority checks remain explicit.

## Product and roadmap boundary

Compiler-core correctness takes priority over adding protocol integrations.
Near-term architectural work is typed HIR, effects, target validation, MIR,
fixed-width numeric semantics, and semantic tooling. New Agent, approval, or
protocol-specific work should enter the core only when a real cross-host or
cross-target program demonstrates a semantic gap that cannot be handled more
clearly by a library, adapter, or established policy system.
