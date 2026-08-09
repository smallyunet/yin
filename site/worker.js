importScripts("runtime/yin.js");

self.postMessage({ kind: "ready" });

self.addEventListener("message", (event) => {
  const { id, action, source, input } = event.data;
  try {
    if (action === "run") {
      if (typeof input === "string") yinSetInput(input);
      self.postMessage({ id, kind: "result", payload: JSON.parse(yinEvaluate(source)) });
    } else if (action === "format") {
      self.postMessage({ id, kind: "result", payload: JSON.parse(yinFormat(source)) });
    } else if (action === "reset") {
      yinReset();
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
