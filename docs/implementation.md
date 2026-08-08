# Implementation overview

Yin is implemented as a direct pipeline:

```text
source file
  -> Lexer
  -> PreParser (balanced delimiter tree)
  -> Parser (semantic AST)
  -> Interpreter or TypeChecker
```

## Main components

- `parser/Lexer.java` tokenizes source text and tracks source positions.
- `parser/PreParser.java` constructs balanced tuple and vector forms.
- `parser/Parser.java` converts those forms into semantic AST nodes.
- `ast/` contains evaluation and type-checking behavior for each construct.
- `Scope.java` implements lexical environment chains.
- `Interpreter.java` evaluates an AST against the runtime initial scope.
- `TypeChecker.java` evaluates an AST against a type-oriented initial scope.
- `value/` contains runtime values, types, closures, records, and primitives.

## Diagnostics

Language failures throw `GeneralError`. Library callers can inspect or test the
error without terminating the JVM. CLI entry points catch the error, print a
concise diagnostic, and return a non-zero process exit code.

Parser syntax failures remain represented by `ParserException` and are wrapped
at the interpreter/type-checker boundary.

## Tests

The integration tests deliberately exercise complete source files rather than
isolated AST construction. They cover the public interpreter and type-checker
entry points and protect the behavior of the maintained examples.

Run all checks with:

```bash
./mvnw verify
```

## Current architectural debt

- runtime values and static types share the `Value` hierarchy
- source diagnostics are plain exceptions rather than structured diagnostic
  objects
- some experimental AST nodes are not reachable from the current parser
