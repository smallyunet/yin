# Yin editor integration

Yin 0.8 includes a minimal Language Server Protocol implementation and a Visual
Studio Code extension. The extension reports syntax and type errors while a
document is being edited and formats the entire document with Yin's canonical
formatter.

## Visual Studio Code

The Marketplace extension bundles the executable Yin server, so a separate Yin
installation is not required. A Java 17 or newer runtime must be available on
`PATH`. If it is installed elsewhere, set `yin.java.path` in Visual Studio Code
settings to the Java executable.

The first release intentionally supports only full-document synchronization,
diagnostics, and formatting. Hover, completion, definitions, and references are
future work.

## Other editors

Any LSP client can start the executable JAR in server mode:

```bash
java -jar yin-0.8.0.jar --lsp
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

Packaging rebuilds the root Maven project, copies the version-matched executable
JAR into the extension, validates TypeScript, bundles the client, and creates a
VSIX suitable for local installation.
