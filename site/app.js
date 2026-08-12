const examples = {
  recursion: `(define fact
  (fun ([x Int] [-> Int])
    (if (= x 0) 1 (* x (fact (- x 1))))))

(fact 5)`,
  closures: `(define make-adder
  (fun (base)
    (fun (value) (+ base value))))

(define add-seven (make-adder 7))
(add-seven 35)`,
  records: `(record Point [x Int] [y Int :default 0])
(record NamedPoint (Point) [name String])

(define origin (NamedPoint :name "origin" :x 42))
origin.x`,
  vectors: `(define base [10 20])
(define extended (append base [30 40]))

(print (length extended))
(at extended 3)`,
  programs: `(define values
  (map (split "10 bad 32" " ")
    (fun ([text String] [-> Int])
      (match (parse-int text)
        [(Int value) value]
        [(Bool _) 0]))))

(fold values 0
  (fun ([total Int] [value Int] [-> Int])
    (+ total value)))`,
  results: `(define fetch
  (fun ([available Bool] [-> (Result Int String)])
    (if available (ok 42) (err "unavailable"))))

(match (fetch true)
  [(Ok value) value]
  [(Err message) (seq (print message) 0)])`,
  contracts: `(record Request [task String] [note (Option String)])
(variant Decision
  [Approve [reason String]]
  [NeedsInput [question String]])

(match (decode-json Request "{\\\"task\\\":\\\"review\\\",\\\"note\\\":null}")
  [(Ok request) (encode-json (Approve :reason (field request :task)))]
  [(Err error) (field error :path)])`,
  unions: `(define numeric-label
  (fun ([value (U Int Float)] [-> String])
    (if (= value 0) "zero" "non-zero")))

(numeric-label 1.5)`,
  keywords: `(define counter 0)
(define next
  (fun ()
    (set! counter (+ counter 1))
    counter))
(define pair
  (fun (left right) [left right]))

(pair :right (next) :left (next))`,
  quicksort: `(define quicksort
  (fun ([values (Vector Int)] [-> (Vector Int)])
    (if (= (length values) 0)
      values
      (seq
        (define pivot (at values 0))
        (define rest (slice values 1 (length values)))
        (define lower (filter rest (fun ([value Int] [-> Bool]) (<= value pivot))))
        (define higher (filter rest (fun ([value Int] [-> Bool]) (> value pivot))))
        (append (quicksort lower) (append [pivot] (quicksort higher)))))))
(quicksort [9 3 7 1 8 2 5 4 6])`,
  structuredAgent: `(record AgentRequest
  [task String]
  [confidence Float]
  [context (Option String) :default none])
(variant Decision
  [Approve [reason String]]
  [Reject [reason String]]
  [NeedsInput [question String]])
(define decide
  (fun ([request AgentRequest] [-> Decision])
    (if (>= (field request :confidence) 0.9)
      (Approve :reason "high confidence")
      (NeedsInput :question "provide more context"))))
(match (decode-json AgentRequest (read-all))
  [(Ok request) (encode-json (decide request))]
  [(Err error) (encode-json (Reject :reason (field error :message)))])`,
  agentReview: `(record ReviewRequest
  [requestId String]
  [action String]
  [amount Int]
  [risk String]
  [context (Option String) :default none])
(variant Decision
  [Approve [requestId String] [reason String]]
  [Reject [requestId String] [reason String]]
  [NeedsInput [requestId String] [question String]])
(define approve
  (fun ([request ReviewRequest] [reason String] [-> Decision])
    (Approve :requestId request.requestId :reason reason)))
(define reject
  (fun ([request ReviewRequest] [reason String] [-> Decision])
    (Reject :requestId request.requestId :reason reason)))
(define needs-input
  (fun ([request ReviewRequest] [question String] [-> Decision])
    (NeedsInput :requestId request.requestId :question question)))
(policy decide
  ([request ReviewRequest] [-> Decision])
  (when (= request.risk "blocked")
    (reject request "risk policy blocked this request"))
  (when (> request.amount 10000)
    (needs-input request "manual approval context is required for a high amount"))
  (when (and (= request.action "transfer") (= request.context none))
    (needs-input request "provide transfer approval context"))
  (when (= request.action "transfer")
    (approve request "transfer context accepted"))
  (otherwise
    (approve request "within automatic policy")))
(match (decode-json ReviewRequest (read-all))
  [(Ok request) (encode-json (decide request))]
  [(Err error)
    (encode-json
      (Reject
        :requestId
        "invalid-request"
        :reason
        (concat
          (concat error.code (concat " at " error.path))
          (concat ": " error.message))))])`,
  typedTool: `(record RiskRequest [amount Int])
(record RiskAssessment [score Int] [reason String])
(variant RiskFailure
  [Offline [message String]]
  [Rejected [message String]])
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
      [(ToolError _ _ message) message])])`,
  web3Guard: `(record TransactionIntent
  [requestId String]
  [chainId Int]
  [kind String]
  [to String]
  [asset String]
  [rawAmount String]
  [valueUsd Float]
  [unlimitedApproval Bool]
  [verifiedContract Bool]
  [simulationSucceeded Bool]
  [context (Option String) :default none])
(variant TransactionDecision
  [Approve [requestId String] [reason String]]
  [Reject [requestId String] [code String] [reason String]]
  [NeedsApproval [requestId String] [risk String] [reason String]])
(define supported-chain
  (fun ([chainId Int] [-> Bool])
    (or (= chainId 1) (= chainId 137))))
(define valid-address
  (fun ([address String] [-> Bool])
    (if (= (string-length address) 42) (= (substring address 0 2) "0x") false)))
(define reject
  (fun ([tx TransactionIntent] [code String] [reason String] [-> TransactionDecision])
    (Reject :requestId tx.requestId :code code :reason reason)))
(define require-approval
  (fun ([tx TransactionIntent] [risk String] [reason String] [-> TransactionDecision])
    (NeedsApproval :requestId tx.requestId :risk risk :reason reason)))
(policy review-transaction
  ([tx TransactionIntent] [-> TransactionDecision])
  (when (not (valid-address tx.to))
    (reject tx "invalid-address" "target must be a 20-byte 0x address"))
  (when (not (supported-chain tx.chainId))
    (reject tx "unsupported-chain" "chain is outside the configured policy"))
  (when (not tx.simulationSucceeded)
    (reject tx "simulation-failed" "transaction simulation did not succeed"))
  (when (not tx.verifiedContract)
    (reject tx "unverified-contract" "target contract is not verified"))
  (when (= tx.kind "contract-upgrade")
    (require-approval tx "critical" "contract upgrades require human approval"))
  (when tx.unlimitedApproval
    (require-approval tx "high" "unlimited token approval requested"))
  (when (> tx.valueUsd 1000.0)
    (require-approval tx "medium" "transaction exceeds the automatic USD limit"))
  (otherwise
    (Approve :requestId tx.requestId :reason "simulation and policy checks passed")))
(match (decode-json TransactionIntent (read-all))
  [(Ok transaction) (encode-json (review-transaction transaction))]
  [(Err error)
    (encode-json
      (Reject
        :requestId
        "invalid-request"
        :code
        error.code
        :reason
        (concat (concat error.path ": ") error.message)))])`
};

const exampleInputs = {
  structuredAgent: `{"task":"review","confidence":0.95}`,
  agentReview: `{"requestId":"req-approve","action":"review","amount":800,"risk":"low"}`,
  web3Guard: `{"requestId":"tx-approve","chainId":1,"kind":"erc20-transfer","to":"0x1111111111111111111111111111111111111111","asset":"USDC","rawAmount":"115792089237316195423570985008687907853269984665640564039457584007913129639935","valueUsd":250,"unlimitedApproval":false,"verifiedContract":true,"simulationSucceeded":true}`
};

const exampleFiles = {
  quicksort: "examples/algorithms/quicksort.yin",
  structuredAgent: "examples/agents/structured-agent.yin",
  agentReview: "examples/agents/agent-review/main.yin",
  typedTool: "examples/agents/typed-tool.yin",
  web3Guard: "examples/web3/transaction-guard/main.yin"
};

const editor = document.querySelector("#code-editor");
const editorPanel = document.querySelector(".editor-panel");
const editorTitle = document.querySelector("#editor-title");
const inputPanel = document.querySelector("#input-panel");
const inputEditor = document.querySelector("#input-editor");
const lineNumbers = document.querySelector("#line-numbers");
const runButton = document.querySelector("#run-button");
const formatButton = document.querySelector("#format-button");
const resetButton = document.querySelector("#reset-button");
const copyButton = document.querySelector("#copy-button");
const runtimeLoading = document.querySelector("#runtime-loading");
const resultView = document.querySelector("#result-view");
const statusChip = document.querySelector("#status-chip");
const duration = document.querySelector("#duration");
const resultValue = document.querySelector("#result-value");
const resultType = document.querySelector("#result-type");
const stdoutBlock = document.querySelector("#stdout-block");
const stdoutValue = document.querySelector("#stdout-value");
const diagnostic = document.querySelector("#diagnostic");
const diagnosticCode = document.querySelector("#diagnostic-code");
const diagnosticMessage = document.querySelector("#diagnostic-message");
const diagnosticLocation = document.querySelector("#diagnostic-location");
const runtimeDot = document.querySelector("#runtime-dot");
const runtimeStatus = document.querySelector("#runtime-status");
const cursorPosition = document.querySelector("#cursor-position");

let worker;
let workerReady = false;
let requestId = 0;
let pendingRequest = null;
let lastDiagnostic = null;
let activeExample = "agentReview";

function createWorker() {
  workerReady = false;
  runtimeDot.classList.remove("is-ready");
  runtimeStatus.textContent = "Initializing";
  runButton.disabled = true;
  formatButton.disabled = true;
  worker = new Worker("worker.js");
  worker.addEventListener("message", handleWorkerMessage);
  worker.addEventListener("error", () => {
    showRuntimeFailure("The browser runtime could not be loaded. Refresh the page to try again.");
  });
}

function handleWorkerMessage(event) {
  if (event.data.kind === "ready") {
    workerReady = true;
    runtimeLoading.classList.add("is-hidden");
    resultView.classList.remove("is-hidden");
    runtimeDot.classList.add("is-ready");
    runtimeStatus.textContent = "Runtime ready";
    runButton.disabled = false;
    formatButton.disabled = false;
    showIdle();
    return;
  }
  if (!pendingRequest || event.data.id !== pendingRequest.id) return;
  const elapsed = Math.max(1, Math.round(performance.now() - pendingRequest.startedAt));
  clearTimeout(pendingRequest.timeout);
  const action = pendingRequest.action;
  pendingRequest = null;
  setBusy(false);
  if (action === "format") {
    handleFormatResult(event.data.payload);
  } else if (action === "reset") {
    showIdle();
  } else {
    showResult(event.data.payload, elapsed);
  }
}

function request(action, source = "", input) {
  if (!workerReady || pendingRequest) return;
  setBusy(true);
  const id = ++requestId;
  const startedAt = performance.now();
  const timeout = setTimeout(() => {
    pendingRequest = null;
    worker.terminate();
    setBusy(false);
    showTimeout();
    createWorker();
  }, 1500);
  pendingRequest = { id, action, startedAt, timeout };
  worker.postMessage({ id, action, source, input });
}

function setBusy(busy) {
  runButton.disabled = busy || !workerReady;
  formatButton.disabled = busy || !workerReady;
  runButton.lastChild.textContent = busy ? " Running" : " Run";
}

function showIdle() {
  statusChip.className = "status-chip status-success";
  statusChip.innerHTML = "<i></i>Ready";
  duration.textContent = "⌘ + Enter";
  resultValue.textContent = "Run the program";
  resultType.textContent = "—";
  stdoutBlock.classList.add("is-hidden");
  diagnostic.classList.add("is-hidden");
}

function showResult(payload, elapsed) {
  duration.textContent = `${elapsed} ms`;
  lastDiagnostic = payload.diagnostic || null;
  const output = payload.output || [];
  stdoutBlock.classList.toggle("is-hidden", output.length === 0);
  stdoutValue.textContent = output.join("\n");
  if (payload.ok) {
    statusChip.className = "status-chip status-success";
    statusChip.innerHTML = "<i></i>Executed";
    resultValue.textContent = payload.value;
    resultType.textContent = payload.type;
    diagnostic.classList.add("is-hidden");
  } else {
    statusChip.className = "status-chip status-error";
    statusChip.innerHTML = "<i></i>Failed";
    resultValue.textContent = "No value";
    resultType.textContent = "—";
    diagnosticCode.textContent = payload.diagnostic?.code || "YIN9001";
    diagnosticMessage.textContent = payload.diagnostic?.message || "Unknown runtime error";
    diagnosticLocation.textContent = payload.diagnostic?.line
      ? `Line ${payload.diagnostic.line}, column ${payload.diagnostic.column}`
      : "Runtime";
    diagnostic.classList.remove("is-hidden");
  }
}

function showTimeout() {
  runtimeLoading.classList.add("is-hidden");
  resultView.classList.remove("is-hidden");
  showResult({
    ok: false,
    output: [],
    diagnostic: { code: "YIN9002", message: "The program exceeded the 1.5-second limit. The runtime was safely reset." }
  }, 1500);
}

function showRuntimeFailure(message) {
  runtimeLoading.classList.add("is-hidden");
  resultView.classList.remove("is-hidden");
  showResult({ ok: false, output: [], diagnostic: { code: "YIN9001", message } }, 0);
  runtimeStatus.textContent = "Runtime unavailable";
}

function handleFormatResult(payload) {
  if (payload.ok) {
    editor.value = payload.formatted;
    saveEditor();
    updateEditorChrome();
    statusChip.className = "status-chip status-success";
    statusChip.innerHTML = "<i></i>Formatted";
    resultValue.textContent = "Canonical source";
    resultType.textContent = "Yin 0.13";
    diagnostic.classList.add("is-hidden");
  } else {
    showResult(payload, 0);
  }
}

function loadExample(name, persist = true) {
  if (!Object.hasOwn(examples, name)) return;
  activeExample = name;
  editor.value = examples[name];
  inputEditor.value = exampleInputs[name] || "";
  const acceptsInput = Object.hasOwn(exampleInputs, name);
  inputPanel.classList.toggle("is-hidden", !acceptsInput);
  editorPanel.classList.toggle("has-input", acceptsInput);
  editorTitle.textContent = exampleFiles[name] || "main.yin";
  document.querySelectorAll(".example-tab").forEach((tab) => {
    const active = tab.dataset.example === name;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  });
  if (persist) localStorage.setItem("yin-playground-example", name);
  saveEditor();
  saveInput();
  updateEditorChrome();
  editor.focus();
  if (workerReady && !pendingRequest) request("reset");
}

function updateEditorChrome() {
  const lines = editor.value.split("\n").length;
  lineNumbers.textContent = Array.from({ length: lines }, (_, index) => index + 1).join("\n");
  lineNumbers.scrollTop = editor.scrollTop;
  const beforeCursor = editor.value.slice(0, editor.selectionStart);
  const line = beforeCursor.split("\n").length;
  const column = beforeCursor.length - beforeCursor.lastIndexOf("\n");
  cursorPosition.textContent = `Ln ${line}, Col ${column}`;
}

function saveEditor() {
  localStorage.setItem("yin-playground-source", editor.value);
}

function saveInput() {
  localStorage.setItem("yin-playground-input", inputEditor.value);
}

function restoreExampleChrome(name) {
  activeExample = Object.hasOwn(examples, name) ? name : "agentReview";
  const acceptsInput = Object.hasOwn(exampleInputs, activeExample);
  inputPanel.classList.toggle("is-hidden", !acceptsInput);
  editorPanel.classList.toggle("has-input", acceptsInput);
  editorTitle.textContent = exampleFiles[activeExample] || "main.yin";
  document.querySelectorAll(".example-tab").forEach((tab) => {
    const active = tab.dataset.example === activeExample;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  });
}

function insertTab(event) {
  if (event.key !== "Tab") return;
  event.preventDefault();
  const start = editor.selectionStart;
  const end = editor.selectionEnd;
  editor.setRangeText("  ", start, end, "end");
  saveEditor();
  updateEditorChrome();
}

document.querySelectorAll(".example-tab").forEach((tab) => {
  tab.addEventListener("click", () => loadExample(tab.dataset.example));
});
runButton.addEventListener("click", () => request(
  "run",
  editor.value,
  Object.hasOwn(exampleInputs, activeExample) ? inputEditor.value : undefined
));
formatButton.addEventListener("click", () => request("format", editor.value));
resetButton.addEventListener("click", () => {
  if (!pendingRequest) request("reset");
});
copyButton.addEventListener("click", async () => {
  await navigator.clipboard.writeText(editor.value);
  copyButton.setAttribute("title", "Copied");
  setTimeout(() => copyButton.setAttribute("title", "Copy code"), 1200);
});
diagnostic.addEventListener("click", () => {
  if (!lastDiagnostic) return;
  editor.focus();
  if (Number.isInteger(lastDiagnostic.start)) {
    editor.setSelectionRange(lastDiagnostic.start, Math.max(lastDiagnostic.start + 1, lastDiagnostic.end || 0));
  }
  updateEditorChrome();
});
editor.addEventListener("input", () => { saveEditor(); updateEditorChrome(); });
inputEditor.addEventListener("input", saveInput);
editor.addEventListener("scroll", () => { lineNumbers.scrollTop = editor.scrollTop; });
editor.addEventListener("click", updateEditorChrome);
editor.addEventListener("keyup", updateEditorChrome);
editor.addEventListener("keydown", (event) => {
  insertTab(event);
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
    event.preventDefault();
    request(
      "run",
      editor.value,
      Object.hasOwn(exampleInputs, activeExample) ? inputEditor.value : undefined
    );
  }
});

const savedSource = localStorage.getItem("yin-playground-source");
const savedInput = localStorage.getItem("yin-playground-input");
const savedExample = localStorage.getItem("yin-playground-example") || "agentReview";
if (savedSource) {
  editor.value = savedSource;
  inputEditor.value = savedInput || exampleInputs[savedExample] || "";
  restoreExampleChrome(savedExample);
  updateEditorChrome();
} else {
  loadExample("agentReview", false);
}
createWorker();
