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
- `Scope.java` implements generic lexical environment chains. Runtime scopes
  contain `Value`; type-checking scopes contain `YinType`.
- `Interpreter.java` evaluates an AST against the runtime initial scope.
- `TypeChecker.java` evaluates an AST against a type-oriented initial scope.
- `ReplSession.java` keeps paired runtime and type scopes across interactive
  submissions; `Repl.java` owns terminal input, multiline recovery, and output.
- `value/` contains runtime values, closures, record constructors, and runtime
  primitives.
- `type/` contains static types, record and function types, unions, and
  primitive signatures. No class in this package extends `Value`.

Every semantic AST node has two deliberately distinct entry points:

```text
interp(Scope<Value>)       -> Value
typecheck(Scope<YinType>)  -> YinType
```

Java's type system therefore prevents a runtime value from being inserted into
a static environment, or a static type from being returned by the interpreter.

## Diagnostics

Language failures throw `GeneralError`, which owns an immutable `Diagnostic`.
Diagnostics contain a stable category code, message, and optional `SourceSpan`
with file, offsets, line, and column. Library callers can inspect these fields
without parsing CLI text.

- `YIN0001`: language/runtime/type error
- `YIN1001`: syntax error
- `YIN1002`: source I/O error

CLI entry points format the same diagnostic and return a non-zero process exit
code.

Parser syntax failures remain represented internally by `ParserException` and
are converted to structured diagnostics at the interpreter/type-checker
boundary.

## Tests

The integration tests deliberately exercise complete source files rather than
isolated AST construction. They cover the public interpreter and type-checker
entry points and protect the behavior of the maintained examples.
`LanguageSpecificationTest` makes the normative evaluation and type rules
executable, while `HistoricalCorpusTest` ensures every historical source stays
explicitly classified and every migrated replacement remains runnable.
`ReplTest` covers in-memory parsing, persistent state, pre-execution type
checking, multiline input, error recovery, and incomplete input at EOF.

Run all checks with:

```bash
./mvnw verify
```

## Current architectural boundary

- attribute access and subscripting are intentionally absent from the supported
  grammar and AST
- descriptor forms remain stored in a generic property table before evaluation
- the type system remains experimental and is not a soundness proof
