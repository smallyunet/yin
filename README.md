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
both the interpreter and type checker. The 38-test suite also covers parser
boundaries, lexical scoping, assignment, Float handling, function calls, record
inheritance, destructuring, unions, and diagnostics.

## Requirements

- JDK 17 or newer
- no system Maven installation is required

## Build and test

```bash
./mvnw verify
```

This produces the executable JAR at `target/yin-0.1.1-SNAPSHOT.jar`.

## Run a program

```bash
java -jar target/yin-0.1.1-SNAPSHOT.jar tests/recursion-direct.yin
```

Run the type checker separately:

```bash
java -cp target/yin-0.1.1-SNAPSHOT.jar \
  org.yinwang.yin.TypeChecker tests/recursion-direct.yin
```

## Repository layout

```text
src/main/java/   language implementation
src/test/java/   automated integration and regression tests
tests/           maintained runnable Yin programs
experiments/     historical, potentially outdated language experiments
prototype1/      original Racket prototype
emacs/           historical Emacs modes
archive/         inactive implementation fragments
docs/            language, architecture, and roadmap notes
```

See [Language reference](docs/language-reference.md),
[Implementation](docs/implementation.md), and [Roadmap](docs/roadmap.md) for
the maintained project boundaries.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
