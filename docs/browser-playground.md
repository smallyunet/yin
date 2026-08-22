# Browser playground

The [Yin Playground](https://smallyunet.github.io/yin/) runs the same Rust
parser, checker, interpreter, and formatter as the native CLI. `wasm-bindgen`
packages the library for `wasm32-unknown-unknown`; a dedicated Web Worker keeps
evaluation off the UI thread and the page resets the worker after its timeout.

Source and input remain in the browser. Filesystem tools, MCP subprocesses,
approval stores, and native gateway operations are deliberately unavailable in
Wasm. Their source forms still parse and type-check, and an attempted tool call
returns a structured `ToolError` through the normal Yin boundary.

Build the runtime:

```bash
rustup target add wasm32-unknown-unknown
cargo install wasm-bindgen-cli --version 0.2.127 --locked
cargo build --release --target wasm32-unknown-unknown --lib
wasm-bindgen target/wasm32-unknown-unknown/release/yin.wasm \
  --out-dir site/runtime --out-name yin --target no-modules --no-typescript
node .github/scripts/browser-runtime-smoke.cjs
```

The generated JavaScript loader and `.wasm` file live in `site/runtime/` and are
not committed. GitHub Pages builds them from source for every `main` deployment.
`site/worker.js` exposes run, format, and reset messages to the static UI.
