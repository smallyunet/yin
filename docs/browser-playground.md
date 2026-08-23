# Browser playground

The [Yin Playground](https://smallyunet.github.io/yin/) runs the same Rust
parser, checker, interpreter, and formatter as the native CLI. `wasm-bindgen`
packages the library for `wasm32-unknown-unknown`; a dedicated Web Worker keeps
evaluation off the UI thread and the page resets the worker after its timeout.

Source and input remain in the browser. Filesystem tools, MCP subprocesses,
approval stores, and native gateway operations are deliberately unavailable in
Wasm. Their source forms still parse and type-check, and an attempted tool call
returns a structured `ToolError` through the normal Yin boundary.

The Playground has one intentionally narrow browser host capability:
`generate-eth-wallet`. It obtains entropy from Web Crypto inside the Worker,
derives an Ethereum address locally, and returns only the address and public key
through the typed Yin tool boundary. The private key stays outside Yin values,
standard output, and diagnostics; the UI retrieves it through a separate
one-shot Wasm export and keeps it hidden until the user explicitly reveals or
copies it. This is an educational demo and generated addresses must not receive
funds.

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
