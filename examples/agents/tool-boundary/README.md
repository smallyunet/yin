# Guarded local-tool boundary

This v0.14 demo is the smallest complete Agent-to-tool boundary: untrusted JSON
is decoded into a typed request, an ordered Yin policy restricts the namespace,
the reference host independently authorizes an installed local tool, and the run
is recorded in a hash-chained JSONL trace.

Build Yin and run the read path:

```bash
./mvnw package
./examples/agents/tool-boundary/run.sh
```

Reads are authorized only when `host.json` installs a matching read capability.
Writes additionally require explicit approval:

```bash
mkdir -p examples/agents/tool-boundary/runtime/notes
cp examples/agents/tool-boundary/fixtures/welcome.txt \
  examples/agents/tool-boundary/runtime/notes/welcome.txt
java -jar target/yin-0.15.0.jar --guard \
  examples/agents/tool-boundary/main.yin \
  --input examples/agents/tool-boundary/inputs/write.json \
  --host examples/agents/tool-boundary/host.json \
  --trace examples/agents/tool-boundary/runtime/write.jsonl \
  --approve notes.write
```

Omit `--approve notes.write` to see an `approval-required` result without a
write. The `outside-policy.json` fixture is rejected by the Yin policy before a
tool is authorized. The host also resolves real filesystem paths under its
configured root, so changing the policy alone cannot grant ambient filesystem
access.

Replay a completed result without invoking any tool:

```bash
java -jar target/yin-0.15.0.jar --replay \
  examples/agents/tool-boundary/runtime/write.jsonl
```

Trace files use create-only semantics and contain payload-derived hashes plus
tool output. Treat them as sensitive execution records and place them in an
access-controlled directory outside the repository in real deployments. The
reference host is intentionally local and narrow; it is a demonstrator, not a
general sandbox or production authorization service.
