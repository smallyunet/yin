use crate::{check_program, format_source, parse};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::io::{self, BufRead, Read, Write};

pub fn run_language_server<R: Read, W: Write>(reader: R, mut writer: W) -> io::Result<()> {
    let mut reader = io::BufReader::new(reader);
    let mut documents = HashMap::<String, String>::new();
    loop {
        let Some(message) = read_message(&mut reader)? else {
            break;
        };
        let method = message.get("method").and_then(Value::as_str).unwrap_or("");
        let id = message.get("id").cloned();
        match method {
            "initialize" => write_message(
                &mut writer,
                &json!({"jsonrpc":"2.0","id":id,"result":{"capabilities":{"textDocumentSync":1,"documentFormattingProvider":true}}}),
            )?,
            "initialized" => {}
            "textDocument/didOpen" | "textDocument/didChange" => {
                let params = &message["params"];
                let uri = params
                    .pointer("/textDocument/uri")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_owned();
                let text = if method.ends_with("didOpen") {
                    params.pointer("/textDocument/text")
                } else {
                    params.pointer("/contentChanges/0/text")
                }
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
                documents.insert(uri.clone(), text.clone());
                let diagnostics = match parse(&uri, &text)
                    .and_then(|program| check_program(&program).map(|_| program))
                {
                    Ok(_) => vec![],
                    Err(error) => vec![
                        json!({"range":{"start":{"line":error.diagnostic().span.as_ref().map_or(0,|s|s.line.saturating_sub(1)),"character":error.diagnostic().span.as_ref().map_or(0,|s|s.column.saturating_sub(1))},"end":{"line":error.diagnostic().span.as_ref().map_or(0,|s|s.line.saturating_sub(1)),"character":error.diagnostic().span.as_ref().map_or(1,|s|s.column)}},"severity":1,"code":error.diagnostic().code.to_string(),"source":"yin","message":error.diagnostic().message}),
                    ],
                };
                write_message(
                    &mut writer,
                    &json!({"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":uri,"diagnostics":diagnostics}}),
                )?;
            }
            "textDocument/didClose" => {
                if let Some(uri) = message
                    .pointer("/params/textDocument/uri")
                    .and_then(Value::as_str)
                {
                    documents.remove(uri);
                    write_message(
                        &mut writer,
                        &json!({"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":uri,"diagnostics":[]}}),
                    )?;
                }
            }
            "textDocument/formatting" => {
                let uri = message
                    .pointer("/params/textDocument/uri")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let result=documents.get(uri).and_then(|source|format_source(uri,source).ok()).map(|new_text|vec![json!({"range":{"start":{"line":0,"character":0},"end":{"line":u32::MAX,"character":0}},"newText":new_text})]).unwrap_or_default();
                write_message(
                    &mut writer,
                    &json!({"jsonrpc":"2.0","id":id,"result":result}),
                )?;
            }
            "shutdown" => {
                write_message(&mut writer, &json!({"jsonrpc":"2.0","id":id,"result":null}))?
            }
            "exit" => break,
            _ if id.is_some() => {
                write_message(&mut writer, &json!({"jsonrpc":"2.0","id":id,"result":null}))?
            }
            _ => {}
        }
    }
    Ok(())
}

fn read_message<R: BufRead>(reader: &mut R) -> io::Result<Option<Value>> {
    let mut content_length = None;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Ok(None);
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
        if let Some(value) = line.strip_prefix("Content-Length:") {
            content_length = value.trim().parse::<usize>().ok();
        }
    }
    let length = content_length
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing Content-Length"))?;
    let mut body = vec![0; length];
    reader.read_exact(&mut body)?;
    serde_json::from_slice(&body)
        .map(Some)
        .map_err(io::Error::other)
}

fn write_message<W: Write>(writer: &mut W, message: &Value) -> io::Result<()> {
    let body = serde_json::to_vec(message).map_err(io::Error::other)?;
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())?;
    writer.write_all(&body)?;
    writer.flush()
}
