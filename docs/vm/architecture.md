# Yin contract architecture

Yin 0.16 separates the human-facing language, portable artifact, execution
machine, and authority-bearing host:

```text
Yin source
  -> Rust parser, profile validator, and type checker
  -> canonical .ybc artifact
  -> independent Rust verifier and fuel-metered VM
  -> deterministic decision envelope
  -> host authorization and side effects
```

The language defines syntax, types, and source semantics. The compiler admits
only `portable-bytecode-v1`, removes comments and formatting, emits versioned
token instructions, and binds the normalized program with SHA-256. The Rust VM
does not load the source runtime or the original source. It verifies the container,
reconstructs structured bytecode expressions, meters evaluation, and returns a
digest-bound JSON decision.

The host remains outside the VM. It supplies normalized input, chooses a fuel
limit, interprets `Approve | Reject | NeedsApproval`, issues enforceable
capabilities, invokes tools, and stores audit evidence. Neither Yin source nor
the VM holds credentials or grants authority by itself.

## Version 0.16 boundary

The Rust `yin` commands are compiler-side operations:

- `--contract-check` validates the source-level `deterministic-policy-v1`;
- `--contract-run` remains the Rust reference evaluator used for conformance;
- `--contract-compile ... --output ...` emits canonical `.ybc`;
- `--bytecode-check` revalidates an artifact using the compiler implementation.

The Rust `yinvm` commands are execution-side operations:

- `yinvm check program.ybc` performs independent container and digest checks;
- `yinvm run program.ybc --input input.json --fuel N` evaluates it with no
  filesystem, network, clock, randomness, output, tools, or ambient arguments.

The VM envelope contains its version and profile, bytecode/program/input/result
digests, the structured result, and `fuelLimit`/`fuelUsed`.

## Intended use

```text
Agent tool request
  -> trusted host normalizes request and context
  -> Rust Yin VM evaluates verified policy bytecode
  -> Approve | Reject | NeedsApproval
  -> host optionally issues a constrained, expiring, one-use capability
  -> executor validates the capability and performs the real action
```

The VM makes the action boundary typed, deterministic, deny-by-default, and
auditable. It does not make an Agent smarter, execute arbitrary Agent plans, or
replace OS/container isolation.

## Repository boundary

The Rust compiler and Rust VM remain in this repository while bytecode v1 is
young, so every format change can be tested atomically across both runtimes. A
separate VM repository becomes useful when the bytecode protocol is stable and
the VM needs an independent security review or release lifecycle.
