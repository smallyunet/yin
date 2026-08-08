# Contributing

Yin is an experimental language implementation. Keep changes small enough that
their semantic effect is visible and testable.

## Development setup

Use JDK 17 or newer and run:

```bash
./mvnw verify
```

## Change guidelines

- Start feature and fix branches from `main`.
- Preserve the behavior of programs under `tests/` unless the change explicitly
  updates the documented language semantics.
- Add interpreter, type-checker, and diagnostic coverage for language changes.
- Keep historical material intact or move it under `archive/` with context.
- Do not add new syntax without updating `docs/language-reference.md`.
- Avoid unrelated formatting or file moves in semantic changes.

Pull requests should explain the behavior being changed, why it is changing,
and which commands were used for verification.
