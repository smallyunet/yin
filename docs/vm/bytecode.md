# Portable bytecode v1

`.ybc` is Yin 0.16's canonical transport between the Rust language front end
and the Rust VM. It is a compact token instruction stream, not UTF-8 Yin source
and not JVM bytecode.

## Container

All integers use big-endian byte order.

| Field | Encoding |
| --- | --- |
| magic | four bytes: `59 42 43 01` (`YBC` plus format family `1`) |
| format version | unsigned 16-bit integer, currently `1` |
| contract version | unsigned 16-bit integer, currently `1` |
| instruction count | unsigned 32-bit integer, `1..100000` |
| program hash | 32 raw SHA-256 bytes |
| instructions | exactly `instruction count` encoded tokens |

An artifact is at most 1 MiB and may not contain trailing bytes. Operand lengths
are unsigned 32-bit values followed by exact UTF-8 bytes.

## Instructions

| Opcode | Meaning | Operand |
| --- | --- | --- |
| `1` | open list | none |
| `2` | close list | none |
| `3` | open vector | none |
| `4` | close vector | none |
| `5` | name | UTF-8 |
| `6` | keyword | UTF-8 without `:` |
| `7` | canonical decimal `Int` | UTF-8 |
| `8` | string token content | UTF-8 |

The compiler renders instructions with one ASCII space between tokens and one
final newline. The stored program hash is SHA-256 over those normalized bytes.
This removes comments and formatting while preserving exact names and string
token content. The full artifact hash is SHA-256 over all `.ybc` bytes.

## Verification and execution

Both implementations reject invalid magic, unknown versions/opcodes, invalid
counts or lengths, truncation, unbalanced structure, hash mismatches, and
trailing data. The Rust compiler additionally parses and type-checks before
emission. The Rust verifier rejects explicit `fun`, `range`, and any
policy-to-policy call, including recursion.

The Rust VM supports the portable capability-decision core: records, variants,
ordered policies, matching, strict JSON input/output, field access, boolean and
integer decisions, and strings. Each input byte, declaration, evaluated
expression, and output byte consumes fuel. Exceeding the caller's limit aborts
without returning a decision.

## Security boundary

Portable bytecode v1 has no host imports other than immutable input. It provides
format integrity and bounded evaluation for the admitted subset. It does not
provide a byte-accurate memory quota, process isolation, signatures, secrets,
network consensus, or compatibility with arbitrary Yin programs. Hosts running
untrusted artifacts should still place `yinvm` inside an OS-level sandbox.
