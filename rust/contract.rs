use crate::{Engine, Expr, Form, Host, Value, YinError, parse};
use serde_json::{Value as Json, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::Path;

const MAGIC: &[u8; 4] = b"YBC\x01";

struct Token(u8, String);

pub fn compile_bytecode(source_name: &str, source: &str) -> Result<Vec<u8>, YinError> {
    let program = parse(source_name, source)?;
    if program.expressions.is_empty() {
        return Err(YinError::language("cannot compile an empty program"));
    }
    let mut tokens = Vec::new();
    for expression in &program.expressions {
        emit(expression, &mut tokens)?;
    }
    if tokens.len() > 100_000 {
        return Err(YinError::language("bytecode token count exceeds maximum"));
    }
    let normalized = render(&tokens);
    let program_hash = Sha256::digest(normalized.as_bytes());
    let mut bytes = Vec::new();
    bytes.extend_from_slice(MAGIC);
    bytes.extend_from_slice(&1_u16.to_be_bytes());
    bytes.extend_from_slice(&1_u16.to_be_bytes());
    bytes.extend_from_slice(&(tokens.len() as u32).to_be_bytes());
    bytes.extend_from_slice(&program_hash);
    for Token(opcode, payload) in tokens {
        bytes.push(opcode);
        if opcode >= 5 {
            bytes.extend_from_slice(&(payload.len() as u32).to_be_bytes());
            bytes.extend_from_slice(payload.as_bytes());
        }
    }
    if bytes.len() > 1_048_576 {
        return Err(YinError::language("compiled bytecode exceeds maximum size"));
    }
    Ok(bytes)
}

pub fn contract_run(program: &Path, input: &Path) -> Result<Json, YinError> {
    let source = fs::read_to_string(program)
        .map_err(|e| YinError::io(format!("failed to read {}: {e}", program.display())))?;
    let input_text = fs::read_to_string(input)
        .map_err(|e| YinError::io(format!("failed to read {}: {e}", input.display())))?;
    let mut engine = Engine::new(Host::browser(&input_text, Vec::new()));
    let result = engine.run_source(program.to_string_lossy(), &source)?;
    let text = match result.value {
        Value::String(text) => text,
        Value::Result { ok: true, value } => match *value {
            Value::String(text) => text,
            other => {
                return Err(YinError::language(format!(
                    "contract returned {other}, expected JSON text"
                )));
            }
        },
        other => {
            return Err(YinError::language(format!(
                "contract returned {other}, expected JSON text"
            )));
        }
    };
    let result_json: Json = serde_json::from_str(&text)
        .map_err(|e| YinError::language(format!("contract returned invalid JSON: {e}")))?;
    let canonical_result =
        serde_json::to_string(&result_json).map_err(|e| YinError::language(e.to_string()))?;
    Ok(json!({
        "contractVersion": 1,
        "profile": "deterministic-policy-v1",
        "status": "completed",
        "programHash": hash(source.as_bytes()),
        "inputHash": hash(input_text.as_bytes()),
        "result": result_json,
        "resultHash": hash(canonical_result.as_bytes())
    }))
}

fn emit(expression: &Expr, tokens: &mut Vec<Token>) -> Result<(), YinError> {
    match expression {
        Expr::Atom(value, _) if value.starts_with(':') => {
            tokens.push(Token(6, value[1..].to_owned()))
        }
        Expr::Atom(value, _) if integer(value) => tokens.push(Token(7, value.clone())),
        Expr::Atom(value, _) => {
            if value == "fun" || value == "range" {
                return Err(YinError::language(format!(
                    "{value} is outside portable-bytecode-v1"
                )));
            }
            tokens.push(Token(5, value.clone()))
        }
        Expr::String(value, _) => tokens.push(Token(8, escape(value))),
        Expr::Form(form, values, _) => {
            tokens.push(Token(
                if *form == Form::Tuple { 1 } else { 3 },
                String::new(),
            ));
            for value in values {
                emit(value, tokens)?;
            }
            tokens.push(Token(
                if *form == Form::Tuple { 2 } else { 4 },
                String::new(),
            ));
        }
    }
    Ok(())
}

fn render(tokens: &[Token]) -> String {
    let mut out = String::new();
    for (index, Token(opcode, payload)) in tokens.iter().enumerate() {
        if index > 0 {
            out.push(' ');
        }
        match opcode {
            1 => out.push('('),
            2 => out.push(')'),
            3 => out.push('['),
            4 => out.push(']'),
            5 | 7 => out.push_str(payload),
            6 => {
                out.push(':');
                out.push_str(payload);
            }
            8 => {
                out.push('"');
                out.push_str(payload);
                out.push('"');
            }
            _ => {}
        }
    }
    out.push('\n');
    out
}

fn integer(value: &str) -> bool {
    value.parse::<i64>().is_ok()
        || value
            .strip_prefix("0x")
            .is_some_and(|v| i64::from_str_radix(v, 16).is_ok())
        || value
            .strip_prefix("0b")
            .is_some_and(|v| i64::from_str_radix(v, 2).is_ok())
}

fn escape(value: &str) -> String {
    value
        .chars()
        .flat_map(|ch| match ch {
            '\n' => "\\n".chars().collect::<Vec<_>>(),
            '\r' => "\\r".chars().collect(),
            '\t' => "\\t".chars().collect(),
            '"' => "\\\"".chars().collect(),
            '\\' => "\\\\".chars().collect(),
            _ => vec![ch],
        })
        .collect()
}

fn hash(value: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(value))
}
