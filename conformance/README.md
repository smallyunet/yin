# Yin conformance corpus

The portable-VM conformance slice is the maintained
`examples/agents/capability-decision/` contract. Its source, three exact inputs,
and three expected structured decisions are executed by the Rust contract and
VM verification scripts.

It is a deliberately narrow deterministic subset, not the general language's
only target use case. Native Rust, `yinvm`, and Wasm implementations consume
shared fixtures where their profiles overlap and must agree on validation,
decisions, errors, hashes, and fuel within those stated boundaries.

## v0.19 semantic migration

The Rust rewrite is checked against the last complete Java implementation in
three complementary ways:

1. `v019-test-classes.tsv` maps every historical JUnit class and its test count
   to maintained Rust, runtime, VM, or browser evidence. The migration audit
   verifies the counts directly from the immutable `v0.19.0` tag.
2. `v019-positive/` contains small programs whose exact stdout must agree
   between the v0.19 JAR and the current release-mode Rust binary. The same
   comparison also covers the maintained programs under `tests/` and selected
   examples.
3. `v019-reject/` contains invalid programs that both type checkers must reject.
   `V019Typecheck.java` is a narrow adapter around the frozen Java checker; it
   does not duplicate language logic.

Run the gates from the repository root after building the Rust release binary:

```sh
.github/scripts/verify-v019-test-migration.sh
.github/scripts/verify-v019-differential.sh
```

The comparison deliberately claims parity only for the mapped regression
surface and checked fixtures. Rust-only gateway, contract, VM, and browser
behavior remains covered by their dedicated verification scripts.
