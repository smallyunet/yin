# Yin formatter

Yin 0.4 includes a deterministic formatter for the supported language grammar.
It validates the entire source before producing output, preserves line comments
and string tokens, uses two-space indentation, and targets an 88-column line.

## Commands

Print one formatted file to standard output without changing it:

```bash
java -jar target/yin-0.14.0.jar --format program.yin
```

Check one or more files. The command exits with status 1 and lists files that
would change:

```bash
java -jar target/yin-0.14.0.jar --format --check tests/*.yin
```

Rewrite one or more files in place:

```bash
java -jar target/yin-0.14.0.jar --format --write program.yin
```

Invalid Yin is never rewritten. I/O and syntax diagnostics also produce a
nonzero exit status.

## Canonical style

- indentation is two spaces
- atoms in short forms use one separating space
- long forms break at semantic heads such as `define`, `fun`, `if`, and calls
- line comments remain attached to the preceding expression when safe
- output uses LF newlines and ends with one newline when non-empty
- redundant blank lines and alignment spaces are normalized

Formatting is idempotent: formatting canonical output again produces the same
bytes. The maintained programs under `tests/` are checked for canonical format
by the automated test suite.

## Library API

Use `Formatter.format(sourceName, source)` for in-memory formatting. The virtual
source name appears in any syntax diagnostic. The formatter changes layout
only; interpreter and type-checker behavior must remain unchanged.
