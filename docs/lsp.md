# Yin editor integration

Yin 0.20 includes a Rust Language Server Protocol implementation and a Visual
Studio Code extension. The extension reports syntax and type errors while a
document is being edited and formats the entire document with Yin's canonical
formatter.

## Visual Studio Code

Install both the platform-specific `yin` executable and the versioned VSIX from the
[GitHub Releases page](https://github.com/smallyunet/yin/releases) and install it
with **Extensions: Install from VSIX...** in Visual Studio Code. Put `yin` on
`PATH`, or set `yin.path` in Visual Studio Code settings to the executable.

The first release intentionally supports only full-document synchronization,
diagnostics, and formatting. Hover, completion, definitions, and references are
future work.

For a file-backed document, diagnostics recursively type-check saved relative
module dependencies. The open document uses its current unsaved snapshot;
imported files use their saved filesystem contents.

## Other editors

Any LSP client can start the native executable in server mode:

```bash
yin --lsp
```

The process reserves standard output for framed JSON-RPC messages. Diagnostics
are published after `textDocument/didOpen` and full-text
`textDocument/didChange` notifications. Formatting is exposed through
`textDocument/formatting` as one whole-document edit.

## Building the extension

From `editors/vscode/`, run:

```bash
npm install
npm run package
```

Packaging validates TypeScript, bundles the client, and creates a universal
VSIX. Platform-specific Yin executables are separate release assets.
