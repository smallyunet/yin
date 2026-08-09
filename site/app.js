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
(field origin :x)`,
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

(pair :right (next) :left (next))`
};

const editor = document.querySelector("#code-editor");
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

function request(action, source = "") {
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
  worker.postMessage({ id, action, source });
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
    resultType.textContent = "Yin 0.10";
    diagnostic.classList.add("is-hidden");
  } else {
    showResult(payload, 0);
  }
}

function loadExample(name, persist = true) {
  editor.value = examples[name];
  document.querySelectorAll(".example-tab").forEach((tab) => {
    const active = tab.dataset.example === name;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  });
  if (persist) localStorage.setItem("yin-playground-example", name);
  saveEditor();
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
runButton.addEventListener("click", () => request("run", editor.value));
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
editor.addEventListener("scroll", () => { lineNumbers.scrollTop = editor.scrollTop; });
editor.addEventListener("click", updateEditorChrome);
editor.addEventListener("keyup", updateEditorChrome);
editor.addEventListener("keydown", (event) => {
  insertTab(event);
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
    event.preventDefault();
    request("run", editor.value);
  }
});

const savedSource = localStorage.getItem("yin-playground-source");
const savedExample = localStorage.getItem("yin-playground-example") || "recursion";
if (savedSource) {
  editor.value = savedSource;
  document.querySelectorAll(".example-tab").forEach((tab) => {
    const active = tab.dataset.example === savedExample;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  });
  updateEditorChrome();
} else {
  loadExample("recursion", false);
}
createWorker();
