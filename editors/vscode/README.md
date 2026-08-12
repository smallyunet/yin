# Yin Language Support

Official editor support for the [Yin programming language](https://github.com/smallyunet/yin).

## Features

- syntax highlighting for `.yin` files
- live parser and type-checker diagnostics for unsaved documents
- canonical whole-document formatting
- exact diagnostic ranges and stable Yin diagnostic codes

The extension includes the Yin 0.15.0 language server and communicates with it
locally over stdio. Source code is never sent to a remote service.

## Requirement

Install Java 17 or newer. If `java` is not on `PATH`, set `yin.java.path` to the
full path of a compatible Java executable, then reload the VS Code window.

## Formatting

Open a `.yin` file and run **Format Document**. To make Yin the default
formatter for the language, add:

```json
"[yin]": {
  "editor.defaultFormatter": "smallyunet.yin-language-support"
}
```

## Current scope

Yin 0.15.0 provides diagnostics and formatting. Hover, completion, and go to
definition are planned for later editor-service releases.
