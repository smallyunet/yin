# Agent Action Gateway

Yin 0.17 adds a generic host boundary for executing source-declared typed tools
through MCP stdio servers. The gateway is a reference security architecture,
not a process sandbox or a hosted authorization service.

## Execution flow

1. Read and type-check the Yin program before starting a server.
2. Parse the action intent as a closed envelope and canonicalize its arguments.
3. Require exact agreement among the source tool declaration, host mapping, and
   intent server, tool, capability, effect, and approval requirement.
4. Run the Yin policy with the canonical intent as standard input.
5. When the policy invokes a tool, compare its encoded typed arguments with the
   canonical intent arguments.
6. For non-read effects, validate approval evidence and atomically consume its
   nonce in the locked durable store before starting the external action.
7. Start the configured subprocess, complete MCP initialization, discover the
   remote tool, and issue `tools/call`.
8. Decode `structuredContent` through the source-declared Yin output contract.
9. Record authorization and result digests in a hash-chained trace.

If the policy blocks the action, no MCP process starts. If authorization fails,
the Yin `invoke` expression receives a typed `ToolError` and no remote call is
sent.

## Action intent

The outer envelope has exactly these fields:

```json
{
  "requestId": "request-123",
  "actor": "user@example.com",
  "agent": "release-agent",
  "server": "github",
  "tool": "create_issue",
  "capability": "github.issue.write",
  "effect": "write",
  "resource": "repo:owner/project",
  "arguments": {}
}
```

`arguments` is generic to the Rust gateway but remains strongly typed by each
Yin program. A GitHub policy can declare `IssueArguments`; a wallet policy can
declare `SwapArguments`. The gateway refuses execution unless the JSON emitted
by `invoke` is exactly the canonical `arguments` object in this envelope.

Normalize protocol-specific identifiers, paths, URLs, addresses, and resource
names before creating the intent. The gateway canonicalizes JSON ordering but
does not guess domain-specific equivalence.

## Host configuration

The host file is closed and versioned:

```json
{
  "version": 1,
  "timeoutMillis": 10000,
  "servers": [
    {
      "name": "github",
      "command": ["your-github-mcp-server"],
      "cwd": ".",
      "tools": [
        {
          "name": "create-issue",
          "remoteName": "create_issue",
          "capability": "github.issue.write",
          "effect": "write",
          "approvalRequired": true
        }
      ]
    }
  ]
}
```

`name` is the local Yin tool declaration; `remoteName` is the MCP tool name.
The subprocess inherits the gateway environment, which is where stdio MCP
servers should obtain credentials. Do not put secrets in the host file.
Relative working directories resolve from the host file's directory.

The client implements newline-delimited JSON-RPC and MCP revision 2025-11-25.
It also accepts the compatible 2025-06-18, 2025-03-26, and 2024-11-05 server
revisions for the implemented tool lifecycle. Every request has the configured
timeout. `tools/list` pagination is followed before invocation, and the remote
tool must actually be advertised.

## Approval evidence

Create an approval request out of band:

```bash
yin --approval-request policy.yin \
  --intent intent.json \
  --host host.json \
  --out approval.json \
  --approved-by user@example.com \
  --expires-in-seconds 300
```

Then execute it once:

```bash
yin --gateway policy.yin \
  --intent intent.json \
  --host host.json \
  --trace trace.jsonl \
  --approval approval.json \
  --nonce-store used-approvals.jsonl
```

Approval binds the exact program hash, host hash, canonical intent hash,
canonical argument hash, request ID, actor, agent, server, tool, capability,
effect, resource, expiration, approver label, and random nonce. The nonce store
uses an exclusive file lock and forces the consumed record to durable storage
before the tool handler starts. A crash after consumption can deny a retry; it
cannot silently repeat the action.

`--approval-request` does not authenticate `approvedBy` or sign the evidence.
A real deployment must place that operation behind an authenticated human
approval service or add signature verification. The requesting agent must not
be allowed to approve its own action.

## Security boundary

- Source tool declarations request authority; they never grant it.
- MCP annotations and server-provided schemas are untrusted discovery data.
- Tool output is decoded against the source-declared Yin contract.
- Read tools may run automatically only when source, host, and intent all agree
  that the effect is `read` and approval is not required.
- Every write or destructive host mapping must require approval.
- The trace stores tool input and output digests, not raw remote results, but
  the final program output remains present for replay.
- The subprocess is not isolated. Use operating-system sandboxing, restricted
  credentials, and least-privilege MCP servers for hostile or third-party code.
- Trace signing, redaction policy, production retention, authenticated approval
  identity, and distributed nonce storage remain deployment responsibilities.

See the [maintained ticket example](../examples/agents/action-gateway/README.md)
for a dependency-free MCP subprocess that performs a visible external action.
