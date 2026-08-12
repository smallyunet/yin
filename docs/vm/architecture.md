# Yin contract architecture

Yin separates the human-facing language from a future portable execution
machine. Yin 0.15 establishes the first executable boundary without claiming
that the current tree-walking interpreter is already a VM.

```text
Yin source language
  -> parser and type checker
  -> deterministic contract profile
  -> current Java reference evaluator
  -> decision envelope
  -> host authorization and side effects
```

The language defines syntax, types, and evaluation meaning. A future compiler
will lower the accepted profile to canonical bytecode. A future Yin VM will
verify and meter that bytecode. The host remains separate: it supplies trusted
context, issues enforceable capabilities, obtains approval, invokes tools, and
stores audit evidence.

## Version 0.15 boundary

`--contract-check` parses and type-checks a source file and rejects constructs
outside `deterministic-policy-v1`. `--contract-run` additionally injects one
fixed JSON input, evaluates with no arguments, filesystem, tools, authorization,
audit sink, or output channel, and requires the program to return JSON text from
`encode-json`.

The result envelope contains:

- `contractVersion`, currently `1`;
- the named execution profile;
- `programHash`, over the exact UTF-8 source bytes;
- `inputHash`, over the exact UTF-8 input bytes;
- the structured JSON `result`; and
- `resultHash`, over its compact JSON representation.

This makes reproducibility observable but does not yet provide bytecode,
instruction metering, a memory bound, isolation from hostile source, a signed
decision, or consensus compatibility. Only trusted, prevalidated programs
should run through the 0.15 evaluator.

## Intended use

The first use is a pure decision point between an Agent and a high-impact tool:

```text
Agent tool request
  -> trusted host assembles normalized request and context
  -> Yin deterministic decision
  -> Approve | Reject | NeedsApproval
  -> host optionally issues a constrained, expiring, one-use capability
  -> executor validates the capability and performs the real action
```

The VM does not make the model smarter and must not hold secrets. Its purpose is
to make the action boundary typed, deterministic, deny-by-default, and
auditable.

## Repository boundary

The language front end, reference interpreter, executable specification, and
contract prototype remain in one repository while the protocol is changing.
A separate VM repository becomes useful only after bytecode is versioned, the
compiler and VM communicate only through that format, and the VM has an
independent security or release lifecycle.
