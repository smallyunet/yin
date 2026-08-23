# Tests

This directory contains the maintained runnable Yin programs and the native
Rust integration suites. `cargo test --workspace --all-targets` exercises the
parser, checker, interpreter, modules, immutable collections, structured JSON,
formatter, persistent REPL, LSP, policy, and typed-tool boundaries.

The migration ledger in `conformance/v019-test-classes.tsv` accounts for every
one of the 234 tests in the frozen v0.19 JUnit suite. CI also runs that suite
from the `v0.19.0` tag and compares observable output and rejection
classification against the Rust implementation.

Historical programs with outdated or speculative syntax remain under
`experiments/` and are not part of the supported baseline.

These programs are the normative executable corpus inherited from Yin 0.19. Historical
programs and their migrated or archived status are listed in
`docs/historical-programs.md`.
