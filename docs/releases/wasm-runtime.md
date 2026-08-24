# Yin browser runtime

This bundle contains the browser-ready Yin Rust/Wasm runtime:

- `yin.js`: the `wasm-bindgen` JavaScript loader;
- `yin_bg.wasm`: the compiled runtime;
- `LICENSE`: Yin's license.

Serve the files over HTTP(S); browsers generally cannot instantiate the module
from a `file://` URL. The bundle uses `wasm-bindgen`'s `no-modules` target and
installs a global `wasm_bindgen` function.

```html
<script src="yin.js"></script>
<script>
  const yinReady = wasm_bindgen({ module_or_path: "yin_bg.wasm" });

  async function runYin(source, input = "") {
    await yinReady;
    return JSON.parse(wasm_bindgen.evaluate(source, input, "[]"));
  }
</script>
```

`wasm_bindgen.format(source)` returns a JSON string containing either formatted
source or a structured diagnostic. The runtime has no filesystem, subprocess,
MCP, or native gateway authority. See the repository's
`docs/browser-playground.md` and `site/worker.js` for the maintained Worker
integration and message boundary.
