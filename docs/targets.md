# Target profiles

Target profiles make portability explicit and fail closed. A profile is a
versioned contract among the Yin frontend, backend, generated artifact, runtime,
and conformance suite. The registry and first validators are now implemented;
validation does not imply that an artifact backend exists.

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

Inspect the registry or validate one source file:

```bash
yin --target-profiles
yin check --target mir-pure-v1 program.yin
```

The JSON report includes the profile status, backend availability, whole-program
and entry effects, named-function call edges, source-spanned effect origins, and
all detected violations. Designed-only profiles fail closed. The current
validator does not resolve an imported module graph; `import` is reported as a
`module-load` effect until graph binding is added.

`--contract-check`, `--contract-compile`, and `contract_run` use the same
`portable-bytecode-v1` validation path, so the portable artifact path cannot
bypass its profile check.

## Current profiles

### `hosted-v1` — supported

The Rust interpreter, CLI, REPL, modules, browser embedding, controlled host I/O,
and installed tools form the current general-language environment. Availability
of filesystem, subprocess, wallet, or tool capabilities depends on the host.
This source check does not grant or verify runtime authority; host configuration
continues to decide which capabilities are actually available.

### `portable-bytecode-v1` — experimental

The existing `.ybc` and `yinvm` path admits a deliberately narrow deterministic
decision subset with immutable input and fuel metering. It excludes ambient
authority and is not a general compiler IR, consensus runtime, memory sandbox,
or promise that arbitrary Yin programs are portable.

It permits allocation and the exact `read-all` input boundary while rejecting
other host I/O, mutation, dynamic calls, tools, unsupported collections,
`Float`, `Any`, and integers outside the current signed 64-bit VM range.

### `mir-pure-v1` — prototype

This profile admits data-returning programs accepted by the experimental MIR
lowerer and evaluator. Allocation is admitted; host I/O, mutation, external
calls, persistent state, module loading, authorization, and unresolved dynamic
calls are rejected with their source origins. It produces no distributable
artifact and is not a consensus profile.

## Designed profiles without validators or backends

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

The profile registry exposes status, validator availability, and artifact
backend availability separately. A successful check currently exists only for
`hosted-v1`, `portable-bytecode-v1`, and `mir-pure-v1`; the other registered
profiles return a designed-only violation rather than pretending to validate.
