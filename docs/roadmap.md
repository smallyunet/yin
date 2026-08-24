# Roadmap

Yin is developed as a typed deterministic language for portable programs and
policies across constrained execution environments. The roadmap now separates
the shared language and compiler core, target profiles, and application
profiles. Agent policy is the first substantial application profile; it is not
the organizing identity of the language. EVM, SVM, RISC-V, and Bitcoin are
planned targets and must not be presented as implemented before their profile
contracts, artifacts, and conformance suites exist.

Released milestones below are historical. The forward milestones describe
architecture and acceptance order rather than promised release numbers or dates.

## 0.1 — reproducible baseline

- Java 17 build and Maven Wrapper
- JUnit integration suite for maintained examples
- GitHub Actions verification
- consistent Float and primitive type checking
- safe function and record argument validation
- testable diagnostics without JVM termination inside the language core
- scoped type-checker state and shared built-in registration

## 0.1.1 — correctness hardening

- semantic regression coverage for scope, assignment, closures, arguments,
  records, destructuring, unions, primitives, and parser diagnostics
- runtime errors for unbound names
- type-preserving assignment checks
- duplicate keyword and descriptor rejection
- consistent record inheritance conflicts in interpreter and type checker
- documented definition-time semantics for function and record defaults

## 0.2 — semantic cleanup

- runtime `Value` and static `YinType` hierarchies are separate
- runtime and type-checking environments are statically parameterized
- record constructors, record values, record types, and record value types have
  distinct representations
- primitive runtime implementations and static signatures are separate
- diagnostics expose stable codes and source spans
- unreachable attribute and subscript AST implementations are removed

## 0.3 — language definition

- normative grammar and deterministic evaluation order
- immutable records and nominal, transitive inheritance subtyping
- structural vector equivalence, union normalization, subtyping, and explicit
  inference boundaries
- complete classification of historical programs as migrated or archived
- executable specification and corpus-classification regression tests

## 0.4 — interactive tooling

- persistent, multiline REPL with diagnostic recovery
- in-memory parsing and a stateful embedding session
- deterministic, comment-preserving formatter with print, check, and write modes

## 0.5 — browser playground

- TeaVM JavaScript build of the language core
- stateful browser bridge and captured standard output
- responsive playground with examples, diagnostics, and online formatting
- Web Worker isolation and execution timeout
- automated GitHub Pages deployment

## 0.6 — record usability

- immutable `(field value :name)` field access
- precise local and inherited field types
- safe union distribution and an explicit `Any` rule
- structured syntax, missing-field, and non-record diagnostics
- maintained CLI, REPL, browser, formatter, and specification coverage

## 0.7 — vector usability

- immutable vector length, indexed access, and concatenation primitives
- exact element types for literal indices and normalized unions for dynamic
  indices
- safe distribution across vector unions and explicit runtime checks for `Any`
- structured operand, index, empty-vector, and bounds diagnostics
- maintained CLI, REPL, browser, formatter, and specification coverage

## 0.8 — editor services

- Language Server Protocol transport over standard input/output
- live syntax and type diagnostics for unsaved documents
- canonical whole-document formatting
- self-contained Visual Studio Code extension with a bundled Yin server
- protocol-level regression tests for initialization, document sync, and edits
- CI-verified VSIX packages published alongside executable release artifacts

## 0.9 — programmable core

- source-expressible homogeneous vector and positional function types
- exhaustive pattern matching over literals, built-in types, vectors, records,
  and union members
- immutable `map`, `filter`, `fold`, range, slice, reverse, and membership
  operations
- string comparison, transformation, splitting, joining, and numeric parsing
- injected program arguments, complete text input, and UTF-8 file reads
- maintained word-count, quicksort, and argument-parsing programs

## 0.10 — explicit outcomes

- source-expressible `(Result T E)` outcome types
- precise `(Ok T)` and `(Err E)` variant types from immutable `ok` and `err`
  constructors
- covariant widening into declared Result boundaries
- exhaustive `Ok` and `Err` pattern matching with typed payload narrowing
- structural Result equality and explicit behavior across `Any`
- maintained CLI, REPL, browser, formatter, specification, and editor coverage

## 0.11 — structured contracts

- closed user-defined tagged variants with named-field constructors
- first-class covariant `(Option T)`, `some`, `none`, `Some`, and `None`
- strict type-directed JSON decoding with structured error codes and paths
- deterministic JSON encoding for records, vectors, options, results, and variants
- deterministic JSON Schema Draft 2020-12 generation with closed object shapes
- maintained structured-agent standard-input/output example
- CLI, REPL, browser, formatter, LSP, editor, specification, and regression coverage

## 0.12 — capabilities and tools

- source-declared typed tool contracts with input, output, and business-error types
- explicit capability, read/write/destructive effect, approval, idempotency, and open-world metadata
- host-injected implementations with deny-by-default approval enforcement
- `Result` outcomes that distinguish business failures from built-in `ToolError` boundary failures
- deterministic preflight capability manifests and structured terminal audit events
- transport-neutral MCP `CallToolResult` adapter with strict output-contract validation
- maintained browser-hosted typed-tool example and JVM integration coverage

## 0.13 — readable policies

- ordered `policy` definitions with first-match `when` rules and a mandatory
  final `otherwise`
- concise dot-style immutable record access with chained field support
- parser lowering to the existing typed function and conditional core
- flattened agent-review and Web3 transaction-guard policies with unchanged
  fixtures and JSON boundary behavior
- formatter, LSP, editor, browser, specification, and diagnostic coverage

## 0.14 — reference policy runtime

- `--guard` execution with preflight type checking and a closed host manifest
- explicit source/host name, capability, effect, and approval agreement
- root-confined local text reads and approval-required writes
- create-only JSONL decision traces with source, input, host, authorization,
  tool-result, final-outcome digests, and a SHA-256 hash chain
- `--replay` verification and final-result reproduction without tool execution
- maintained end-to-end demo for automatic reads, denied writes, approved writes,
  policy rejection, trace integrity, and side-effect-free replay

## 0.15 — deterministic contract profile

- executable `deterministic-policy-v1` preflight for pure Agent decisions
- `--contract-check` validation and `--contract-run` execution over exact JSON input
- explicit rejection of `Float`, `Any`, mutation, filesystem access, output, and tools
- result envelopes binding program, input, and structured decision with SHA-256 digests
- maintained capability-decision fixtures for approval, rejection, and human review
- architecture and conformance boundaries for a future bytecode compiler and Rust/Wasm VM

## 0.16 — portable bytecode and Rust VM

- canonical `.ybc` token bytecode with explicit format and contract versions
- Java compiler admission, normalization, type checking, and artifact hashing
- independent Rust verifier and decision evaluator with no JVM dependency
- fuel accounting across input, declarations, evaluation, and encoded output
- deliberate rejection of functions, ranges, and policy calls for bounded v1 execution
- cross-runtime capability-decision conformance for approve, reject, and review

## 0.17 — Agent Action Gateway

- generic `ActionIntent` envelope over source-typed tool arguments
- newline-delimited MCP stdio client with initialization, version negotiation,
  paginated discovery, bounded requests, cancellation, and graceful shutdown
- closed source/host/intent agreement for server, tool, capability, effect, and
  approval requirements without trusting remote annotations
- approval evidence bound to program, host, canonical intent and arguments,
  actor, agent, resource, expiry, and nonce
- locked durable nonce consumption before non-read execution
- hash-chained authorization, tool-result, and final-outcome traces compatible
  with side-effect-free `--replay`
- maintained external ticket action demonstrating the complete subprocess flow

## 0.18 — modules and multi-file type checking

- explicit `module` declarations with closed export lists
- selective relative `import` forms with isolated lexical module scopes
- recursive dependency-graph evaluation and type checking with one initialization
- canonical path identity, transitive relative resolution, and circular-import diagnostics
- private-binding, undefined-export, duplicate-module, and local-conflict enforcement
- source-qualified nominal Record and Variant identity across module boundaries
- file-aware LSP analysis and maintained multi-file example
- explicit rejection from digest-bound security profiles until dependency hashes are bound

## 0.19 — immutable dictionaries, sets, and safe access

- covariant `(Dict K V)` and `(Set T)` types with an inferred `Never` bottom
  type for empty collections
- immutable insertion-ordered constructors and transformations
- total `dict/get` lookup through `(Option V)` rather than a missing-key error
- dictionary traversal, membership, size, update, and removal operations
- set membership, traversal, size, update, removal, union, intersection, and difference
- structural equality that ignores collection insertion order while preserving
  deterministic traversal and rendering order
- strict JSON object interoperability for String-key dictionaries and array
  interoperability for sets, including deterministic schema generation
- maintained multi-file JSON configuration validator as a non-Agent end-to-end program
- JVM, TeaVM browser, formatter, LSP, specification, and regression coverage

## 0.20 — complete Rust implementation

- freeze the 0.19 protocol and Java implementation on a permanent archive branch
- replace the JVM interpreter, checker, formatter, LSP, gateway, and compiler with Rust
- replace TeaVM with a shared Rust/Wasm browser runtime
- preserve canonical `.ybc` compatibility and the independent fuel-metered VM
- ship native Linux, macOS, and Windows executables and a universal VSIX client

## 0.21 — native semantic parity and release hardening

- migrate the frozen Java semantic corpus into native Rust integration suites
- verify shared positive and rejection behavior differentially against v0.19
- restore precise language behavior discovered by the migrated suites
- publish aligned native binaries, Wasm, and editor artifacts
- make release verification parameterized and reproducible

## Forward milestone A — compiler-ready core

Initial source status: the experimental `--emit-hir` path now reuses normative
checker types, assigns stable binding and parameter symbols, preserves source
spans, and lowers a pure Phase 1 slice including records and field access. It is
not yet consumed by the hosted evaluator and does not complete this milestone.

- introduce resolved, typed HIR instead of making backends inspect surface
  `Expr` strings directly
- separate module/name resolution from type checking and evaluation
- attach source spans, resolved identities, types, and inferred effects to HIR
- define explicit fixed-width signed and unsigned integer types and overflow
  behavior while retaining hosted arbitrary-precision `Int`
- lower functions, records, variants, matches, and explicit failures into a
  target-independent control-flow MIR
- implement a MIR evaluator and differential tests against the current
  tree-walking reference evaluator
- preserve formatter, LSP, browser, hosted runtime, and current source behavior
  during the compiler refactor

Exit criterion: maintained hosted programs execute identically through the
reference evaluator and MIR evaluator, and no target-specific concept appears
in the shared MIR.

## Forward milestone B — effects and target validation

- define a versioned target-profile contract covering types, control flow,
  effects, resource limits, ABI, failures, artifacts, and runtime versions
- infer pure, allocation, host I/O, persistent state, external call, account,
  hashing, signature, and authorization effects through the call graph
- add `yin check --target <profile>` with fail-closed diagnostics and effect
  provenance
- bind complete module graphs and profile versions into generated artifacts
- distinguish designed, prototype, experimental, and supported target status
  in all documentation and release metadata

Exit criterion: hosted and current portable-VM behavior are expressed as
profiles, and unsupported programs fail before code generation.

## Forward milestone C — EVM profile

- specify `evm-contract-v1` integer, ABI, storage, memory, context, revert,
  event, external-call, gas, and rollback semantics
- lower an intentionally small Yin subset through Yul to creation/runtime EVM
  bytecode, ABI JSON, metadata, and source mappings
- validate generated contracts in REVM and compare pure functions with the MIR
  semantic oracle
- add storage-layout, ABI, revert, gas, and adversarial boundary suites
- consider native EVM assembly only after the profile and Yul path stabilize

Exit criterion: versioned example contracts can be built, deployed, called,
and differentially verified without claiming Solidity-wide compatibility.

## Forward milestone D — RISC-V profile

- select and document an explicit `riscv64-v1` ABI and hosted or bare-metal
  runtime boundary
- lower MIR through LLVM IR or another independently justified backend path
- define memory allocation, traps, linking, and runtime imports
- run generated ELF artifacts in a named emulator and hardware-independent
  conformance environment

Exit criterion: shared pure programs execute consistently on RISC-V, proving
that MIR and the compiler core do not encode EVM-specific assumptions.

## Forward milestone E — SVM profile

- specify `svm-program-v1` instruction data, accounts, signer/writable/owner
  constraints, PDA, CPI, compute-unit, allocation, and failure semantics
- provide target libraries and effects rather than aliasing SVM concepts to EVM
  storage or hosted tools
- lower admitted programs to sBPF artifacts with Solana-compatible entry points
- verify account changes, CPI propagation, failures, and compute limits in a
  maintained SVM harness

Exit criterion: a bounded program processes real account fixtures with results
that agree with the documented profile and semantic oracle where applicable.

## Forward milestone F — Bitcoin profile

- specify a particular Script/Tapscript version rather than a generic "Bitcoin"
  target
- statically prove termination, maximum stack depth, code size, opcode
  availability, and numeric encoding for every admitted program
- reject loops, recursion, dynamic allocation, persistent mutable state, and
  unavailable introspection before emission
- emit spending scripts plus human-readable policy and resource reports
- validate against Bitcoin Core-compatible vectors and consensus fixtures

Exit criterion: generated scripts are consensus-valid for the named profile and
their accepted and rejected spending cases match the source policy.

## Continuing language and tooling work

- module namespaces, aliases, project manifests, source roots, and graph diagnostics
- coherent failure semantics across parsing, indexing, arithmetic, and host I/O
- user-defined generics, recursive aliases, and inference hardening
- completion, hover, definition, references, project formatting, tests, and
  generated API documentation
- precise fuel, stack, code-size, and memory accounting per target

Authenticated approval services, durable Agent workflows, additional
MCP-specific syntax, web frameworks, async/concurrency, and a package registry
remain application-driven work. They must not block compiler-core correctness or
be promoted into the language solely for one integration.

New syntax or target intrinsics require written semantics, positive and
rejection tests, diagnostics, formatter/editor coverage, and a maintained
program demonstrating why a library or adapter is insufficient.
