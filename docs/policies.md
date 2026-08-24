# Ordered policies

`policy` is application-facing syntax over the shared language core, not an
Agent-only or blockchain-only sublanguage. A policy may be portable when its
types, control flow, and effects satisfy a requested target profile.

Yin 0.13 added a human-facing form for typed decision policy. It keeps the
existing S-expression core and changes how decision trees are written, not how
their conditions or outcomes are evaluated.

```yin
(policy review
  ([request ReviewRequest] [-> Decision])
  (when (= request.risk "blocked")
    (Reject :reason "blocked by policy"))
  (when (> request.amount 10000)
    (NeedsApproval :reason "amount requires approval"))
  (otherwise
    (Approve :reason "within policy")))
```

## Semantics

- Parameters use the same typed descriptor form as `fun`.
- A policy contains one or more `when` clauses and exactly one final
  `otherwise` clause.
- Conditions are checked as `Bool`.
- Rules are tested from top to bottom; only the first matching outcome is
  evaluated.
- Every outcome must satisfy the declared return type.
- A policy creates an ordinary lexical function binding and can be called with
  positional or keyword arguments.

The parser lowers the example above to the equivalent typed function:

```yin
(define review
  (fun ([request ReviewRequest] [-> Decision])
    (if (= (field request :risk) "blocked")
      (Reject :reason "blocked by policy")
      (if (> (field request :amount) 10000)
        (NeedsApproval :reason "amount requires approval")
        (Approve :reason "within policy")))))
```

This lowering means policies inherit existing lexical scope, lazy branch
evaluation, return checking, browser execution, formatting, and LSP diagnostics.

## Dot-style fields

`request.risk` is concise syntax for `(field request :risk)`. Chained access is
left-associated, so `request.account.owner` means:

```yin
(field (field request :account) :owner)
```

The target remains immutable and each field is checked against the precise
record type. The original `field` form remains supported as core syntax.

## Scope

`policy` is intended for deterministic decisions over already normalized data.
It does not fetch remote state, grant tool authority, or execute a wallet action
by itself. Hosts remain responsible for normalization and external execution;
typed tool declarations and deny-by-default authorization define that boundary.
