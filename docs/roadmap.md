# Roadmap

The project is being stabilized before new language features are designed.

## 0.1 — reproducible baseline

- Java 17 build and Maven Wrapper
- JUnit integration suite for maintained examples
- GitHub Actions verification
- consistent Float and primitive type checking
- safe function and record argument validation
- testable diagnostics without JVM termination inside the language core
- scoped type-checker state and shared built-in registration

## 0.1.1 — correctness hardening

- semantic regression coverage for scope, assignment, closures, arguments,
  records, destructuring, unions, primitives, and parser diagnostics
- runtime errors for unbound names
- type-preserving assignment checks
- duplicate keyword and descriptor rejection
- consistent record inheritance conflicts in interpreter and type checker
- documented definition-time semantics for function and record defaults

## 0.2 — semantic cleanup

- runtime `Value` and static `YinType` hierarchies are separate
- runtime and type-checking environments are statically parameterized
- record constructors, record values, record types, and record value types have
  distinct representations
- primitive runtime implementations and static signatures are separate
- diagnostics expose stable codes and source spans
- unreachable attribute and subscript AST implementations are removed

## 0.3 — language definition

- normative grammar and deterministic evaluation order
- immutable records and nominal, transitive inheritance subtyping
- structural vector equivalence, union normalization, subtyping, and explicit
  inference boundaries
- complete classification of historical programs as migrated or archived
- executable specification and corpus-classification regression tests

## 0.4 — interactive tooling

- persistent, multiline REPL with diagnostic recovery
- in-memory parsing and a stateful embedding session
- deterministic, comment-preserving formatter with print, check, and write modes

## 0.5 — browser playground

- TeaVM JavaScript build of the language core
- stateful browser bridge and captured standard output
- responsive playground with examples, diagnostics, and online formatting
- Web Worker isolation and execution timeout
- automated GitHub Pages deployment

## 0.6 — record usability

- immutable `(field value :name)` field access
- precise local and inherited field types
- safe union distribution and an explicit `Any` rule
- structured syntax, missing-field, and non-record diagnostics
- maintained CLI, REPL, browser, formatter, and specification coverage

## 0.7 — vector usability

- immutable vector length, indexed access, and concatenation primitives
- exact element types for literal indices and normalized unions for dynamic
  indices
- safe distribution across vector unions and explicit runtime checks for `Any`
- structured operand, index, empty-vector, and bounds diagnostics
- maintained CLI, REPL, browser, formatter, and specification coverage

## 0.8 — editor services

- Language Server Protocol transport over standard input/output
- live syntax and type diagnostics for unsaved documents
- canonical whole-document formatting
- self-contained Visual Studio Code extension with a bundled Yin server
- protocol-level regression tests for initialization, document sync, and edits
- CI-verified VSIX packages published alongside executable release artifacts

## 0.9 — programmable core

- source-expressible homogeneous vector and positional function types
- exhaustive pattern matching over literals, built-in types, vectors, records,
  and union members
- immutable `map`, `filter`, `fold`, range, slice, reverse, and membership
  operations
- string comparison, transformation, splitting, joining, and numeric parsing
- injected program arguments, complete text input, and UTF-8 file reads
- maintained word-count, quicksort, and argument-parsing programs

## 0.10 — explicit outcomes

- source-expressible `(Result T E)` outcome types
- precise `(Ok T)` and `(Err E)` variant types from immutable `ok` and `err`
  constructors
- covariant widening into declared Result boundaries
- exhaustive `Ok` and `Err` pattern matching with typed payload narrowing
- structural Result equality and explicit behavior across `Any`
- maintained CLI, REPL, browser, formatter, specification, and editor coverage

## Later

- modules and multi-file type checking
- persistent dictionaries and sets
- explicit option values
- JSON values, schema validation, and record encoding
- effect and host-capability declarations
- typed tool and model boundaries
- durable tasks, checkpoints, approval, and record/replay
- bytecode or native compilation
- hover, completion, definition, and reference navigation

New syntax should not be added until it has a written rule, interpreter tests,
type-checker tests, and diagnostic tests.
