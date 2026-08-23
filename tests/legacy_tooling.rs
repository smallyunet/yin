use serde_json::{Value as JsonValue, json};
use std::io::Cursor;
use std::io::Write;
use std::process::{Command, Stdio};
use yin::{CheckSession, Host, ReplSession, format_source, parse, run_language_server};

fn frame(message: JsonValue) -> Vec<u8> {
    let body = serde_json::to_vec(&message).unwrap();
    format!("Content-Length: {}\r\n\r\n", body.len())
        .into_bytes()
        .into_iter()
        .chain(body)
        .collect()
}

fn lsp(messages: Vec<JsonValue>) -> String {
    let input = messages.into_iter().flat_map(frame).collect::<Vec<_>>();
    let mut output = Vec::new();
    run_language_server(Cursor::new(input), &mut output).unwrap();
    String::from_utf8(output).unwrap()
}

#[test]
fn formatter_is_canonical_and_idempotent() {
    let once = format_source("x.yin", "(define   x [1  2 3])").unwrap();
    assert_eq!(once, "(define x [1 2 3])\n");
    assert_eq!(format_source("x.yin", &once).unwrap(), once);
}

#[test]
fn formatter_preserves_comments_and_adds_final_newline() {
    let source = "-- heading\n(define x 1) -- value";
    assert_eq!(
        format_source("x.yin", source).unwrap(),
        format!("{source}\n")
    );
}

#[test]
fn formatter_escapes_strings_and_rejects_invalid_syntax() {
    assert_eq!(
        format_source("x.yin", "(print \"a\\n\\\"b\")").unwrap(),
        "(print \"a\\n\\\"b\")\n"
    );
    assert!(format_source("x.yin", "(define x [1 2)").is_err());
}

#[test]
fn parser_reports_virtual_file_spans() {
    let error = parse("memory://broken.yin", "(define x [1 2)").unwrap_err();
    let span = error.diagnostic().span.as_ref().unwrap();
    assert_eq!(span.file, "memory://broken.yin");
    assert!(span.line >= 1 && span.column >= 1);
}

#[test]
fn historical_source_corpus_still_parses() {
    for path in [
        "examples/agents/structured-agent.yin",
        "examples/agents/action-gateway/main.yin",
        "examples/agents/tool-boundary/main.yin",
        "examples/agents/agent-review/main.yin",
        "examples/web3/transaction-guard/main.yin",
    ] {
        let source = std::fs::read_to_string(path).unwrap();
        parse(path, &source).unwrap_or_else(|error| panic!("{path}: {error}"));
    }
}

#[test]
fn lsp_initializes_and_formats_open_documents() {
    let output = lsp(vec![
        json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}),
        json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///x.yin","text":"(define   x 1)"}}}),
        json!({"jsonrpc":"2.0","id":2,"method":"textDocument/formatting","params":{"textDocument":{"uri":"file:///x.yin"}}}),
        json!({"jsonrpc":"2.0","method":"exit"}),
    ]);
    assert!(output.contains("documentFormattingProvider"));
    assert!(output.contains("(define x 1)\\n"));
    assert!(output.contains("publishDiagnostics"));
}

#[test]
fn lsp_republishes_diagnostics_after_changes() {
    let output = lsp(vec![
        json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///x.yin","text":"(+ 1 true)"}}}),
        json!({"jsonrpc":"2.0","method":"textDocument/didChange","params":{"textDocument":{"uri":"file:///x.yin"},"contentChanges":[{"text":"(+ 1 2)"}]}}),
        json!({"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":"file:///x.yin"}}}),
        json!({"jsonrpc":"2.0","method":"exit"}),
    ]);
    assert!(output.contains("numeric argument"));
    assert!(output.matches("\"diagnostics\":[]").count() >= 2);
}

#[test]
fn repl_persists_values_closures_and_records() {
    let mut repl = ReplSession::new(Host::default());
    repl.evaluate("<repl>", "(define base 40)").unwrap();
    repl.evaluate("<repl>", "(define add (fun (x) (+ base x)))")
        .unwrap();
    assert_eq!(
        repl.evaluate("<repl>", "(add 2)")
            .unwrap()
            .0
            .value
            .to_string(),
        "42"
    );
    repl.evaluate("<repl>", "(record Box [value Int])").unwrap();
    assert_eq!(
        repl.evaluate("<repl>", "(field (Box :value 7) :value)")
            .unwrap()
            .0
            .value
            .to_string(),
        "7"
    );
}

#[test]
fn failed_repl_checks_do_not_commit_definitions() {
    let mut checker = CheckSession::new();
    assert!(
        checker
            .check_source("<repl>", "(define bad (+ 1 true))")
            .is_err()
    );
    assert!(checker.check_source("<repl>", "bad").is_err());

    let mut repl = ReplSession::new(Host::default());
    assert!(repl.evaluate("<repl>", "(define bad (+ 1 true))").is_err());
    assert!(repl.evaluate("<repl>", "bad").is_err());
}

fn cli_repl(input: &str) -> (String, String) {
    let mut child = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("--repl")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child
        .stdin
        .take()
        .unwrap()
        .write_all(input.as_bytes())
        .unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    (
        String::from_utf8(output.stdout).unwrap(),
        String::from_utf8(output.stderr).unwrap(),
    )
}

#[test]
fn cli_repl_supports_multiline_recovery_and_quit() {
    let (stdout, stderr) = cli_repl(
        "(define add-two\n  (fun (value)\n    (+ value 2)))\n(add-two true)\n(add-two 40)\n:quit\n",
    );
    assert!(stderr.contains("YIN0001"), "{stderr}");
    assert!(stdout.ends_with("42\n"), "{stdout}");
}

#[test]
fn cli_repl_reports_incomplete_input_at_eof() {
    let (_, stderr) = cli_repl("(+ 1\n");
    assert!(stderr.contains("YIN1001"), "{stderr}");
    assert!(stderr.contains("unclosed delimiter"), "{stderr}");
}

#[test]
fn repl_redefinition_keeps_existing_closures_lexical() {
    let mut repl = ReplSession::new(Host::default());
    repl.evaluate(
        "<repl>",
        "(define value 1)\n(define captured (fun () value))",
    )
    .unwrap();
    repl.evaluate("<repl>", "(define value 2)").unwrap();
    assert_eq!(
        repl.evaluate("<repl>", "(captured)")
            .unwrap()
            .0
            .value
            .to_string(),
        "1"
    );
    assert_eq!(
        repl.evaluate("<repl>", "value")
            .unwrap()
            .0
            .value
            .to_string(),
        "2"
    );
}
