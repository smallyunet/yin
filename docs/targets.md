# Target profiles

Target profiles make portability explicit and fail closed. A profile is a
versioned contract among the Yin frontend, backend, generated artifact, runtime,
and conformance suite. This document records the intended profile boundaries;
only `hosted` and the existing narrow portable VM are implemented today.

## Profile contract

Every target profile must define:

1. admitted source and HIR types;
2. integer width and overflow behavior;
3. supported control flow and termination requirements;
4. available effects and authority model;
5. memory, stack, code-size, and execution limits;
6. entry points and input/output ABI;
7. failure, trap, and rollback semantics;
8. artifact, metadata, and source-map formats;
9. deterministic-build and differential-test requirements;
10. profile and target runtime versions.

`yin check --target <profile>` should eventually report all unsupported
constructs before code generation. `yin build --target <profile>` must produce
only artifacts that passed the same validation.

## Current profiles

### `hosted`

The Rust interpreter, CLI, REPL, modules, browser embedding, controlled host I/O,
and installed tools form the current general-language environment. Availability
of filesystem, subprocess, wallet, or tool capabilities depends on the host.

### `portable-bytecode-v1`

The existing `.ybc` and `yinvm` path admits a deliberately narrow deterministic
decision subset with immutable input and fuel metering. It excludes ambient
authority and is not a general compiler IR, consensus runtime, memory sandbox,
or promise that arbitrary Yin programs are portable.

## Planned profiles

### `evm-contract-v1`

The EVM profile will need fixed-width integers, ABI entry points, calldata and
returndata lowering, deterministic memory and storage layouts, events, reverts,
execution context, and explicit external calls. The initial implementation may
lower through Yul before a native EVM assembler is justified.

It will reject hosted I/O, `Float`, dynamic `Any`, unbounded recursion, and any
operation without defined gas and rollback behavior.

### `svm-program-v1`

The SVM profile will target Solana's sBPF program environment. Its application
boundary must model accounts, signer/writable/owner constraints, program-derived
addresses, instruction data, compute units, and cross-program invocation.

These concepts belong in a target library and effect model, not as aliases for
EVM storage or generic hosted tools.

### `riscv64-v1`

RISC-V is an instruction set rather than a complete application environment.
The first profile must select an explicit ABI and runtime boundary, such as a
small bare-metal runtime or a named hosted environment. Initial lowering may use
LLVM IR while preserving a backend-independent Yin MIR.

RISC-V is the architectural check that the shared compiler middle-end has not
encoded assumptions specific to blockchain stack machines.

### `bitcoin-tapscript-v1`

The Bitcoin profile will accept only programs that can be proven to fit the
selected Script/Tapscript rules. It must statically establish termination,
maximum stack usage, opcode availability, numeric encoding, and script size.

Loops, recursion, dynamic allocation, persistent mutable state, and unsupported
introspection are rejected. The backend emits a spending condition, not a
general executable or a stateful smart contract.

## Cross-target subset

The project will not define the intersection of all targets as the whole Yin
language. Such an intersection would be too weak for useful hosted, EVM, or SVM
programs. Instead, the compiler determines whether each module or exported
function fits a requested set of profiles.

Portable libraries should prefer explicit fixed-width integers, pure functions,
closed records and variants, exhaustive matching, bounded collections, and
declared effects. Target adapters own calldata, accounts, syscalls, script
stacks, and other platform entry conventions.

## Status language

Documentation and releases must distinguish:

- **designed**: the profile contract is documented;
- **prototype**: some programs compile or run, without a stable contract;
- **experimental**: versioned artifacts and conformance tests exist;
- **supported**: compatibility and security maintenance commitments are stated.

The presence of a target in this document means **designed**, not implemented or
production ready.
