# Implementation overview

Yin 0.20 is a Rust workspace. The v0.19 Java implementation and its exact
normative specification are frozen on `codex/java-v0.19.0-archive` and tag
`v0.19.0`; no JVM code is required by the current runtime or release.

```text
UTF-8 source
  -> rust/syntax.rs       lexer, balanced forms, source spans
  -> rust/check.rs        static environments and contract checking
  -> rust/eval.rs         lexical runtime, modules, values, JSON, tools
  -> rust/main.rs         CLI, REPL, formatter, LSP and profile commands
```

Additional boundaries:

- `rust/format.rs` owns deterministic formatting.
- `rust/lsp.rs` owns framed stdio JSON-RPC, diagnostics, sync, and formatting.
- `rust/contract.rs` emits canonical `.ybc` and runs deterministic contracts.
- `rust/gateway.rs` owns reference file tools, request-bound approval, durable
  nonce consumption, MCP stdio execution, trace, and replay.
- `vm/` remains an independent parser, verifier, and fuel-metered evaluator for
  `portable-bytecode-v1`.
- the `wasm32-unknown-unknown` build exports evaluation and formatting to the
  browser without filesystem or process authority.

The runtime uses distinct `Type` and `Value` representations. Every executed
program is parsed and checked before evaluation. Host effects enter through an
explicit `Host`; browser hosts disable filesystem and subprocess capabilities
and expose only the educational wallet demo tool, while native gateway hosts
install their own narrowly scoped tool executors.

## Compiler evolution boundary

The current parser exposes a deliberately small surface `Expr` tree, and the
checker and evaluator interpret those expressions directly. That remains the
implemented 0.21.1 architecture. It is not sufficient as the interface for
multiple consensus and native backends.

The planned compiler separates:

```text
surface AST
  -> resolved and typed HIR
  -> inferred effects and target-profile validation
  -> target-independent control-flow MIR
  -> target-specific lowering and artifacts
```

The current evaluator will remain a semantic reference while a MIR evaluator
and backends are introduced. The existing `.ybc` token format remains the
artifact of `portable-bytecode-v1`; it will not be retroactively described as
HIR or MIR. See [language and compiler architecture](architecture.md) and
[target profiles](targets.md).

No EVM, SVM, RISC-V, or Bitcoin code generator is implemented in 0.21.1.

The first compiler-core slice exposes an experimental inspection path for the
admitted pure subset:

```bash
yin --emit-hir program.yin
```

It runs the normative checker first, resolves bindings and parameters to stable
`SymbolId` values, attaches the checker's expression types and source spans,
and prints a deterministic HIR snapshot. The existing interpreter does not
consume HIR yet. Unsupported forms fail with a source-spanned Phase 1
diagnostic; this is not a restriction on normal hosted execution.

Run the complete maintained verification path:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --all-targets
cargo build --release --workspace
.github/scripts/verify-rust-runtime.sh 0.21.1
.github/scripts/verify-rust-vm.sh 0.21.1
```

The conformance script executes the normative corpus, multi-file configuration
validator, deterministic compiler/VM boundary, and isolated end-to-end MCP
gateway with approval, nonce, trace, and replay.
