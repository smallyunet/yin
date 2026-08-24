# Yin examples

All maintained examples live under this directory and are grouped by purpose:

| Category | Examples | Purpose |
| --- | --- | --- |
| `algorithms/` | `quicksort.yin` | Pure language and collection algorithms |
| `cli/` | `parse-values.yin`, `wc.yin` | Arguments, standard input, and file-oriented CLI programs |
| `modules/` | `main.yin`, `math.yin` | Explicit exports, selective imports, and multi-file type checking |
| `config-validator/` | `main.yin`, `validation.yin` | Real multi-file JSON CLI using Dict, Set, Option, Result, and variants |
| `agents/` | `structured-agent.yin`, `typed-tool.yin`, `agent-review/`, `capability-decision/`, `tool-boundary/`, `action-gateway/` | AI application profiles: typed policy, deterministic decisions, guarded tools, MCP, and JSON workflows |
| `web3/` | `transaction-guard/`, `eth-wallet/` | Hosted transaction policy and browser-only educational wallet examples; these are not EVM or SVM backends |

Runnable demos include their own README and end-to-end regression coverage.
The configuration validator is the primary hosted general-language example.
Agent and Web3 directories demonstrate application and embedding profiles rather
than defining Yin's identity or proving support for a planned compiler target.
Future target examples must state the exact profile, artifact, runtime, and
verification boundary they exercise.
