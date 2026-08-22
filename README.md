# The Yin Programming Language

Yin is a small, typed, deterministic programming language for reliable CLI
tools, data and configuration transformation, and embeddable automation. It
combines immutable data, explicit failures, exhaustive pattern matching,
isolated modules, strict JSON boundaries, and predictable evaluation in a
compact implementation.

Yin is a general programming language, not an approval system or an Agent-only
DSL. Agent policies, capability-safe tools, and deterministic decision
contracts remain supported as optional libraries and runtime profiles built on
the same language core.

The implementation descends from Yin Wang's 2013–2014 experiment and now
includes a hand-written parser, tree-walking interpreter, static type checker,
formatter, language server, browser runtime, bytecode compiler, and an
independent Rust VM for a portable deterministic subset. Yin is experimental
and not yet production ready. The untouched historical state is preserved by
the `legacy-2015` Git tag.

Try it in the [Yin Playground](https://smallyunet.github.io/yin/). Evaluation,
type checking, and formatting run locally in a Web Worker; source and input are
not sent to a server.

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

The maintained test suite checks interpreter and type-checker agreement,
source diagnostics, modules, JSON contracts, tooling, browser behavior, and
the independent portable VM. Yin 0.19 defines the collection and safe-access
semantics in the language specification rather than leaving them as host
library conventions.

## Requirements

- JDK 17 or newer
- Rust 1.85 or newer only to build `yinvm`
- Node.js 18 or newer only for the MCP demo and editor packaging
- no system Maven installation is required

## Install or build

Versioned executable JARs, Linux x86-64 `yinvm` binaries, Visual Studio Code
extension packages, and SHA-256 checksums are on the
[GitHub Releases page](https://github.com/smallyunet/yin/releases).

```bash
java -jar yin-0.19.0.jar --version
code --install-extension yin-language-support-0.19.0.vsix
```

Build and test from source:

```bash
./mvnw verify
cargo test --manifest-path vm/Cargo.toml
```

The executable JAR is `target/yin-0.19.0.jar`. Build the Rust VM with
`cargo build --release --manifest-path vm/Cargo.toml`.

## Run real programs

The multi-file configuration validator reads a JSON object from stdin,
validates required keys, supplies a default, and emits a closed JSON result:

```bash
printf '%s' '{"host":"localhost","port":"8080"}' | \
  java -jar target/yin-0.19.0.jar --json examples/config-validator/main.yin
```

```json
{"tag":"Valid","config":{"host":"localhost","port":"8080","mode":"development"}}
```

Other maintained programs:

```bash
java -jar target/yin-0.19.0.jar examples/algorithms/quicksort.yin
java -jar target/yin-0.19.0.jar examples/cli/parse-values.yin 10 bad 32
java -jar target/yin-0.19.0.jar examples/cli/wc.yin README.md
java -jar target/yin-0.19.0.jar examples/modules/main.yin
```

Run the type checker separately:

```bash
java -cp target/yin-0.19.0.jar \
  org.yinwang.yin.TypeChecker examples/config-validator/main.yin
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
java -jar target/yin-0.19.0.jar
```

Format or verify source:

```bash
java -jar target/yin-0.19.0.jar --format program.yin
java -jar target/yin-0.19.0.jar --format --check tests/*.yin
```

The bundled language server provides syntax/type diagnostics and whole-document
formatting. See the [REPL](docs/repl.md), [formatter](docs/formatter.md), and
[editor integration](docs/lsp.md) guides.

## Optional automation profiles

Yin's Agent and policy features are retained, but they are not the language's
identity. They demonstrate how a typed deterministic core can be embedded into
hosts with explicit authority:

- [ordered policy syntax](docs/policies.md)
- [typed capabilities and guarded reference host](docs/policy-runtime.md)
- [deterministic contract profile and portable VM](docs/vm/architecture.md)
- [MCP action gateway](docs/action-gateway.md)
- [automation-profile design boundary](docs/ai-first.md)

These profiles do not replace UI approval, authentication, process isolation,
or an organizational policy engine. Host implementations own authority and
side effects; Yin source declarations do not grant permission by themselves.

## Browser runtime

```bash
./mvnw -Pbrowser -DskipTests package
```

TeaVM writes the JavaScript runtime to `site/runtime/`. Serve `site/` over HTTP
for local development. Pushes to `main` deploy the same static directory to
GitHub Pages.

## Repository layout

```text
src/main/java/   parser, interpreter, type checker, runtime, LSP, and compiler
src/test/java/   integration, specification, tooling, and regression tests
vm/              independent Rust verifier and fuel-metered portable runtime
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
[implementation overview](docs/implementation.md), and the
[roadmap](docs/roadmap.md).

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
