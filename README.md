<p align="center">
  <img src="assets/yin-logo.svg" alt="Yin programming language" width="240">
</p>

# The Yin Programming Language

Yin is a typed deterministic language for portable programs and policies across
constrained execution environments. It combines immutable data, explicit
failures, exhaustive pattern matching, isolated modules, strict boundaries, and
predictable evaluation in a compact implementation.

The long-term architecture is one language core with checked target profiles.
Hosted CLI and browser execution work today. EVM, SVM, RISC-V, and Bitcoin
Script are planned compiler targets, not current capabilities. Agent policies
and the Action Gateway are application profiles built on the same core, not the
language's identity.

The implementation descends from Yin Wang's 2013–2014 experiment and is now a
Rust workspace containing a hand-written parser, tree-walking interpreter,
static type checker, formatter, language server, Wasm browser runtime, bytecode
compiler, Agent Action Gateway, and independent fuel-metered VM. Yin is
experimental and not yet production ready. The untouched historical state is
preserved by the `legacy-2015` Git tag.

Read the [language and compiler architecture](docs/architecture.md) for the
separation among Yin Core, target profiles, and application profiles. The
[target profile guide](docs/targets.md) records the proposed backend boundaries
and their honest implementation status.

Try it in the [Yin Playground](https://smallyunet.github.io/yin/). Parsing, type
checking, evaluation, and formatting run locally in a Rust/Wasm Web Worker;
source and input are not sent to a server.

```yin
(define config
  (dict "host" "localhost" "port" "8080"))

(define normalized
  (match (dict/get config "mode")
    [(Some _) config]
    [(None) (dict/put config "mode" "development")]))

(encode-json normalized)
```

`dict/get` returns `Option` for both present and missing keys. `dict/put`
returns a new insertion-ordered dictionary; `config` remains unchanged.

## What works today

- `Int`, `Float`, `Bool`, `String`, `Any`, union and function types, plus an inferred bottom type
- immutable exact and homogeneous vectors with higher-order operations
- immutable insertion-ordered `Dict` and `Set` values with structural equality
- safe dictionary lookup through `Option`, with no missing-key exception
- typed `Result` outcomes and exhaustive `Ok` / `Err` handling
- closed variants, nominal records, inheritance, and immutable field access
- lexical closures, keyword arguments, recursion, assignment, and pattern matching
- isolated file modules, explicit exports, selective imports, and graph-wide type checking
- strict typed JSON decoding, deterministic encoding, and Draft 2020-12 schemas
- command-line arguments, standard input, controlled UTF-8 file input, and output
- REPL, deterministic formatter, LSP diagnostics, VS Code support, and browser execution
- optional typed tools, ordered policies, capability manifests, guarded hosts,
  deterministic contracts, portable bytecode, and an MCP stdio gateway

The maintained Rust and conformance suites check interpreter/type-checker agreement,
source diagnostics, modules, JSON contracts, tooling, browser behavior, and
the independent portable VM. Yin 0.20 preserves the frozen v0.19 language and protocol
semantics in the language specification rather than leaving them as host
library conventions.

## Direction, not current support

Yin is evolving from an interpreter-centered implementation toward a reusable
compiler architecture:

```text
Yin source
  -> parser and module resolver
  -> typed HIR
  -> effect and target validation
  -> target-independent MIR
  -> hosted / portable VM / EVM / SVM / RISC-V / Bitcoin backends
```

Typed HIR, effect inference, MIR, fixed-width portable integers, and the EVM,
SVM, RISC-V, and Bitcoin backends are not implemented yet. The current `.ybc`
format remains a narrow normalized decision artifact, not a general compiler IR.

Portability is checked per program. Pure typed logic may be accepted by several
targets, while storage, accounts, syscalls, host tools, and spending conditions
remain explicit target capabilities. The compiler must reject unsupported
effects rather than approximate them.

## Requirements

- Rust 1.88 or newer
- Node.js 24 or newer for browser verification and editor packaging

## Install or build

Platform-specific `yin` and `yinvm` executables, the Visual Studio Code
extension, and SHA-256 checksums are on the
[GitHub Releases page](https://github.com/smallyunet/yin/releases).
The editor extension is also available directly from the
[Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=smallyu.yin-language-support).

```bash
yin --version
code --install-extension yin-language-support-0.21.1.vsix
```

Build and test from source:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --all-targets
cargo build --release --workspace
```

The executables are `target/release/yin` and `target/release/yinvm`.

## Run real programs

The multi-file configuration validator reads a JSON object from stdin,
validates required keys, supplies a default, and emits a closed JSON result:

```bash
printf '%s' '{"host":"localhost","port":"8080"}' | \
  target/release/yin --json examples/config-validator/main.yin
```

```json
{"tag":"Valid","config":{"host":"localhost","port":"8080","mode":"development"}}
```

Other maintained programs:

```bash
target/release/yin examples/algorithms/quicksort.yin
target/release/yin examples/cli/parse-values.yin 10 bad 32
target/release/yin examples/cli/wc.yin README.md
target/release/yin examples/modules/main.yin
```

See [examples/README.md](examples/README.md) for the maintained program catalog.

## Collections and safe access

Dictionary constructors take alternating key/value arguments. Set constructors
deduplicate by structural equality. Both collections preserve first-insertion
order so printed values, traversal, and JSON output are deterministic.

```yin
(define scores (dict "alice" 8 "bob" 13))
(define active (set "alice" "bob" "alice"))

[(dict/get scores "alice")       -- (some 8)
 (dict/get scores "carol")       -- none
 (dict/keys scores)               -- ["alice" "bob"]
 (set/values active)]             -- ["alice" "bob"]
```

The complete operation table and JSON rules are in the
[language reference](docs/language-reference.md).

## Modules

Modules declare their complete public surface and callers import selected
names. Relative imports resolve from the importing file; every reachable file
is type-checked; modules initialize once; cycles and binding conflicts are
diagnosed; nominal types from different files remain distinct.

```yin
(module math [double]
  (define double (fun ([value Int] [-> Int]) (* value 2))))
```

```yin
(import "./math.yin" [double])
(double 21)
```

See the [module guide](docs/modules.md).

## Interactive and editor tooling

Launch the persistent multiline REPL:

```bash
target/release/yin
```

Format or verify source:

```bash
target/release/yin --format program.yin
target/release/yin --format --check tests/*.yin
```

The bundled language server provides syntax/type diagnostics and whole-document
formatting. See the [REPL](docs/repl.md), [formatter](docs/formatter.md), and
[editor integration](docs/lsp.md) guides.

## AI and automation applications

AI is an application domain, not a compiler target. Yin's Agent and policy
features demonstrate how the typed deterministic core can validate an intent,
make a bounded decision, and pass explicitly authorized effects to a host:

- [ordered policy syntax](docs/policies.md)
- [typed capabilities and guarded reference host](docs/policy-runtime.md)
- [deterministic contract profile and portable VM](docs/vm/architecture.md)
- [MCP action gateway](docs/action-gateway.md)
- [AI and automation application boundary](docs/ai-first.md)

These profiles do not replace UI approval, authentication, process isolation,
or an organizational policy engine. Host implementations own authority and
side effects; Yin source declarations do not grant permission by themselves.

## Browser runtime

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.127 --locked
cargo build --release --target wasm32-unknown-unknown --lib
wasm-bindgen target/wasm32-unknown-unknown/release/yin.wasm \
  --out-dir site/runtime --out-name yin --target no-modules --no-typescript
```

`wasm-bindgen` writes the Rust/Wasm runtime to `site/runtime/`. Serve `site/` over HTTP
for local development. Pushes to `main` deploy the same static directory to
GitHub Pages.

## Repository layout

```text
rust/            parser, checker, interpreter, tooling, Wasm, gateway, and CLI
vm/              independent verifier and fuel-metered portable runtime
tests/           normative runnable language corpus
examples/        CLI, data/config, algorithm, module, Agent, and Web3 programs
site/            browser playground
experiments/     historical, potentially outdated programs
prototype1/      original Racket prototype
archive/         inactive implementation fragments
docs/            specification, guides, implementation notes, and roadmap
```

Start with the normative [language specification](docs/language-specification.md),
the concise [language reference](docs/language-reference.md), the
[architecture](docs/architecture.md), the [target profiles](docs/targets.md),
the [implementation overview](docs/implementation.md), and the
[roadmap](docs/roadmap.md).

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
