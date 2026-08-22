# Automation profiles, not the language identity

Yin is a small typed general programming language. It is intended for reliable
CLI tools, data/configuration programs, and embeddable automation. The Agent
policy, typed-tool, guarded-host, deterministic-contract, and MCP gateway
features are optional execution profiles over that language core.

This distinction is deliberate. A local coding interface can already display
tool calls and ask a human for approval; Yin should not claim that reproducing
that UI is sufficient reason for a new language. Nor should Yin compete with a
full organizational policy engine merely by adding more approval syntax.

The profiles are useful only when a host needs language-level properties that
survive outside one interface: typed input/output contracts, deterministic
decisions, explicit authority injection, portable evaluation, or replayable
execution evidence. Applications that do not need those properties should use
the ordinary language without the profiles.

## Shared language principles

1. Programs are statically checked before execution.
2. Immutable values and stable iteration make results reproducible.
3. Expected absence is `Option`; expected domain failure is `Result`; malformed
   programs and violated language invariants are diagnostics.
4. JSON is a strict typed boundary rather than an unvalidated dynamic object.
5. Modules expose a closed public surface and the complete dependency graph is
   checked.
6. Host authority is injected explicitly. Source declarations never grant an
   external capability by themselves.

These principles apply equally to a configuration validator, a log-processing
CLI, or an Agent decision program.

## Optional profiles

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

## Product and roadmap boundary

Language completeness takes priority over profile expansion. Near-term work is
module namespaces and projects, coherent failure semantics, user-defined
generics, fuller bytecode parity, and semantic editor tooling. New Agent,
approval, or protocol-specific work should enter the roadmap only when a real
program demonstrates a language-level gap that cannot be handled more clearly
by the host or an established policy system.
