# Language and compiler architecture

Yin is a typed deterministic language for portable programs and policies across
constrained execution environments. The long-term architecture separates one
language core from target-specific execution models and application-specific
libraries. It does not assume that every Yin program can run on every target.

This document describes the direction of the project. Unless a feature is also
listed under **Implemented today**, it is an architectural commitment rather
than a claim about the current compiler.

## The three layers

### Language core

The core owns syntax, modules, name resolution, static types, immutable values,
functions, records, variants, `Option`, `Result`, pattern matching, diagnostics,
and deterministic evaluation rules. These semantics must not change depending
on whether a program is later interpreted on a host or compiled for a chain.

The compiler direction is:

```text
UTF-8 Yin source
  -> parser and module resolver
  -> typed high-level IR (HIR)
  -> effect and target validation
  -> target-independent mid-level IR (MIR)
  -> target lowering, linking, and artifacts
```

HIR gives every expression a resolved identity, type, effect set, and source
span. MIR makes control flow, calls, checked arithmetic, data layout, and
failure explicit without assuming a stack machine, a register machine, an
account model, or a particular host ABI.

### Target profiles

A target profile answers **where and under which machine constraints a program
runs**. A profile defines admitted types, control flow, effects, resource
limits, ABI rules, artifact formats, and validation requirements.

Planned profile families are:

- `hosted`: the current CLI, REPL, browser, and embedding environment;
- `portable-vm`: bounded deterministic execution using verified Yin artifacts;
- `evm`: Ethereum-compatible contracts and EVM bytecode;
- `svm`: Solana programs and sBPF artifacts;
- `riscv`: hosted or bare-metal RISC-V programs with an explicit runtime ABI;
- `bitcoin`: statically bounded Bitcoin Script or Tapscript spending programs.

Profiles are deliberately unequal. EVM storage, Solana accounts, RISC-V
syscalls, and the Bitcoin stack are different authority and state models. A
backend must preserve Yin semantics where admitted and reject a program when it
cannot do so. It must never silently approximate an unsupported operation.

### Application profiles and libraries

An application profile answers **what responsibility a program has**. Agent
policy, action gateways, smart contracts, and spending conditions are
applications of the language rather than separate language identities.

Examples include:

- `pure-policy`: typed input to a deterministic decision with no ambient effects;
- `agent-policy`: decisions over normalized Agent intents;
- `action-gateway`: hosted capability checks and external tool execution;
- `smart-contract`: target-specific state and message entry points;
- `spend-policy`: a statically bounded Bitcoin spending condition.

Application profiles may be usable on more than one target. A pure policy, for
example, may eventually run in the hosted VM, in a browser, or behind an EVM or
SVM adapter. Target-specific effects remain explicit and cannot be made portable
by renaming them.

## Portable does not mean universal

The useful portability boundary is a shared typed kernel plus thin adapters:

```text
MCP request -----> hosted adapter ---+
EVM calldata ----> ABI adapter -------|
SVM accounts ----> account adapter ---+--> typed Yin policy --> Decision
Bitcoin stack ---> script adapter ----+
```

Pure functions over fixed-width integers, booleans, records, variants, and
bounded collections are strong candidates for cross-target compilation. Host
I/O, dynamic allocation, recursive traversal, contract storage, account
borrowing, and signature opcodes are target capabilities and may narrow the set
of accepted targets.

Cross-target compilation is therefore a checked property of a particular
program, not a blanket property of all Yin source.

## Effects and capabilities

The future HIR must distinguish pure computation from operations such as:

- host input and output;
- allocation and unbounded control flow;
- persistent state reads and writes;
- external contract or program calls;
- account reads, writes, signer checks, and ownership checks;
- hashing and signature verification;
- authorization-bearing tool execution.

Target validation consumes these inferred effects. An unsupported effect is a
compile-time error with a call chain and a target-specific explanation. Source
declarations describe required authority; they never grant authority by
themselves.

## Numeric and failure semantics

Portable code cannot rely on the current hosted `Int` being reinterpreted by a
backend. The language needs explicit fixed-width signed and unsigned integer
types, checked overflow rules, and named wrapping or saturating operations.
`Float`, arbitrary precision `Int`, and dynamic `Any` may remain hosted features
without being admitted by consensus targets.

Expected absence remains `Option`; expected domain failure remains `Result`.
Each target profile must specify how an uncaught language diagnostic or failed
checked operation becomes a process failure, VM trap, contract revert, or script
rejection.

## Correctness model

The reference evaluator and experimental MIR evaluator are semantic oracles,
not proof that a backend is correct. Each backend requires:

- positive and rejection conformance suites;
- differential execution against the reference semantics;
- artifact verification and deterministic build checks;
- target-native integration tests;
- resource-limit and adversarial boundary tests;
- explicit versioning of source, profile, ABI, and artifact semantics.

Consensus-target support must remain experimental until the compiler, runtime,
and generated artifacts have received appropriate independent review.

## Implemented today

Yin 0.22.0 has the Rust parser, checker, tree-walking evaluator, formatter, LSP,
Wasm browser runtime, hosted tool boundary, Agent Action Gateway, canonical
`.ybc` artifacts, and an independent fuel-metered decision VM. The current
`.ybc` format is a normalized token stream for a narrow decision profile; it is
not the future target-independent MIR.

Current source also contains the first two experimental typed HIR slices. Phase
1 covers literals, vectors, definitions, functions, calls, conditionals,
sequences, records, and field access. Phase 2 adds variants, typed constructors,
exhaustive matches, scoped pattern bindings, `Option`, `Result`, policy lowering,
and typed JSON boundaries. They reuse normative checker types, resolve stable
symbol identities, preserve spans, and have deterministic snapshots through
`yin --emit-hir`. The hosted evaluator does not consume HIR yet.

An initial target-independent MIR slice lowers admitted pure HIR into functions,
explicit basic blocks, typed block parameters, calls, branches, jumps,
constructors, field reads, and exhaustive match terminators. `yin --emit-mir`
renders it deterministically, while `yin --run-mir` executes data-returning
programs through a differential-tested evaluator with closures and recursion.
This experimental evaluator does not replace hosted execution, and JSON, host
I/O, modules, tools, mutation, defaults, and higher-order collection primitives
remain outside its fail-closed admission boundary.

Complete HIR, MIR, and effect-inference coverage, fixed-width portable integers,
and EVM, SVM, RISC-V, and Bitcoin backends remain planned work. The
[roadmap](roadmap.md) defines the order and acceptance boundaries.

The initial effect pass classifies allocation, host I/O, mutation, persistent
state, external calls, account access, hashing, signatures, authorization,
module loading, and unresolved dynamic calls. It propagates effects through
named functions, policies, and named callbacks while retaining source-spanned
origins. `yin check --target` applies versioned, fail-closed profile rules.
`hosted-v1`, `portable-bytecode-v1`, and `mir-pure-v1` have validators; designed
consensus/native profiles remain registered but cannot pass validation.
