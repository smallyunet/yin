# Yin examples

All maintained examples live under this directory and are grouped by purpose:

| Category | Examples | Purpose |
| --- | --- | --- |
| `algorithms/` | `quicksort.yin` | Pure language and collection algorithms |
| `cli/` | `parse-values.yin`, `wc.yin` | Arguments, standard input, and file-oriented CLI programs |
| `agents/` | `structured-agent.yin`, `typed-tool.yin`, `agent-review/`, `capability-decision/`, `tool-boundary/` | Ordered typed policy, deterministic capability decisions, guarded local tools, and JSON workflows |
| `web3/` | `transaction-guard/` | Ordered safety policy between an AI agent and a wallet host |

Runnable demos include their own README, input fixtures, shell entry point, and
end-to-end regression coverage. Algorithm and CLI examples remain single Yin
files so their core behavior stays easy to inspect.
