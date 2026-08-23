const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

vm.runInThisContext(fs.readFileSync("site/runtime/yin.js", "utf8"));

(async () => {
  await wasm_bindgen({ module_or_path: fs.readFileSync("site/runtime/yin_bg.wasm") });
  const evaluate = (source, input = "") =>
    JSON.parse(wasm_bindgen.evaluate(source, input, "[]"));

  const evaluation = evaluate(`
    (record Box [value Int])
    (field (Box :value 42) :value)
  `);
  assert.equal(evaluation.ok, true);
  assert.equal(evaluation.value, "42");

  const orderedPolicy = evaluate(`
    (record Request [risk String] [amount Int])
    (policy decide
      ([request Request] [-> String])
      (when (= request.risk "blocked") "reject")
      (when (> request.amount 1000) "approval")
      (otherwise "approve"))
    (decide (Request :risk "low" :amount 2500))
  `);
  assert.equal(orderedPolicy.ok, true);
  assert.equal(orderedPolicy.value, '"approval"');

  const diagnostic = evaluate("(field 42 :value)");
  assert.equal(diagnostic.ok, false);
  assert.equal(diagnostic.error.code, "YIN0001");

  const formatted = JSON.parse(wasm_bindgen.format("(field (Box :value 42) :value)"));
  assert.equal(formatted.ok, true);
  assert.match(formatted.source, /^\(field /);

  const programmable = evaluate(`
    (define values
      (map (split (read-all) " ")
        (fun ([text String] [-> Int])
          (match (parse-int text)
            [(Int value) value]
            [(Bool _) 0]))))
    (fold values 0
      (fun ([total Int] [value Int] [-> Int]) (+ total value)))
  `, "20 22");
  assert.equal(programmable.ok, true);
  assert.equal(programmable.value, "42");

  const structuredContract = evaluate(`
    (record Request [task String] [note (Option String)])
    (variant Decision [Approve [reason String]] [NeedsInput [question String]])
    (match (decode-json Request "{\\"task\\":\\"review\\",\\"note\\":null}")
      [(Ok request) (encode-json (Approve :reason (field request :task)))]
      [(Err error) (err error)])
  `);
  assert.equal(structuredContract.ok, true);
  assert.match(structuredContract.value, /Approve/);

  console.log("Rust/Wasm browser runtime smoke test passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
