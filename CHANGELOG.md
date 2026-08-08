# Changelog

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
