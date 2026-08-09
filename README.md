# The Yin Programming Language

Yin is an experimental programming language originally developed by Yin Wang
in 2013–2014. It uses an S-expression-like syntax and explores a small set of
language-design ideas through a hand-written parser, tree-walking interpreter,
and an experimental static type checker.

The project is suitable for studying language implementation. It is not yet a
production-ready language. The untouched historical state is preserved by the
`legacy-2015` Git tag.

Try the language in the browser at the
[Yin Playground](https://smallyunet.github.io/yin/). Evaluation, type checking,
and formatting run locally in a Web Worker; no source code is sent to a server.

## Implemented and tested

- integers, floats, booleans, strings, and vectors
- exact and homogeneous immutable vectors with higher-order processing
- exhaustive pattern matching over primitives, vectors, records, and unions
- typed `Result` outcomes with exhaustive `Ok` and `Err` handling
- closed tagged variants and first-class `Option` values
- strict typed JSON decoding, deterministic encoding, and Draft 2020-12 schemas
- string transformation, parsing, program arguments, and controlled text input
- arithmetic, comparison, and boolean primitives
- lexical scopes and first-class closures
- positional and keyword function arguments
- direct and mutual recursion
- records with typed fields and default values
- immutable record field access, including inherited fields
- an experimental type checker
- editor diagnostics and whole-document formatting through LSP

The JUnit integration suite runs every maintained program under `tests/` through
both the interpreter and type checker. The automated suite also covers parser
boundaries, lexical scoping, assignment, Float handling, function calls, record
inheritance, destructuring, unions, structured diagnostics, and architecture
boundaries between runtime values and static types. Yin 0.11 defines these
behaviors normatively rather than relying on historical implementation details.

## Requirements

- JDK 17 or newer
- no system Maven installation is required

## Releases

Versioned executable JARs, Visual Studio Code extension packages, and SHA-256
checksum files are published on the
[GitHub Releases page](https://github.com/smallyunet/yin/releases). Confirm a
downloaded JAR before running it:

```bash
java -jar yin-0.11.0.jar --version
```

Install the matching editor extension from the downloaded VSIX:

```bash
code --install-extension yin-language-support-0.11.0.vsix
```

## Build and test

```bash
./mvnw verify
```

This produces the executable JAR at `target/yin-0.11.0.jar`.

## Run a program

```bash
java -jar target/yin-0.11.0.jar tests/program-usability.yin
```

Run the type checker separately:

```bash
java -cp target/yin-0.11.0.jar \
  org.yinwang.yin.TypeChecker tests/program-usability.yin
```

Run complete example programs:

```bash
java -jar target/yin-0.11.0.jar examples/quicksort.yin
java -jar target/yin-0.11.0.jar examples/parse-values.yin 10 bad 32
printf '%s' '{"task":"review","confidence":0.95}' | \
  java -jar target/yin-0.11.0.jar examples/structured-agent.yin
java -jar target/yin-0.11.0.jar examples/wc.yin README.md
```

## Interactive REPL

Launch the REPL by running the JAR without a program path:

```bash
java -jar target/yin-0.11.0.jar
```

Definitions persist across inputs, balanced multiline forms are supported, and
language errors do not terminate the session. Use `:quit` or `:q` to exit. See
the [REPL guide](docs/repl.md) for its precise behavior and embedding API.

## Format source

Print canonical formatting without changing the file:

```bash
java -jar target/yin-0.11.0.jar --format tests/function1.yin
```

Use `--format --check` in CI or `--format --write` to update one or more files.
The formatter validates supported Yin syntax before changing output and retains
all line comments. See the [Formatter guide](docs/formatter.md).

## Build the browser demo

```bash
./mvnw -Pbrowser -DskipTests package
```

TeaVM writes the generated JavaScript runtime to `site/runtime/`. Serve `site/`
over HTTP for local development. Pushes to `main` build and deploy the same
static directory to GitHub Pages.

## Repository layout

```text
src/main/java/   language implementation
  .../type/      static type representations and primitive signatures
  .../value/     runtime values, closures, constructors, and primitives
src/test/java/   automated integration and regression tests
tests/           maintained runnable Yin programs
examples/        complete command-line and algorithm examples
experiments/     historical, potentially outdated language experiments
prototype1/      original Racket prototype
emacs/           historical Emacs modes
archive/         inactive implementation fragments
docs/            language, architecture, and roadmap notes
```

See the normative [Language specification](docs/language-specification.md), the
short [Language reference](docs/language-reference.md), the
[historical-program classification](docs/historical-programs.md),
[REPL guide](docs/repl.md), [Formatter guide](docs/formatter.md),
[Editor integration](docs/lsp.md),
[Implementation](docs/implementation.md), and [Roadmap](docs/roadmap.md) for the
maintained project boundaries.
The [AI-first direction](docs/ai-first.md) defines the structured-contract,
capability, tool, model, and durable-agent sequence without binding Yin syntax
to one provider protocol.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
