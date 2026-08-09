# AI-first direction

Yin's AI-first goal is to make agent programs deterministic where possible and
explicit where uncertainty or external effects remain. It is not a prompt
template language and does not embed one provider's API into the grammar.

The intended execution model is:

```text
typed input
  -> pure deterministic computation
  -> typed model or tool request
  -> explicit Result
  -> policy or human approval for external writes
  -> durable checkpoint
  -> typed output plus an auditable trace
```

## Design principles

1. Expected failure is data. Tool, model, and task boundaries return `Result`
   values instead of hiding domain failure in exceptions or sentinel strings.
2. External authority is injected. Files, network access, secrets, tools, and
   model providers are host capabilities rather than ambient globals.
3. Structured data is the default. Future integrations use source types and
   schemas for inputs, outputs, and errors; free-form text remains available but
   is not treated as validated structure.
4. External writes are reviewable. Risk, idempotency, parameter previews, and
   approval belong to the execution contract.
5. Long work is resumable. Tasks have stable identities, cancellation,
   deadlines, checkpoints, and terminal results.
6. Runs are reproducible. Model and tool boundaries can be recorded, mocked,
   and replayed without repeating external effects.
7. Protocols are adapters. MCP, HTTP, local processes, and agent-to-agent
   transports map to stable Yin abstractions instead of becoming syntax.

## Version sequence

- **0.10 explicit outcomes:** typed `Result`, `Ok`, `Err`, and exhaustive
  narrowing. This is the shared failure contract for every later boundary.
- **0.11 structured data:** tagged data, JSON values, schema validation, and
  record encoding/decoding.
- **0.12 capabilities and tools:** declared effects, injected permissions,
  typed `Tool<Input, Output, Error>`, approval metadata, and an MCP adapter.
- **0.13 model boundaries:** typed generation, provider-neutral configuration,
  token/cost budgets, provenance, mocks, and record/replay.
- **0.14 durable agents:** tasks, structured concurrency, checkpointing,
  suspension, user input, approval tokens, cancellation, and recovery.

The static checker should eventually be able to list every capability and
potential external write required by an agent before that agent runs. Until
those semantics are specified and tested, AI services remain host integrations
rather than ad hoc language primitives.
