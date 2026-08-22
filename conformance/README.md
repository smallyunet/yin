# Yin conformance corpus

The initial portable-VM conformance slice is the maintained
`examples/agents/capability-decision/` contract. Its source, three exact inputs,
and three expected structured decisions are executed by
`DeterministicContractRuntimeTest`.

It is a deliberately narrow deterministic subset, not the general language's
only target use case. Future Java, Rust, and Wasm implementations must consume the same fixtures and
agree on validation, decisions, errors, hashes, and eventually fuel. Bytecode
fixtures will be added only after the instruction format is versioned; the
source AST is not a portable wire format.
