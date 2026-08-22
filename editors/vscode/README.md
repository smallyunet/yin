# Yin Language Support

Official editor support for the [Yin programming language](https://github.com/smallyunet/yin).

## Features

- syntax highlighting for `.yin` files
- live parser diagnostics for unsaved documents
- canonical whole-document formatting
- exact diagnostic ranges and stable Yin diagnostic codes

The extension starts the Yin 0.20.0 Rust language server locally over stdio.
Source code is never sent to a remote service.

## Requirement

Install the `yin` executable from the matching GitHub Release. If it is not on
`PATH`, set `yin.path` to its full path, then reload the VS Code window.

## Formatting

Open a `.yin` file and run **Format Document**. To make Yin the default
formatter for the language, add:

```json
"[yin]": {
  "editor.defaultFormatter": "smallyunet.yin-language-support"
}
```

## Current scope

Yin 0.20.0 provides diagnostics and formatting. Hover, completion, and go to
definition are planned for later editor-service releases.
