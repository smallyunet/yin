# Contributing

Yin is an experimental language implementation. Keep changes small enough that
their semantic effect is visible and testable.

## Development setup

Use JDK 17 or newer and run:

```bash
./mvnw verify
```

## Change guidelines

- Work from `main` and keep each commit focused and independently verifiable.
- Preserve the behavior of programs under `tests/` unless the change explicitly
  updates the documented language semantics.
- Add interpreter, type-checker, and diagnostic coverage for language changes.
- Keep historical material intact or move it under `archive/` with context.
- Do not add new syntax without updating `docs/language-reference.md`.
- Avoid unrelated formatting or file moves in semantic changes.

Commit messages should state the behavior being changed. Run `./mvnw verify`
before pushing directly to `main`.
