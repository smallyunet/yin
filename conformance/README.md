# Yin conformance corpus

The initial conformance slice is the maintained
`examples/agents/capability-decision/` contract. Its source, three exact inputs,
and three expected structured decisions are executed by
`DeterministicContractRuntimeTest`.

Future Java, Rust, and Wasm implementations must consume the same fixtures and
agree on validation, decisions, errors, hashes, and eventually fuel. Bytecode
fixtures will be added only after the instruction format is versioned; the
source AST is not a portable wire format.
