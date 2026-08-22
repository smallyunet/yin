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

const orderedPolicy = JSON.parse(yinEvaluate(`
  (record Request [risk String] [amount Int])
  (policy decide
    ([request Request] [-> String])
    (when (= request.risk "blocked")
      "reject")
    (when (> request.amount 1000)
      "approval")
    (otherwise
      "approve"))
  (decide (Request :risk "low" :amount 2500))
`));
assert.equal(orderedPolicy.ok, true);
assert.equal(orderedPolicy.value, '"approval"');
assert.equal(orderedPolicy.type, "String");

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

const immutableCollections = JSON.parse(yinEvaluate(`
  (define original (dict "name" "yin"))
  (define updated (dict/put original "version" "0.19"))
  [(dict/get original "version")
   (dict/get updated "version")
   (set/values (set "cli" "data" "cli"))]
`));
assert.equal(immutableCollections.ok, true);
assert.equal(immutableCollections.value,
  '[none (some "0.19") ["cli" "data"]]');

const structuredContract = JSON.parse(yinEvaluate(`
  (record Request [task String] [note (Option String)])
  (variant Decision [Approve [reason String]] [NeedsInput [question String]])
  (match (decode-json Request "{\\\"task\\\":\\\"review\\\",\\\"note\\\":null}")
    [(Ok request) (encode-json (Approve :reason (field request :task)))]
    [(Err error) (err error)])
`));
assert.equal(structuredContract.ok, true);
assert.equal(structuredContract.value,
  '(ok "{\\\"tag\\\":\\\"Approve\\\",\\\"reason\\\":\\\"review\\\"}")');

const typedTool = JSON.parse(yinEvaluate(`
  (record RiskRequest [amount Int])
  (record RiskAssessment [score Int] [reason String])
  (variant RiskFailure [Offline [message String]] [Rejected [message String]])
  (tool assess-risk RiskRequest RiskAssessment RiskFailure
    :capability "risk.read"
    :effect :read
    :approval false
    :idempotent true
    :open-world false)
  (match (invoke assess-risk (RiskRequest :amount 42))
    [(Ok assessment) (field assessment :reason)]
    [(Err error)
      (match error
        [(Offline message) message]
        [(Rejected message) message]
        [(ToolError _ _ message) message])])
`));
assert.equal(typedTool.ok, true);
assert.equal(typedTool.value, '"browser host policy accepted"');
assert.equal(typedTool.type, "String");

console.log("Browser runtime smoke test passed");
