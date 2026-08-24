<p align="center">
  <img src="https://raw.githubusercontent.com/smallyunet/yin/main/editors/vscode/images/icon.png" alt="Yin programming language" width="112">
</p>

# Yin Language Support

Official editor support for the [Yin programming language](https://github.com/smallyunet/yin).

Yin is a typed deterministic language for portable programs and policies across
constrained execution environments. The extension supports the implemented
hosted language today; planned EVM, SVM, RISC-V, and Bitcoin target diagnostics
are not yet available.

[Install from the Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=smallyu.yin-language-support).

## Features

- syntax highlighting for `.yin` files
- live parser diagnostics for unsaved documents
- canonical whole-document formatting
- exact diagnostic ranges and stable Yin diagnostic codes

The extension starts the Yin 0.21.1 Rust language server locally over stdio.
Source code is never sent to a remote service.

## Requirement

Install the `yin` executable from the matching GitHub Release. If it is not on
`PATH`, set `yin.path` to its full path, then reload the VS Code window.

## Formatting

Open a `.yin` file and run **Format Document**. To make Yin the default
formatter for the language, add:

```json
"[yin]": {
  "editor.defaultFormatter": "smallyu.yin-language-support"
}
```

## Current scope

Yin 0.21.1 provides diagnostics and formatting. Hover, completion, and go to
definition are planned for later editor-service releases.
