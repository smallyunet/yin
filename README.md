# The Yin Programming Language

Yin is an experimental programming language originally developed by Yin Wang
in 2013–2014. It uses an S-expression-like syntax and explores a small set of
language-design ideas through a hand-written parser, tree-walking interpreter,
and an incomplete static type checker.

The project is suitable for studying language implementation. It is not yet a
production-ready language. The untouched historical state is preserved by the
`legacy-2015` Git tag.

## Implemented and tested

- integers, floats, booleans, strings, and vectors
- arithmetic, comparison, and boolean primitives
- lexical scopes and first-class closures
- positional and keyword function arguments
- direct and mutual recursion
- records with typed fields and default values
- an experimental type checker

The JUnit integration suite runs every maintained program under `tests/` through
both the interpreter and type checker. The 77-test suite also covers parser
boundaries, lexical scoping, assignment, Float handling, function calls, record
inheritance, destructuring, unions, structured diagnostics, and architecture
boundaries between runtime values and static types. Yin 0.3 defines these
behaviors normatively rather than relying on historical implementation details.

## Requirements

- JDK 17 or newer
- no system Maven installation is required

## Build and test

```bash
./mvnw verify
```

This produces the executable JAR at `target/yin-0.4.0-SNAPSHOT.jar`.

## Run a program

```bash
java -jar target/yin-0.4.0-SNAPSHOT.jar tests/recursion-direct.yin
```

Run the type checker separately:

```bash
java -cp target/yin-0.4.0-SNAPSHOT.jar \
  org.yinwang.yin.TypeChecker tests/recursion-direct.yin
```

## Interactive REPL

Launch the REPL by running the JAR without a program path:

```bash
java -jar target/yin-0.4.0-SNAPSHOT.jar
```

Definitions persist across inputs, balanced multiline forms are supported, and
language errors do not terminate the session. Use `:quit` or `:q` to exit. See
the [REPL guide](docs/repl.md) for its precise behavior and embedding API.

## Format source

Print canonical formatting without changing the file:

```bash
java -jar target/yin-0.4.0-SNAPSHOT.jar --format tests/function1.yin
```

Use `--format --check` in CI or `--format --write` to update one or more files.
The formatter validates supported Yin syntax before changing output and retains
all line comments. See the [Formatter guide](docs/formatter.md).

## Repository layout

```text
src/main/java/   language implementation
  .../type/      static type representations and primitive signatures
  .../value/     runtime values, closures, constructors, and primitives
src/test/java/   automated integration and regression tests
tests/           maintained runnable Yin programs
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
[Implementation](docs/implementation.md), and [Roadmap](docs/roadmap.md) for the
maintained project boundaries.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
