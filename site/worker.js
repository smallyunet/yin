importScripts("runtime/yin.js");

const runtimeReady = wasm_bindgen({ module_or_path: "runtime/yin_bg.wasm" }).then(() => {
  self.postMessage({ kind: "ready" });
});

self.addEventListener("message", async (event) => {
  const { id, action, source, input } = event.data;
  try {
    await runtimeReady;
    if (action === "run") {
      const result = JSON.parse(wasm_bindgen.evaluate(source, input || "", "[]"));
      self.postMessage({
        id,
        kind: "result",
        payload: result.ok
          ? { ok: true, value: result.value, type: "Runtime value", output: result.output || [] }
          : { ok: false, output: [], diagnostic: result.error }
      });
    } else if (action === "format") {
      const result = JSON.parse(wasm_bindgen.format(source));
      self.postMessage({
        id,
        kind: "result",
        payload: result.ok
          ? { ok: true, formatted: result.source }
          : { ok: false, diagnostic: result.error }
      });
    } else if (action === "reset") {
      self.postMessage({ id, kind: "result", payload: { ok: true } });
    }
  } catch (error) {
    self.postMessage({
      id,
      kind: "result",
      payload: {
        ok: false,
        output: [],
        diagnostic: { code: "YIN9001", message: error?.message || "Browser runtime failed" }
      }
    });
  }
});
