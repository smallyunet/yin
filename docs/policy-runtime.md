# Reference policy runtime

Yin 0.14 closes the first executable Agent-to-tool loop. `--guard` runs one
typed Yin program with untrusted JSON input, an explicit host configuration,
deny-by-default authorization, and a create-only decision trace.

## Command

```bash
yin --guard program.yin \
  --input request.json \
  --host host.json \
  --trace trace.jsonl \
  [--approve capability]
```

All three file options are required. `--approve` can be repeated, and approves
only an exact capability named by a source declaration and matching host entry.
It does not install a tool or bypass effect checks.

Before creating the trace or executing source, the runtime:

1. parses and type-checks the complete Yin program;
2. collects its deterministic capability manifest;
3. parses a closed versioned host configuration;
4. rejects installed tools whose name, capability, or effect differs from the
   source declaration; and
5. rejects every installed write tool whose declaration does not require
   approval.

## Host configuration

The v1 host format deliberately supports only two local tool kinds:

```json
{
  "version": 1,
  "root": "runtime",
  "tools": [
    {"name": "read-note", "kind": "read-text", "capability": "notes.read"},
    {"name": "write-note", "kind": "write-text", "capability": "notes.write"}
  ]
}
```

`root` is resolved relative to the host file and must already be a directory.
Tool names connect host implementations to source-level `tool` declarations.
Unknown configuration fields, duplicate tools, undeclared installations, and
metadata mismatches fail preflight.

`read-text` accepts `{ "path": String }` and returns
`{ "path": String, "content": String }`. `write-text` accepts
`{ "path": String, "content": String }` and returns
`{ "path": String, "bytes": Int }`. Both use the demo's `FileFailure` variant:
`InvalidPath`, `NotFound`, or `IoError`, each with a `message` field.

Absolute paths and lexical traversal outside `root` are rejected. Existing
targets and parent directories are resolved to real paths before access, so a
symlink cannot escape the configured root. Writes do not create parent
directories.

## Authorization

Installing a tool is necessary but not sufficient. The reference host grants an
ordinary read only when name, capability, and effect match. A read declaration
may additionally require approval. Every write must have `approval true` in
source and an exact `--approve` capability on that run. Source declarations
alone never grant authority.

Policy and host authorization are separate on purpose. A Yin policy can reject
a request before any tool call; if it permits the request, the host still makes
the final authority decision.

## Trace and replay

Every successful start creates a new JSONL file. Existing trace paths are never
overwritten. Events record:

- runtime and trace versions, a run ID, source path, and SHA-256 digests of the
  source, input, and host configuration;
- the capability manifest and explicitly approved capabilities;
- each authorization decision and its reason;
- each terminal tool status, payload digests, and the tool output; and
- the final channel, exit code, output, and output digest.

Each event contains a sequence number, previous-event hash, and its own SHA-256
hash. Replay verifies the chain's internal consistency and requires exactly one
final `run-completed` event:

```bash
yin --replay trace.jsonl
```

Replay prints the recorded final output. It does not parse the original source,
run a policy, authorize a capability, or invoke a tool, so it cannot repeat an
external write.

## Security boundary

This host is a reference implementation, not a general sandbox. It has no MCP
client, process execution, secret broker, user identity, signed approvals,
multi-tenant isolation, or durable suspension. A process running Yin retains
the operating-system permissions of its native process.

Traces include tool output so replay can reproduce the boundary result. They
may therefore contain sensitive content. Store them in an access-controlled
location. The unkeyed hash chain detects ordinary edits that do not recompute
the chain, but it provides neither authenticity nor proof of who approved a run.
