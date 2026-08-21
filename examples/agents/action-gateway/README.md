# Agent Action Gateway

This example connects a typed Yin policy to a real MCP stdio subprocess. The
gateway completes MCP initialization, discovers `create_ticket`, enforces a
request-bound approval, invokes the tool, records a hash-chained trace, and
replays the final result without invoking the tool again.

Build Yin and run the complete flow:

```bash
./mvnw verify
examples/agents/action-gateway/run.sh
```

The demo MCP server appends its external action to
`runtime/tickets.jsonl`. The approval is bound to the exact program, host
configuration, canonical action intent, canonical tool arguments, actor,
agent, server, tool, capability, effect, resource, expiry, and nonce. The
nonce store is locked and durably updated before the remote action, so the
same approval cannot authorize a second call.

`--approval-request` creates unsigned approval evidence. In a real host, an
authenticated human approval service must create or attest that file; allowing
the requesting agent to generate its own approval defeats the boundary. MCP
server annotations are discovery hints and never grant Yin authority.

Replace `command`, `cwd`, and the tool mapping in `host.json` to connect another
newline-delimited stdio MCP server. Keep the source-level `tool` contract and
the host mapping aligned: the gateway rejects mismatched capability, effect,
and approval metadata before execution.
