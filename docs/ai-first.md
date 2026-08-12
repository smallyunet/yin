# Agent policy direction

Yin's primary goal is to make the boundary between AI agents and external tools
typed, deterministic, deny-by-default, and auditable. Yin is not a prompt
template language, a general Agent framework, or syntax for one model provider.

The intended execution model is:

```text
untrusted typed intent
  -> ordered deterministic policy
  -> approve, reject, or request human approval
  -> deny-by-default host authorization
  -> typed tool Result
  -> auditable trace
```

Agent orchestration remains in the host application. Yin owns the smaller part
where a probabilistic proposal crosses into an external effect.

## Design principles

1. Policy must be readable in decision order. A policy is a sequence of `when`
   rules followed by an explicit `otherwise`; the first match wins.
2. Expected failure is data. Policy, tool, model, and task boundaries return
   typed outcomes instead of hiding domain failure in exceptions or strings.
3. External authority is injected. Files, network access, secrets, tools, and
   model providers are host capabilities rather than ambient globals.
4. Structured data is the default. Source types produce strict JSON decoders,
   encoders, and schemas at the boundary.
5. External writes are reviewable. Risk, idempotency, parameter previews, and
   approval belong to the execution contract.
6. Runs should become reproducible. Boundary calls can be recorded, mocked, and
   replayed without repeating external effects.
7. Protocols are adapters. MCP, HTTP, local processes, and agent-to-agent
   transports map to stable Yin abstractions instead of becoming syntax.

## Version sequence

- **0.10 explicit outcomes:** typed `Result`, `Ok`, `Err`, and exhaustive
  narrowing.
- **0.11 structured contracts:** tagged variants, `Option`, strict typed JSON,
  deterministic encoding, and JSON Schema generation.
- **0.12 capabilities and tools:** declared effects, deny-by-default injected
  permissions, approval metadata, capability manifests, and audit events.
- **0.13 readable policies:** ordered `policy` rules, mandatory fallback,
  first-match evaluation, and dot-style immutable field access.
- **0.14 policy runtime:** a deny-by-default reference host for root-confined
  local text tools, explicit write approval, hash-chained traces, and replay
  that never repeats a tool invocation.
- **0.15 deterministic contracts:** a pure executable policy profile,
  digest-bound decision envelopes, and explicit exclusions ahead of bytecode,
  metering, and a portable VM.
- **Next: adoption evidence:** evaluate the complete boundary with non-authors
  and compare the same task against an ordinary host implementation.
- **Later: durable boundaries:** MCP connectivity, approval suspension, cancellation, recovery,
  and recorded model/tool results when real policy-runtime use requires them.

The static checker lists every declared tool capability and potential external
write before execution. Declarations never grant authority: installed tool
calls still pass through host authorization, and the default policy denies all
calls.
