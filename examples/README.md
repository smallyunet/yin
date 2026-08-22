# Yin examples

All maintained examples live under this directory and are grouped by purpose:

| Category | Examples | Purpose |
| --- | --- | --- |
| `algorithms/` | `quicksort.yin` | Pure language and collection algorithms |
| `cli/` | `parse-values.yin`, `wc.yin` | Arguments, standard input, and file-oriented CLI programs |
| `modules/` | `main.yin`, `math.yin` | Explicit exports, selective imports, and multi-file type checking |
| `config-validator/` | `main.yin`, `validation.yin` | Real multi-file JSON CLI using Dict, Set, Option, Result, and variants |
| `agents/` | `structured-agent.yin`, `typed-tool.yin`, `agent-review/`, `capability-decision/`, `tool-boundary/`, `action-gateway/` | Optional automation profiles: typed policy, deterministic decisions, guarded tools, MCP, and JSON workflows |
| `web3/` | `transaction-guard/` | Optional ordered safety profile between an AI agent and a wallet host |

Runnable demos include their own README and end-to-end regression coverage.
The configuration validator is the primary general-language example; Agent and
Web3 directories demonstrate optional embedding profiles rather than defining
Yin's product scope.
