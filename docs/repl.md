# Yin REPL

The Yin 0.4 REPL provides a persistent interactive environment over the same
parser, type checker, and interpreter used for source files.

## Starting a session

After `./mvnw package`, either command starts the REPL:

```bash
java -jar target/yin-0.6.0-SNAPSHOT.jar
java -jar target/yin-0.6.0-SNAPSHOT.jar --repl
```

When attached to a terminal it displays `yin> ` for a new submission and
`...> ` while waiting for a closing parenthesis or bracket. Prompts are omitted
when input is piped. Use `:quit`, `:q`, or end-of-file to exit.

## Session behavior

Each complete submission is parsed and type-checked before it is interpreted.
Definitions, assignments, functions, and records remain available to later
submissions. A later submission may redefine a top-level name, which is useful
while experimenting interactively. Redefinition creates a new lexical layer:
later expressions see the new binding, while existing closures retain the
environment captured when they were created.

Non-`void` results are printed. Syntax, type, and runtime diagnostics are
printed without terminating the session. If piped input ends in an unclosed
form, the REPL reports a `YIN1001` syntax diagnostic before exiting.

Runtime effects that occur before a runtime error are not rolled back. Static
errors do not execute the submitted runtime expression.

## Embedding

`ReplSession` is the non-terminal API:

```java
ReplSession session = new ReplSession();
session.evaluate("(define answer 40)");
ReplSession.Evaluation result = session.evaluate("(+ answer 2)");
```

An `Evaluation` contains both the runtime `Value` and inferred `YinType`.
`Parser.parseSource(sourceName, source)` is available when only in-memory
parsing is needed; its virtual source name is retained in diagnostics.

Command history, completion, formatter integration, and editor services are
not implemented yet.
