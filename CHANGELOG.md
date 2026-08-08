# Changelog

## 0.5.0-SNAPSHOT

- compile the interpreter, type checker, and formatter to browser JavaScript
  with TeaVM
- expose a stateful browser bridge with values, types, output, and structured
  diagnostics
- add a responsive, accessible Yin Playground with runnable language examples
- isolate execution in a Web Worker with a 1.5-second time limit
- deploy the entirely static demo to GitHub Pages from `main`
- expand the automated suite from 77 to 83 tests

## 0.4.0-SNAPSHOT

- add an interactive REPL with persistent definitions and multiline forms
- recover from syntax, type, and runtime diagnostics without ending the session
- type-check each submission before executing its runtime effects
- support parsing in-memory source strings with virtual diagnostic locations
- expose `ReplSession` as a small stateful embedding API
- add a syntax-validating, comment-preserving, idempotent source formatter
- provide formatter print, CI check, and in-place write modes
- enforce canonical formatting across the maintained program corpus
- expand the automated suite from 63 to 77 tests

## 0.3.0-SNAPSHOT

- publish a normative grammar and deterministic evaluation-order specification
- define `Any`, union, vector, and nominal record subtype/equivalence rules
- make record inheritance transitive in static checks while keeping records
  immutable
- reject unsupported declarations, computed annotation types, empty unions,
  unknown descriptor properties, and misplaced return descriptors
- evaluate keyword and record arguments in source order
- classify every historical program and migrate two maintained examples
- expand the automated suite from 44 to 63 tests

## 0.2.0-SNAPSHOT

- separate runtime `Value` objects from static `YinType` representations
- parameterize lexical scopes so runtime and type environments cannot mix
- split runtime record constructors and values from static record types
- move primitive signatures out of runtime primitive implementations
- add structured diagnostics with stable codes and exact source spans
- classify missing source files as I/O diagnostics
- remove unreachable attribute and subscript AST implementations
- expand the automated suite from 38 to 44 tests

## 0.1.1-SNAPSHOT

- expand the automated suite from 16 to 38 tests
- reject unbound runtime names instead of propagating `null`
- preserve declared types across assignments
- reject duplicate keywords in descriptors
- align record inheritance conflict handling across execution modes
- correct the runtime identity and diagnostics of the `or` primitive
- define function and record defaults as definition-time expressions

## 0.1-SNAPSHOT

- restore a reproducible Java 17 and Maven Wrapper build
- add interpreter, type-checker, parser, and CLI regression coverage
- stabilize Float, function-call, record-construction, and diagnostic behavior
