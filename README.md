# The Yin Programming Language

Yin is a small typed policy language for defining and auditing the boundary
between AI agents and external tools. Policies read from top to bottom, external
authority is injected by a deny-by-default host, expected failures are explicit,
and tool calls carry approval and audit metadata.

Yin is also an experimental programming-language implementation, originally
developed by Yin Wang in 2013–2014 and now built around a hand-written parser,
tree-walking interpreter, and static type checker. It is not yet production
ready. The untouched historical state is preserved by the `legacy-2015` Git tag.

Try the language in the browser at the
[Yin Playground](https://smallyunet.github.io/yin/). Evaluation, type checking,
formatting, and the editable-input Agent and Web3 demos run locally in a Web
Worker; no source code or JSON input is sent to a server.

```yin
(policy review
  ([request ReviewRequest] [-> Decision])
  (when (= request.risk "blocked")
    (Reject :reason "risk policy blocked this request"))
  (when (> request.amount 10000)
    (NeedsInput :question "manual approval is required"))
  (otherwise
    (Approve :reason "within automatic policy")))
```

`policy` rules are checked like ordinary typed functions and lower to the same
core AST. The first matching `when` wins, and every policy must end with an
explicit `otherwise` result.

## Implemented and tested

- ordered typed policies with explicit fallback and first-match evaluation
- concise immutable field access such as `request.risk`
- integers, floats, booleans, strings, and vectors
- exact and homogeneous immutable vectors with higher-order processing
- exhaustive pattern matching over primitives, vectors, records, and unions
- typed `Result` outcomes with exhaustive `Ok` and `Err` handling
- closed tagged variants and first-class `Option` values
- strict typed JSON decoding, deterministic encoding, and Draft 2020-12 schemas
- source-declared capabilities and typed host tools with explicit `Result` outcomes
- deterministic capability manifests, approval enforcement, and tool-call audit events
- string transformation, parsing, program arguments, and controlled text input
- arithmetic, comparison, and boolean primitives
- lexical scopes and first-class closures
- positional and keyword function arguments
- direct and mutual recursion
- records with typed fields and default values
- immutable record field access, including inherited fields
- an experimental type checker
- editor diagnostics and whole-document formatting through LSP

The JUnit integration suite runs every maintained program under `tests/` through
both the interpreter and type checker. The automated suite also covers parser
boundaries, lexical scoping, assignment, Float handling, function calls, record
inheritance, destructuring, unions, structured diagnostics, and architecture
boundaries between runtime values and static types. Yin 0.13 defines these
behaviors normatively rather than relying on historical implementation details.

## Requirements

- JDK 17 or newer
- no system Maven installation is required

## Releases

Versioned executable JARs, Visual Studio Code extension packages, and SHA-256
checksum files are published on the
[GitHub Releases page](https://github.com/smallyunet/yin/releases). Confirm a
downloaded JAR before running it:

```bash
java -jar yin-0.13.0.jar --version
```

Install the matching editor extension from the downloaded VSIX:

```bash
code --install-extension yin-language-support-0.13.0.vsix
```

## Build and test

```bash
./mvnw verify
```

This produces the executable JAR at `target/yin-0.13.0.jar`.

## Run a program

```bash
java -jar target/yin-0.13.0.jar tests/program-usability.yin
```

Run the type checker separately:

```bash
java -cp target/yin-0.13.0.jar \
  org.yinwang.yin.TypeChecker tests/program-usability.yin
```

Run complete example programs:

```bash
java -jar target/yin-0.13.0.jar examples/algorithms/quicksort.yin
java -jar target/yin-0.13.0.jar examples/cli/parse-values.yin 10 bad 32
printf '%s' '{"task":"review","confidence":0.95}' | \
  java -jar target/yin-0.13.0.jar --json examples/agents/structured-agent.yin
java -jar target/yin-0.13.0.jar examples/cli/wc.yin README.md
```

The [typed agent review demo](examples/agents/agent-review/README.md) provides a
complete strict-JSON request, exhaustive decision, and raw-JSON response flow
with maintained fixtures for approval, rejection, user input, and boundary
errors.
The [Web3 transaction guard](examples/web3/transaction-guard/README.md) applies
the same boundary to normalized wallet intents, including simulation,
verification, unlimited-approval, value-limit, chain, and address policies.
The [typed-tool example](examples/agents/typed-tool.yin) declares a host-injected
risk tool, invokes it through a checked contract, and handles both business and
host boundary errors without granting ambient authority.

Inspect every tool capability without executing the program:

```bash
java -jar target/yin-0.13.0.jar --capabilities examples/agents/typed-tool.yin
```

The manifest is deterministic and includes effect, approval, idempotency, and
open-world metadata. Tool implementations and approval decisions remain host
responsibilities; declarations never grant authority by themselves.

## Interactive REPL

Launch the REPL by running the JAR without a program path:

```bash
java -jar target/yin-0.13.0.jar
```

Definitions persist across inputs, balanced multiline forms are supported, and
language errors do not terminate the session. Use `:quit` or `:q` to exit. See
the [REPL guide](docs/repl.md) for its precise behavior and embedding API.

## Format source

Print canonical formatting without changing the file:

```bash
java -jar target/yin-0.13.0.jar --format tests/function1.yin
```

Use `--format --check` in CI or `--format --write` to update one or more files.
The formatter validates supported Yin syntax before changing output and retains
all line comments. See the [Formatter guide](docs/formatter.md).

## Build the browser demo

```bash
./mvnw -Pbrowser -DskipTests package
```

TeaVM writes the generated JavaScript runtime to `site/runtime/`. Serve `site/`
over HTTP for local development. Pushes to `main` build and deploy the same
static directory to GitHub Pages.

## Repository layout

```text
src/main/java/   language implementation
  .../type/      static type representations and primitive signatures
  .../value/     runtime values, closures, constructors, and primitives
src/test/java/   automated integration and regression tests
tests/           maintained runnable Yin programs
examples/        categorized algorithm, CLI, agent, and Web3 examples
experiments/     historical, potentially outdated language experiments
prototype1/      original Racket prototype
emacs/           historical Emacs modes
archive/         inactive implementation fragments
docs/            language, architecture, and roadmap notes
```

See the normative [Language specification](docs/language-specification.md), the
short [Language reference](docs/language-reference.md), the
[ordered-policy guide](docs/policies.md), the
[historical-program classification](docs/historical-programs.md),
[REPL guide](docs/repl.md), [Formatter guide](docs/formatter.md),
[Editor integration](docs/lsp.md),
[Implementation](docs/implementation.md), and [Roadmap](docs/roadmap.md) for the
maintained project boundaries.
The [Agent policy direction](docs/ai-first.md) defines the structured-contract,
capability, policy-runtime, and durable-agent sequence without binding Yin
syntax to one provider protocol.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

Copyright © 2013–2014 Yin Wang and contributors.
