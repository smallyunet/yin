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
- `parser/Parser.java` converts those forms into semantic AST nodes. Readable
  `policy` rules and dotted fields lower here to the existing function,
  conditional, and immutable-field core.
- `ast/` contains evaluation and type-checking behavior for each construct.
- `ast/FieldAccess.java` implements immutable record field reads and their
  record, union, and `Any` type rules.
- `ast/Match.java` evaluates patterns and performs branch binding, union
  narrowing, and exhaustiveness checks.
- `ast/VariantDef.java` installs closed tagged unions and their named case
  constructors; `ast/JsonOperation.java` preserves type-directed boundaries.
- `json/JsonCodec.java` owns strict parsing, deterministic value encoding, and
  deterministic Draft 2020-12 schema generation.
- `Scope.java` implements generic lexical environment chains. Runtime scopes
  contain `Value`; type-checking scopes contain `YinType`.
- `Interpreter.java` evaluates an AST against the runtime initial scope.
  Its `--json` host mode keeps stdout machine-readable and routes program logs
  to stderr for structured pipelines.
- `RuntimeContext.java` injects output, complete text input, program arguments,
  UTF-8 resource reads, named tool handlers, authorization policy, and an audit sink
  instead of hiding host effects in language nodes.
- `ast/ToolDef.java`, `ast/Invoke.java`, `type/ToolType.java`, and
  `value/ToolValue.java` keep declared authority, static contracts, runtime
  handles, and invocation separate. `CapabilityManifest.java` performs
  type-checked preflight without execution.
- `tool/McpToolAdapter.java` maps MCP `structuredContent` and `isError` into the
  stable Yin host boundary. Remote annotations are never used as authorization.
- `TypeChecker.java` evaluates an AST against a type-oriented initial scope.
- `ReplSession.java` keeps paired runtime and type scopes across interactive
  submissions; `Repl.java` owns terminal input, multiline recovery, and output.
- `Formatter.java` validates with the semantic parser, then renders a small
  concrete syntax tree so comments and original string tokens remain intact.
- `lsp/LanguageService.java` applies the parser, type checker, and formatter to
  unsaved source text without retaining editor state.
- `lsp/YinLanguageServer.java` implements JSON-RPC framing, document sync,
  diagnostics, and formatting over standard input/output.
- `value/` contains runtime values, closures, record constructors, and runtime
  primitives.
- `value/ResultValue.java` and the distinct `OkType`, `ErrType`, and
  `ResultType` static representations keep expected failures explicit without
  mixing runtime values into the type environment.
- `value/Vector.java` stores an immutable element snapshot; checked primitives
  provide indexing, concatenation, mapping, filtering, folding, slicing,
  reversal, ranges, and membership without mutable collection state.
- `type/` contains static types, record and function types, unions, and
  primitive signatures. No class in this package extends `Value`.
- `type/HomogeneousVectorType.java` complements precise fixed `VectorType`
  values, while `DeclaredFunctionType.java` makes positional callable
  signatures source-expressible for higher-order operations.

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
`FormatterTest` covers comment safety, idempotence, semantic preservation, CLI
modes, invalid input, and canonical formatting of every maintained program.
`LanguageServerIntegrationTest` drives framed protocol messages to protect
initialization, diagnostic clearing, shutdown, and formatting responses.
`LanguageCompletenessTest` protects the Yin 0.9 programmable-core slice from type
annotations through match, collection/string processing, host input, and
complete runnable examples.
`ResultIntegrationTest` protects the Yin 0.10 explicit-outcome slice across
construction, covariance, exhaustive narrowing, structural equality, `Any`,
and the maintained result program.
`StructuredContractsIntegrationTest` protects the Yin 0.11 variant, Option,
strict JSON, structured error path, schema, and complete agent-boundary slice.
`ToolIntegrationTest` protects the Yin 0.12 declaration, manifest, host
injection, approval, audit, structured-result validation, and MCP adapter slice.
`PolicyIntegrationTest` protects the Yin 0.13 ordered-rule lowering, mandatory
fallback, first-match behavior, dotted immutable field access, formatting, and
policy diagnostics.
`ReferencePolicyRuntimeTest` protects the Yin 0.14 preflight host agreement,
root confinement, write approval, create-only trace, hash-chain verification,
and side-effect-free replay boundary.
`AgentReviewDemoTest` executes every maintained policy and malformed-input
fixture through the raw JSON CLI boundary.
`Web3TransactionGuardDemoTest` protects the normalized wallet-intent policy
boundary without claiming RPC, ABI, signing, or broadcast support.

Run all checks with:

```bash
./mvnw verify
```

## Current architectural boundary

- record mutation and generic subscript syntax are intentionally absent from the
  supported grammar and AST
- descriptor forms remain stored in a generic property table before evaluation
- the type system remains experimental and is not a soundness proof
