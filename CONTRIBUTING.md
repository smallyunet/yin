# Contributing

Yin is an experimental language implementation. Keep changes small enough that
their semantic effect is visible and testable.

## Development setup

Use JDK 17 or newer and run:

```bash
cargo test --workspace --all-targets
```

## Change guidelines

- Work from `main` and keep each commit focused and independently verifiable.
- Preserve the behavior of programs under `tests/` unless the change explicitly
  updates the documented language semantics.
- Add interpreter, type-checker, and diagnostic coverage for language changes.
- Demonstrate new general-language capabilities in a maintained non-Agent CLI,
  data, configuration, or automation program.
- Keep historical material intact or move it under `archive/` with context.
- Do not add new syntax without updating `docs/language-reference.md`.
- Avoid unrelated formatting or file moves in semantic changes.

Commit messages should state the behavior being changed. Run `cargo test --workspace --all-targets`
before pushing directly to `main`.

## Visual Studio Code extension

The extension under `editors/vscode/` bundles the executable Yin JAR. Run
`npm ci`, then `npm run package` to rebuild the server, type-check and
bundle the client, and produce a VSIX. Keep the extension and Cargo versions in
sync. Marketplace credentials belong only in the repository-root `.env` file.
