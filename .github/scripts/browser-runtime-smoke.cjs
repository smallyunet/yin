const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

vm.runInThisContext(fs.readFileSync("site/runtime/yin.js", "utf8"));

yinReset();
const evaluation = JSON.parse(yinEvaluate(`
  (record Box [value Int])
  (field (Box :value 42) :value)
`));
assert.equal(evaluation.ok, true);
assert.equal(evaluation.value, "42");
assert.equal(evaluation.type, "Int");

const diagnostic = JSON.parse(yinEvaluate("(field 42 :value)"));
assert.equal(diagnostic.ok, false);
assert.equal(diagnostic.diagnostic.code, "YIN0001");

const formatted = JSON.parse(yinFormat("(field   (Box :value 42)   :value)"));
assert.equal(formatted.ok, true);
assert.match(formatted.formatted, /^\(field /);

console.log("Browser runtime smoke test passed");
