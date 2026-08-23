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
explicit `Host`; browser hosts disable filesystem and subprocess capabilities,
while native gateway hosts install narrowly scoped tool executors.

Run the complete maintained verification path:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --all-targets
cargo build --release --workspace
.github/scripts/verify-rust-runtime.sh
.github/scripts/verify-rust-vm.sh 0.21.0
```

The conformance script executes the normative corpus, multi-file configuration
validator, deterministic compiler/VM boundary, and isolated end-to-end MCP
gateway with approval, nonce, trace, and replay.
