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

yinSetInput("20 22");
const programmable = JSON.parse(yinEvaluate(`
  (define values
    (map (split (read-all) " ")
      (fun ([text String] [-> Int])
        (match (parse-int text)
          [(Int value) value]
          [(Bool _) 0]))))
  (fold values 0
    (fun ([total Int] [value Int] [-> Int]) (+ total value)))
`));
assert.equal(programmable.ok, true);
assert.equal(programmable.value, "42");

const explicitOutcome = JSON.parse(yinEvaluate(`
  (define fetch
    (fun ([available Bool] [-> (Result Int String)])
      (if available (ok 42) (err "unavailable"))))
  (match (fetch false)
    [(Ok value) value]
    [(Err _) 0])
`));
assert.equal(explicitOutcome.ok, true);
assert.equal(explicitOutcome.value, "0");
assert.equal(explicitOutcome.type, "Int");

console.log("Browser runtime smoke test passed");
