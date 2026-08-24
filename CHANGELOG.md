# Changelog

## Unreleased

- define the long-term Yin architecture as one typed deterministic language
  core with versioned target and application profiles
- document the planned typed HIR, effect validation, target-independent MIR,
  and staged EVM, RISC-V, SVM, and Bitcoin backend boundaries
- retain Agent policy and the Action Gateway as AI application profiles while
  separating hosted MCP concerns from compiler targets
- update repository, Playground, package, editor, example, conformance, and
  contributor descriptions without claiming that planned backends exist

## 0.21.1 — 2026-08-23

- introduce a unified Yin logo and reusable vector brand assets
- use the new mark across the Playground, browser metadata, README, and VS Code extension
- publish the current native extension release to the Visual Studio Marketplace

## 0.21.0 — 2026-08-23

- migrate the v0.19 semantic regression surface into native Rust integration
  suites covering language semantics, collections, Results and Options,
  structured contracts, modules, formatter, REPL, LSP, policy, and typed tools
- add a class-by-class ledger for all 27 historical JUnit classes and all 234
  tests, verified directly against the frozen `v0.19.0` tag
- add a differential semantic suite that runs the full frozen JUnit suite and
  compares shared positive outputs and negative type-check classifications with
  the Rust runtime in CI
- restore precise record, function, collection, Result, Option, tool, JSON,
  module, and persistent REPL semantics found by the migrated suites

## 0.20.0 — 2026-08-22

- freeze the complete Java 0.19 implementation and normative protocol on
  `codex/java-v0.19.0-archive` and the existing `v0.19.0` tag
- replace the JVM, Maven, JUnit, and TeaVM implementation with one Rust workspace
- preserve the language core across parser, checker, interpreter, modules,
  immutable collections, structured JSON, formatter, REPL, and LSP
- migrate deterministic contracts and canonical `.ybc` compilation to Rust while
  retaining the independent fuel-metered `yinvm`
- migrate the Agent Action Gateway, approval binding, durable nonce consumption,
  MCP stdio lifecycle, trace, and replay to Rust
- replace the browser runtime with Rust/Wasm and the Java VS Code server with
  `yin --lsp`
- publish native Linux, macOS, and Windows binaries plus the universal VSIX

## 0.19.0 — 2026-08-22

- reposition Yin as a small typed deterministic general language for CLI tools,
  data/configuration programs, and embeddable automation; retain Agent policy
  and approval capabilities as optional profiles rather than the product identity
- add covariant `(Dict K V)` and `(Set T)` types with an inferred `Never`
  bottom type for empty collections
- add immutable insertion-ordered dictionary construction, safe `Option` lookup,
  persistent update/removal, traversal, membership, and size operations
- add immutable ordered sets with deduplication, membership, persistent update,
  traversal, union, intersection, and difference
- define structural dictionary and Set equality independently of insertion order
  while retaining deterministic printing and traversal
- extend strict JSON decoding, deterministic encoding, and Draft 2020-12 schema
  generation for String-key dictionaries and sets
- add a maintained multi-file configuration-validator CLI using modules, Dict,
  Set, Option, Result, variants, stdin, and raw JSON stdout
- update the Playground, language specification/reference, roadmap, examples,
  implementation notes, editor syntax, extension, and all release metadata
- expand the JVM suite from 218 to 234 tests while retaining 3 Rust VM tests

## 0.18.0 — 2026-08-22

- add isolated `module` declarations with explicit, closed export lists and
  selective relative `import` forms
- evaluate canonical module files once per program and recursively resolve
  transitive dependencies relative to each importing file
- type-check the complete dependency graph while preserving dependency source
  spans in diagnostics
- reject private imports, undefined exports, local binding conflicts, duplicate
  module names, invalid module files, and circular imports with their chain
- qualify Record and Variant nominal identity by defining source so same-named
  types from separate modules cannot cross boundaries accidentally
- add file-URI-aware LSP analysis, formatter and VS Code syntax support, a
  maintained multi-file example, and module specification documentation
- reject modules from digest-bound contract, guard, and gateway modes until the
  complete dependency graph is included in their approval and trace hashes
- update release metadata to 0.18.0, bringing the JVM suite to 218 tests while
  retaining 3 Rust VM tests

## 0.17.0 — 2026-08-21

- add `--gateway` as a generic deny-by-default bridge from source-declared
  typed Yin tools to real newline-delimited MCP stdio subprocesses
- implement MCP initialization and version negotiation, `initialized`,
  paginated `tools/list`, bounded `tools/call`, cancellation, server pings, and
  graceful subprocess shutdown against protocol revision 2025-11-25
- add a closed `ActionIntent` boundary and require exact agreement among the
  source declaration, host mapping, remote tool, capability, effect, resource,
  and canonical typed arguments
- add `--approval-request` evidence bound to the program, host, full intent,
  arguments, actor, agent, expiry, and nonce, with locked durable single-use
  nonce consumption before external execution
- extend hash-chained traces across gateway authorization and MCP results while
  retaining side-effect-free `--replay`
- add a maintained ticket MCP server and one-command end-to-end example, plus
  approval tampering, expiry, replay, and nonce-reuse integration coverage
- update all maintained commands and release metadata to 0.17.0, bringing the
  JVM suite to 207 tests while retaining 3 Rust VM tests

## 0.16.0 — 2026-08-12

- add a canonical, versioned `.ybc` token-bytecode format with compiler-side
  type checking, profile admission, SHA-256 binding, and tamper verification
- add the independent Rust `yinvm` runtime for bytecode verification and
  deterministic execution without loading the JVM or reparsing Yin source
- meter VM parsing, declarations, input, evaluation, and encoded output with a
  caller-supplied fuel limit
- bound the first portable profile by rejecting explicit functions, `range`,
  and policy-to-policy calls; keep filesystem, output, tools, and ambient host
  authority unavailable
- add Java compiler/verifier tests, Rust parser/fuel tests, and cross-runtime
  capability-decision fixtures, bringing the suite to 201 JVM and 3 Rust tests
- teach CI and release packaging to validate and publish the Rust VM alongside
  the Yin JAR and VS Code extension

## 0.15.0 — 2026-08-12

- define `deterministic-policy-v1` as the executable, side-effect-free Agent
  decision profile ahead of a portable bytecode VM
- add `--contract-check` to type-check contracts and reject `Float`, `Any`,
  mutation, filesystem access, output, and tool authority
- add `--contract-run` for exact JSON input and digest-bound program, input,
  structured result, and result hashes
- add a maintained wallet-swap capability example with approve, reject, and
  human-approval conformance fixtures
- document the language/compiler/VM/host layers, repository boundary, and the
  deliberate absence of bytecode, fuel metering, and hostile-code isolation
- expand the automated suite from 191 to 197 tests

## 0.14.0 — 2026-08-12

- add `--guard` as a deny-by-default reference host for typed local-tool calls
- preflight source declarations against an explicit, closed host configuration
- provide root-confined `read-text` and approval-required `write-text` tools
- record source, input, host, authorization, tool result, and final outcome in
  create-only JSONL traces with a SHA-256 hash chain
- add `--replay` to verify a trace and reproduce its final result without
  invoking tools or repeating writes
- add a complete guarded note-workspace demo with read, approved-write, policy
  rejection, and replay workflows
- document the runtime configuration, security boundary, trace schema, and
  production limitations
- expand the automated suite from 182 to 191 tests

## 0.13.0 — 2026-08-12

- reposition Yin as a small typed policy language for Agent-to-tool boundaries
- add ordered `policy` definitions with first-match `when` rules and a required
  final `otherwise`
- add concise immutable field access such as `request.risk`, including chains
- lower policy and dotted-access syntax into the existing typed core AST
- flatten the maintained agent-review and Web3 transaction-guard decision trees
  without changing their fixtures or strict JSON boundary behavior
- update the formatter, LSP, VS Code grammar, browser Playground, specification,
  roadmap, and policy documentation for the readability reset
- expand the automated suite from 175 to 182 tests

## 0.12.0 — 2026-08-09

- add source-level `tool` declarations with typed input, output, and business-error contracts
- add `invoke`, returning explicit results and a built-in `ToolError` union for host failures
- declare capability, effect, approval, idempotency, and open-world metadata per tool
- enforce destructive-tool approval declarations and deny-by-default host approval policies
- add deterministic `--capabilities` preflight manifests and terminal tool audit events
- add a transport-neutral MCP `CallToolResult` adapter with strict structured output validation
- add a browser-hosted typed-tool example with formatter, editor, TeaVM, and regression coverage
- expand the automated suite from 165 to 175 tests

- add a complete typed agent-review demo with maintained approval, rejection,
  user-input, and strict boundary-error fixtures
- add `--json` for clean JSON stdout, stderr-isolated logs, and structured
  non-zero failure output
- organize maintained examples by algorithms, CLI, agents, and Web3
- add a Web3 transaction guard with wallet-intent policy fixtures for
  simulation, verification, chain, address, value, upgrade, and approval risk
- expose quicksort, structured-agent, agent-review, and Web3 guard in the
  browser Playground with editable JSON standard input

## 0.11.0 — 2026-08-09

- add closed `variant` declarations with named-field constructors and exhaustive matching
- add covariant `(Option T)`, `some`, `none`, `Some`, and `None`
- add strict type-directed `decode-json` with structured `DecodeError` codes and JSON paths
- add deterministic `encode-json` for records, vectors, Option, Result, and variants
- add deterministic JSON Schema Draft 2020-12 generation through `json-schema`
- add a complete typed standard-input/output structured-agent example
- extend formatter, browser runtime, LSP, VS Code syntax, specification, and documentation coverage
- expand the automated suite from 132 to 158 tests

## 0.10.0 — 2026-08-09

- establish the AI-first direction around typed boundaries, injected
  capabilities, approval, durable work, and replay
- add source-expressible `(Result T E)` outcome annotations
- add immutable `ok` and `err` constructors with precise `(Ok T)` and `(Err E)`
  variant types
- add covariant Result subtyping and exhaustive payload-narrowing patterns
- add structural Result equality and an explicit runtime-checked `Any` boundary
- add a maintained explicit-outcome program and browser Playground example
- expand editor syntax highlighting for the Yin 0.10 outcome forms
- expand the automated suite from 124 to 132 tests

## 0.9.0 — 2026-08-09

- add `(Vector T)` homogeneous collection annotations and `(Fn [T...] R)`
  positional function types
- add exhaustive `match` expressions with primitive, vector, record, literal,
  wildcard, and binding patterns
- add immutable `map`, `filter`, `fold`, `range`, `slice`, `reverse`, and
  `contains` operations
- add structural equality and complete string processing and parsing primitives
- expose injected `args`, `read-all`, and `read-text` program input boundaries
- add runnable word-count, quicksort, and argument-parsing examples
- expand editor syntax highlighting and browser runtime coverage for Yin 0.9
- expand the automated suite from 107 to 124 tests

## 0.8.0 — 2026-08-09

- add a minimal Language Server Protocol endpoint over standard input/output
- report syntax and type diagnostics for unsaved documents with full-text sync
- expose canonical whole-document formatting through the language server
- add a self-contained Visual Studio Code extension with bundled Yin server
- verify and publish the version-matched VSIX with GitHub release artifacts
- keep local Marketplace credentials and generated extension artifacts out of Git
- expand the automated suite from 101 to 107 tests

## 0.7.0 — 2026-08-09

- add `length`, `at`, and `append` for immutable vector use
- infer exact `at` types for literal indices and normalized element unions for
  dynamic indices
- distribute safe vector operations across union members and preserve `Any` as
  an explicit runtime-checked boundary
- reject invalid operands, non-integer indices, empty dynamic access, and
  out-of-bounds access with structured diagnostics
- make runtime vectors own immutable element snapshots
- add vector operations to the maintained corpus and browser Playground
- add a versioned executable JAR and `--version` CLI contract
- expand the automated suite from 91 to 101 tests

## 0.6.0-SNAPSHOT

- add `(field value :name)` for immutable record field access
- infer precise local and inherited field types
- distribute safe field access across union members and preserve `Any`
- reject missing fields, non-record targets, and malformed field syntax with
  structured diagnostics
- add field access to the maintained corpus, browser bridge, and Playground
- execute the generated TeaVM runtime in the Pages validation job
- expand the automated suite from 84 to 91 tests

## 0.5.0-SNAPSHOT

- compile the interpreter, type checker, and formatter to browser JavaScript
  with TeaVM
- expose a stateful browser bridge with values, types, output, and structured
  diagnostics
- add a responsive, accessible Yin Playground with runnable language examples
- isolate execution in a Web Worker with a 1.5-second time limit
- deploy the entirely static demo to GitHub Pages from `main`
- expand the automated suite from 77 to 83 tests

## 0.4.0-SNAPSHOT

- add an interactive REPL with persistent definitions and multiline forms
- recover from syntax, type, and runtime diagnostics without ending the session
- type-check each submission before executing its runtime effects
- support parsing in-memory source strings with virtual diagnostic locations
- expose `ReplSession` as a small stateful embedding API
- add a syntax-validating, comment-preserving, idempotent source formatter
- provide formatter print, CI check, and in-place write modes
- enforce canonical formatting across the maintained program corpus
- expand the automated suite from 63 to 77 tests

## 0.3.0-SNAPSHOT

- publish a normative grammar and deterministic evaluation-order specification
- define `Any`, union, vector, and nominal record subtype/equivalence rules
- make record inheritance transitive in static checks while keeping records
  immutable
- reject unsupported declarations, computed annotation types, empty unions,
  unknown descriptor properties, and misplaced return descriptors
- evaluate keyword and record arguments in source order
- classify every historical program and migrate two maintained examples
- expand the automated suite from 44 to 63 tests

## 0.2.0-SNAPSHOT

- separate runtime `Value` objects from static `YinType` representations
- parameterize lexical scopes so runtime and type environments cannot mix
- split runtime record constructors and values from static record types
- move primitive signatures out of runtime primitive implementations
- add structured diagnostics with stable codes and exact source spans
- classify missing source files as I/O diagnostics
- remove unreachable attribute and subscript AST implementations
- expand the automated suite from 38 to 44 tests

## 0.1.1-SNAPSHOT

- expand the automated suite from 16 to 38 tests
- reject unbound runtime names instead of propagating `null`
- preserve declared types across assignments
- reject duplicate keywords in descriptors
- align record inheritance conflict handling across execution modes
- correct the runtime identity and diagnostics of the `or` primitive
- define function and record defaults as definition-time expressions

## 0.1-SNAPSHOT

- restore a reproducible Java 17 and Maven Wrapper build
- add interpreter, type-checker, parser, and CLI regression coverage
- stabilize Float, function-call, record-construction, and diagnostic behavior
