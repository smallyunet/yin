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

## 0.2 — semantic cleanup

- separate runtime values from static type representations
- define structured diagnostics with source spans
- decide and document default-argument semantics
- either complete or remove unreachable attribute/subscript syntax

## 0.3 — language definition

- specify grammar and evaluation order
- specify record inheritance and mutation semantics
- specify type equivalence, unions, subtyping, and inference boundaries
- classify historical programs as supported, migrated, or archived

## Later, only after semantics stabilize

- REPL and formatter
- modules
- bytecode or native compilation
- editor/LSP integration

New syntax should not be added until it has a written rule, interpreter tests,
type-checker tests, and diagnostic tests.
