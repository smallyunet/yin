use serde_json::{Map, Value as Json, json};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::env;
use std::fmt::Write;
use std::fs;
use std::path::Path;

const MAGIC: &[u8; 4] = b"YBC\x01";
const FORMAT_VERSION: u16 = 1;
const CONTRACT_VERSION: u16 = 1;
const MAX_BYTECODE_BYTES: usize = 1_048_576;
const MAX_INPUT_BYTES: usize = 262_144;
const DEFAULT_FUEL: u64 = 100_000;

#[derive(Clone, Debug, PartialEq)]
enum Token {
    OpenParen,
    CloseParen,
    OpenVector,
    CloseVector,
    Name(String),
    Keyword(String),
    Integer(i64),
    String(String),
}

#[derive(Clone, Debug)]
enum Expr {
    Name(String),
    Keyword(String),
    Integer(i64),
    String(String),
    List(Vec<Expr>),
    Vector(Vec<Expr>),
}

#[derive(Clone, Debug)]
enum RuntimeValue {
    Json(Json),
    Variant {
        tag: String,
        fields: Vec<(String, RuntimeValue)>,
    },
    Encoded(String),
}

#[derive(Clone)]
struct Function {
    params: Vec<String>,
    body: Vec<Expr>,
}

#[derive(Clone)]
struct Constructor {
    tag: Option<String>,
    fields: Vec<Field>,
}

#[derive(Clone)]
struct Field {
    name: String,
    type_name: String,
}

struct Artifact {
    tokens: Vec<Token>,
    program_hash: String,
    bytecode_hash: String,
}

struct Vm {
    input: String,
    fuel_limit: u64,
    fuel_used: u64,
    functions: HashMap<String, Function>,
    constructors: HashMap<String, Constructor>,
}

type Result<T> = std::result::Result<T, String>;

fn main() {
    if let Err(message) = run_cli() {
        eprintln!("yinvm: {message}");
        std::process::exit(1);
    }
}

fn run_cli() -> Result<()> {
    let args: Vec<String> = env::args().skip(1).collect();
    match args.as_slice() {
        [command, file] if command == "check" => {
            let artifact = decode(&read_bytecode(file)?)?;
            println!("{}", json!({
                "vmVersion": FORMAT_VERSION,
                "contractVersion": CONTRACT_VERSION,
                "profile": "portable-bytecode-v1",
                "instructionCount": artifact.tokens.len(),
                "programHash": artifact.program_hash,
                "bytecodeHash": artifact.bytecode_hash,
                "valid": true
            }));
            Ok(())
        }
        [command, file, input_flag, input_file] if command == "run" && input_flag == "--input" => {
            execute_command(file, input_file, DEFAULT_FUEL)
        }
        [command, file, input_flag, input_file, fuel_flag, fuel]
            if command == "run" && input_flag == "--input" && fuel_flag == "--fuel" => {
                let fuel = fuel.parse::<u64>().map_err(|_| "invalid fuel limit".to_string())?;
                execute_command(file, input_file, fuel)
            }
        _ => Err("usage: yinvm check <program.ybc> | yinvm run <program.ybc> --input <input.json> [--fuel <units>]".into()),
    }
}

fn execute_command(file: &str, input_file: &str, fuel: u64) -> Result<()> {
    let bytecode = read_bytecode(file)?;
    let input_size = fs::metadata(input_file)
        .map_err(|error| format!("failed to inspect input file {input_file}: {error}"))?
        .len();
    if input_size > MAX_INPUT_BYTES as u64 {
        return Err("input exceeds VM maximum size".into());
    }
    let input = fs::read_to_string(input_file)
        .map_err(|error| format!("failed to read input file {input_file}: {error}"))?;
    if input.len() > MAX_INPUT_BYTES {
        return Err("input exceeds VM maximum size".into());
    }
    let artifact = decode(&bytecode)?;
    let expressions = parse(&artifact.tokens)?;
    let mut vm = Vm::new(input.clone(), fuel);
    vm.charge(artifact.tokens.len() as u64)?;
    let result = vm.execute(expressions)?;
    let result_json: Json = serde_json::from_str(&result)
        .map_err(|error| format!("contract returned invalid JSON: {error}"))?;
    let result_text = serde_json::to_string(&result_json).map_err(|error| error.to_string())?;
    println!(
        "{}",
        json!({
            "vmVersion": FORMAT_VERSION,
            "profile": "portable-bytecode-v1",
            "status": "completed",
            "bytecodeHash": artifact.bytecode_hash,
            "programHash": artifact.program_hash,
            "inputHash": hash(input.as_bytes()),
            "fuelLimit": fuel,
            "fuelUsed": vm.fuel_used,
            "result": result_json,
            "resultHash": hash(result_text.as_bytes())
        })
    );
    Ok(())
}

impl Vm {
    fn new(input: String, fuel_limit: u64) -> Self {
        Self {
            input,
            fuel_limit,
            fuel_used: 0,
            functions: HashMap::new(),
            constructors: HashMap::new(),
        }
    }

    fn charge(&mut self, units: u64) -> Result<()> {
        self.fuel_used = self
            .fuel_used
            .checked_add(units)
            .ok_or("fuel counter overflow")?;
        if self.fuel_used > self.fuel_limit {
            return Err(format!(
                "VM fuel exhausted: used {}, limit {}",
                self.fuel_used, self.fuel_limit
            ));
        }
        Ok(())
    }

    fn execute(&mut self, expressions: Vec<Expr>) -> Result<String> {
        self.charge(16 + self.input.len() as u64)?;
        let mut env = HashMap::new();
        let mut last = RuntimeValue::Json(Json::Null);
        for expression in expressions {
            last = self.eval_top(&expression, &mut env)?;
        }
        match last {
            RuntimeValue::Encoded(text) => Ok(text),
            _ => Err("deterministic contract must return JSON text from encode-json".into()),
        }
    }

    fn eval_top(
        &mut self,
        expression: &Expr,
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        if let Expr::List(items) = expression {
            match name(items.first()) {
                Some("record") => {
                    self.define_record(items)?;
                    return Ok(RuntimeValue::Json(Json::Null));
                }
                Some("variant") => {
                    self.define_variant(items)?;
                    return Ok(RuntimeValue::Json(Json::Null));
                }
                Some("policy") => {
                    self.define_policy(items)?;
                    return Ok(RuntimeValue::Json(Json::Null));
                }
                _ => {}
            }
        }
        self.eval(expression, env)
    }

    fn define_record(&mut self, items: &[Expr]) -> Result<()> {
        self.charge(1)?;
        let constructor = name(items.get(1))
            .ok_or("record name must be an identifier")?
            .to_string();
        let fields = items[2..].iter().map(field).collect::<Result<Vec<_>>>()?;
        self.constructors
            .insert(constructor, Constructor { tag: None, fields });
        Ok(())
    }

    fn define_variant(&mut self, items: &[Expr]) -> Result<()> {
        self.charge(1)?;
        for branch in &items[2..] {
            let Expr::Vector(parts) = branch else {
                return Err("variant branch must be a vector".into());
            };
            let tag = name(parts.first())
                .ok_or("variant tag must be an identifier")?
                .to_string();
            let fields = parts[1..].iter().map(field).collect::<Result<Vec<_>>>()?;
            self.constructors.insert(
                tag.clone(),
                Constructor {
                    tag: Some(tag),
                    fields,
                },
            );
        }
        Ok(())
    }

    fn define_policy(&mut self, items: &[Expr]) -> Result<()> {
        self.charge(1)?;
        let function_name = name(items.get(1))
            .ok_or("policy name must be an identifier")?
            .to_string();
        let Expr::List(signature) = items.get(2).ok_or("policy signature is missing")? else {
            return Err("policy signature must be a list".into());
        };
        let mut params = Vec::new();
        for part in signature {
            let Expr::Vector(binding) = part else {
                continue;
            };
            if name(binding.first()) == Some("->") {
                continue;
            }
            params.push(
                name(binding.first())
                    .ok_or("policy parameter must be an identifier")?
                    .to_string(),
            );
        }
        self.functions.insert(
            function_name,
            Function {
                params,
                body: items[3..].to_vec(),
            },
        );
        Ok(())
    }

    fn eval(
        &mut self,
        expression: &Expr,
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        self.charge(1)?;
        match expression {
            Expr::Integer(value) => Ok(RuntimeValue::Json(json!(value))),
            Expr::String(value) => Ok(RuntimeValue::Json(json!(decode_string(value)?))),
            Expr::Name(value) if value == "true" => Ok(RuntimeValue::Json(Json::Bool(true))),
            Expr::Name(value) if value == "false" => Ok(RuntimeValue::Json(Json::Bool(false))),
            Expr::Name(value) => self.lookup(value, env),
            Expr::Keyword(value) => Ok(RuntimeValue::Json(json!(value))),
            Expr::Vector(_) => {
                Err("vector cannot be evaluated directly by portable-bytecode-v1".into())
            }
            Expr::List(items) => self.eval_list(items, env),
        }
    }

    fn lookup(&self, value: &str, env: &HashMap<String, RuntimeValue>) -> Result<RuntimeValue> {
        let mut parts = value.split('.');
        let first = parts.next().unwrap_or_default();
        let mut current = env
            .get(first)
            .cloned()
            .ok_or_else(|| format!("unbound value: {first}"))?;
        for field in parts {
            current = match current {
                RuntimeValue::Json(Json::Object(object)) => RuntimeValue::Json(
                    object
                        .get(field)
                        .cloned()
                        .ok_or_else(|| format!("missing field: {field}"))?,
                ),
                RuntimeValue::Variant { fields, .. } => fields
                    .into_iter()
                    .find(|(name, _)| name == field)
                    .map(|(_, value)| value)
                    .ok_or_else(|| format!("missing field: {field}"))?,
                _ => return Err(format!("cannot access field {field}")),
            };
        }
        Ok(current)
    }

    fn eval_list(
        &mut self,
        items: &[Expr],
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        let operation = name(items.first()).ok_or("call target must be an identifier")?;
        match operation {
            "read-all" => Ok(RuntimeValue::Json(json!(self.input))),
            "decode-json" => self.decode_json(items, env),
            "encode-json" => {
                let value = self.eval(required(items, 1)?, env)?;
                let text =
                    serde_json::to_string(&to_json(value)?).map_err(|error| error.to_string())?;
                self.charge(text.len() as u64)?;
                Ok(RuntimeValue::Encoded(text))
            }
            "match" => self.eval_match(items, env),
            "not" => Ok(RuntimeValue::Json(json!(!as_bool(
                self.eval(required(items, 1)?, env)?
            )?))),
            ">" => {
                let left = as_i64(self.eval(required(items, 1)?, env)?)?;
                let right = as_i64(self.eval(required(items, 2)?, env)?)?;
                Ok(RuntimeValue::Json(json!(left > right)))
            }
            "concat" => {
                let left = as_string(self.eval(required(items, 1)?, env)?)?;
                let right = as_string(self.eval(required(items, 2)?, env)?)?;
                Ok(RuntimeValue::Json(json!(format!("{left}{right}"))))
            }
            name if self.functions.contains_key(name) => self.call_policy(name, &items[1..], env),
            name if self.constructors.contains_key(name) => self.construct(name, &items[1..], env),
            _ => Err(format!(
                "unsupported portable bytecode operation: {operation}"
            )),
        }
    }

    fn decode_json(
        &mut self,
        items: &[Expr],
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        let type_name = name(items.get(1)).ok_or("decode-json type must be an identifier")?;
        let input = as_string(self.eval(required(items, 2)?, env)?)?;
        match serde_json::from_str::<Json>(&input) {
            Ok(value) => match self.validate_json(type_name, &value, "$") {
                Ok(()) => Ok(RuntimeValue::Variant {
                    tag: "Ok".into(),
                    fields: vec![("value".into(), RuntimeValue::Json(value))],
                }),
                Err((code, path, message)) => Ok(decode_error(code, path, message)),
            },
            Err(error) => Ok(RuntimeValue::Variant {
                tag: "Err".into(),
                fields: vec![(
                    "error".into(),
                    RuntimeValue::Json(json!({
                        "code": "invalid-json", "path": "$", "message": error.to_string()
                    })),
                )],
            }),
        }
    }

    fn validate_json(
        &self,
        type_name: &str,
        value: &Json,
        path: &str,
    ) -> std::result::Result<(), (String, String, String)> {
        let mismatch = || {
            (
                "type-mismatch".into(),
                path.into(),
                format!("expected {type_name}"),
            )
        };
        match type_name {
            "Int" if value.as_i64().is_some() => Ok(()),
            "Bool" if value.is_boolean() => Ok(()),
            "String" if value.is_string() => Ok(()),
            "Int" | "Bool" | "String" => Err(mismatch()),
            _ => {
                let constructor = self.constructors.get(type_name).ok_or_else(|| {
                    (
                        "unsupported-type".into(),
                        path.into(),
                        format!("unsupported input type {type_name}"),
                    )
                })?;
                if constructor.tag.is_some() {
                    return Err(mismatch());
                }
                let object = value.as_object().ok_or_else(mismatch)?;
                for key in object.keys() {
                    if !constructor.fields.iter().any(|field| field.name == *key) {
                        return Err((
                            "unknown-field".into(),
                            format!("{path}.{key}"),
                            format!("unknown field {key}"),
                        ));
                    }
                }
                for field in &constructor.fields {
                    let field_path = format!("{path}.{}", field.name);
                    let field_value = object.get(&field.name).ok_or_else(|| {
                        (
                            "missing-field".into(),
                            field_path.clone(),
                            format!("missing field {}", field.name),
                        )
                    })?;
                    self.validate_json(&field.type_name, field_value, &field_path)?;
                }
                Ok(())
            }
        }
    }

    fn eval_match(
        &mut self,
        items: &[Expr],
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        let value = self.eval(required(items, 1)?, env)?;
        let RuntimeValue::Variant { tag, fields } = value else {
            return Err("match requires a variant".into());
        };
        for arm in &items[2..] {
            let Expr::Vector(parts) = arm else {
                return Err("match arm must be a vector".into());
            };
            let Expr::List(pattern) = required(parts, 0)? else {
                return Err("match pattern must be a list".into());
            };
            if name(pattern.first()) != Some(tag.as_str()) {
                continue;
            }
            let mut local = env.clone();
            for (index, binder) in pattern[1..].iter().enumerate() {
                let binding = name(Some(binder)).ok_or("match binder must be an identifier")?;
                let value = fields
                    .get(index)
                    .ok_or("match payload arity mismatch")?
                    .1
                    .clone();
                local.insert(binding.to_string(), value);
            }
            return self.eval(required(parts, 1)?, &mut local);
        }
        Err(format!("non-exhaustive match for {tag}"))
    }

    fn call_policy(
        &mut self,
        function_name: &str,
        arguments: &[Expr],
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        let function = self
            .functions
            .get(function_name)
            .cloned()
            .ok_or("unknown policy")?;
        if arguments.len() != function.params.len() {
            return Err("policy argument arity mismatch".into());
        }
        let mut local = HashMap::new();
        for (parameter, argument) in function.params.iter().zip(arguments) {
            local.insert(parameter.clone(), self.eval(argument, env)?);
        }
        for clause in &function.body {
            let Expr::List(parts) = clause else {
                return Err("policy clause must be a list".into());
            };
            match name(parts.first()) {
                Some("when") if as_bool(self.eval(required(parts, 1)?, &mut local)?)? => {
                    return self.eval(required(parts, 2)?, &mut local);
                }
                Some("when") => {}
                Some("otherwise") => return self.eval(required(parts, 1)?, &mut local),
                _ => return Err("unsupported policy clause".into()),
            }
        }
        Err("policy has no matching clause".into())
    }

    fn construct(
        &mut self,
        name: &str,
        arguments: &[Expr],
        env: &mut HashMap<String, RuntimeValue>,
    ) -> Result<RuntimeValue> {
        let constructor = self
            .constructors
            .get(name)
            .cloned()
            .ok_or("unknown constructor")?;
        let mut supplied = HashMap::new();
        let mut index = 0;
        while index < arguments.len() {
            let Expr::Keyword(field) = &arguments[index] else {
                return Err("constructor fields must use keywords".into());
            };
            supplied.insert(
                field.clone(),
                self.eval(required(arguments, index + 1)?, env)?,
            );
            index += 2;
        }
        let mut fields = Vec::new();
        for field in constructor.fields {
            fields.push((
                field.name.clone(),
                supplied
                    .remove(&field.name)
                    .ok_or_else(|| format!("missing constructor field: {}", field.name))?,
            ));
        }
        match constructor.tag {
            Some(tag) => Ok(RuntimeValue::Variant { tag, fields }),
            None => {
                let mut object = Map::new();
                for (field, value) in fields {
                    object.insert(field, to_json(value)?);
                }
                Ok(RuntimeValue::Json(Json::Object(object)))
            }
        }
    }
}

fn decode(bytes: &[u8]) -> Result<Artifact> {
    if bytes.len() > MAX_BYTECODE_BYTES {
        return Err("bytecode exceeds maximum size".into());
    }
    let mut cursor = Cursor::new(bytes);
    if cursor.take(4)? != MAGIC {
        return Err("invalid bytecode magic".into());
    }
    let format = cursor.u16()?;
    if format != FORMAT_VERSION {
        return Err(format!("unsupported bytecode version: {format}"));
    }
    let contract = cursor.u16()?;
    if contract != CONTRACT_VERSION {
        return Err(format!("unsupported contract profile version: {contract}"));
    }
    let count = cursor.u32()? as usize;
    if count == 0 || count > 100_000 {
        return Err(format!("invalid bytecode token count: {count}"));
    }
    let expected_hash = cursor.take(32)?.to_vec();
    let mut tokens = Vec::with_capacity(count);
    for _ in 0..count {
        let opcode = cursor.u8()?;
        let token = match opcode {
            1 => Token::OpenParen,
            2 => Token::CloseParen,
            3 => Token::OpenVector,
            4 => Token::CloseVector,
            5 => Token::Name(cursor.string()?),
            6 => Token::Keyword(cursor.string()?),
            7 => {
                let encoded = cursor.string()?;
                let value = encoded
                    .parse::<i64>()
                    .map_err(|_| "invalid integer operand")?;
                if encoded != value.to_string() {
                    return Err("non-canonical integer operand".into());
                }
                Token::Integer(value)
            }
            8 => Token::String(cursor.string()?),
            _ => return Err(format!("unknown bytecode opcode: {opcode}")),
        };
        tokens.push(token);
    }
    if cursor.remaining() != 0 {
        return Err("trailing bytes after bytecode program".into());
    }
    validate_termination(&tokens)?;
    let normalized = render(&tokens);
    if Sha256::digest(normalized.as_bytes()).as_slice() != expected_hash.as_slice() {
        return Err("bytecode program hash mismatch".into());
    }
    let expressions = parse(&tokens)?;
    validate_program_termination(&expressions)?;
    Ok(Artifact {
        tokens,
        program_hash: hash_raw(&expected_hash),
        bytecode_hash: hash(bytes),
    })
}

fn validate_termination(tokens: &[Token]) -> Result<()> {
    for token in tokens {
        match token {
            Token::Name(name) if name == "fun" => {
                return Err("explicit fun is outside portable-bytecode-v1".into());
            }
            Token::Name(name) if name == "range" => {
                return Err("range is outside portable-bytecode-v1".into());
            }
            Token::Name(name)
                if matches!(
                    name.as_str(),
                    "Any"
                        | "Float"
                        | "args"
                        | "parse-float"
                        | "print"
                        | "read-text"
                        | "set!"
                        | "tool"
                        | "invoke"
                ) =>
            {
                return Err(format!("{name} is outside portable-bytecode-v1"));
            }
            _ => {}
        }
    }
    Ok(())
}

fn validate_program_termination(expressions: &[Expr]) -> Result<()> {
    let policies = expressions
        .iter()
        .filter_map(|expression| {
            let Expr::List(items) = expression else {
                return None;
            };
            (name(items.first()) == Some("policy"))
                .then(|| name(items.get(1)))
                .flatten()
        })
        .collect::<Vec<_>>();
    for expression in expressions {
        let Expr::List(items) = expression else {
            continue;
        };
        if name(items.first()) != Some("policy") {
            continue;
        }
        for body in &items[3..] {
            if let Some(called) = policies.iter().find(|policy| contains_name(body, policy)) {
                return Err(format!(
                    "policy calls are outside portable-bytecode-v1: {called}"
                ));
            }
        }
    }
    let mut callable = vec![
        "record",
        "variant",
        "policy",
        "when",
        "otherwise",
        "match",
        "decode-json",
        "read-all",
        "encode-json",
        "not",
        ">",
        "concat",
        "Ok",
        "Err",
    ];
    for expression in expressions {
        let Expr::List(items) = expression else {
            continue;
        };
        match name(items.first()) {
            Some("record" | "policy") => {
                if let Some(declared) = name(items.get(1)) {
                    callable.push(declared);
                }
            }
            Some("variant") => {
                for branch in &items[2..] {
                    if let Expr::Vector(parts) = branch
                        && let Some(tag) = name(parts.first())
                    {
                        callable.push(tag);
                    }
                }
            }
            _ => {}
        }
    }
    for expression in expressions {
        validate_operations(expression, &callable)?;
    }
    let mut types = HashSet::from(["Int".to_string(), "Bool".to_string(), "String".to_string()]);
    for expression in expressions {
        let Expr::List(items) = expression else {
            continue;
        };
        if matches!(name(items.first()), Some("record" | "variant"))
            && let Some(declared) = name(items.get(1))
        {
            types.insert(declared.to_string());
        }
    }
    for expression in expressions {
        validate_types(expression, &types)?;
    }
    Ok(())
}

fn validate_operations(expression: &Expr, callable: &[&str]) -> Result<()> {
    match expression {
        Expr::List(items) => {
            if let Some(operation) = name(items.first())
                && !callable.contains(&operation)
            {
                return Err(format!(
                    "unsupported portable bytecode operation: {operation}"
                ));
            }
            for item in items {
                validate_operations(item, callable)?;
            }
        }
        Expr::Vector(items) => {
            for item in items {
                validate_operations(item, callable)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn validate_types(expression: &Expr, types: &HashSet<String>) -> Result<()> {
    match expression {
        Expr::Vector(items) => {
            if let (Some(Expr::Name(_)), Some(Expr::Name(type_name))) =
                (items.first(), items.get(1))
                && !types.contains(type_name)
            {
                return Err(format!("unsupported portable bytecode type: {type_name}"));
            }
            for item in items {
                validate_types(item, types)?;
            }
        }
        Expr::List(items) => {
            for item in items {
                validate_types(item, types)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn contains_name(expression: &Expr, expected: &str) -> bool {
    match expression {
        Expr::Name(value) => value == expected,
        Expr::List(items) | Expr::Vector(items) => {
            items.iter().any(|item| contains_name(item, expected))
        }
        _ => false,
    }
}

fn parse(tokens: &[Token]) -> Result<Vec<Expr>> {
    let mut index = 0;
    let mut expressions = Vec::new();
    while index < tokens.len() {
        expressions.push(parse_one(tokens, &mut index)?);
    }
    Ok(expressions)
}

fn parse_one(tokens: &[Token], index: &mut usize) -> Result<Expr> {
    let token = tokens.get(*index).ok_or("truncated expression")?.clone();
    *index += 1;
    match token {
        Token::OpenParen => parse_sequence(tokens, index, false),
        Token::OpenVector => parse_sequence(tokens, index, true),
        Token::CloseParen | Token::CloseVector => Err("unexpected closing delimiter".into()),
        Token::Name(value) => Ok(Expr::Name(value)),
        Token::Keyword(value) => Ok(Expr::Keyword(value)),
        Token::Integer(value) => Ok(Expr::Integer(value)),
        Token::String(value) => Ok(Expr::String(value)),
    }
}

fn parse_sequence(tokens: &[Token], index: &mut usize, vector: bool) -> Result<Expr> {
    let mut items = Vec::new();
    loop {
        match tokens.get(*index) {
            Some(Token::CloseVector) if vector => {
                *index += 1;
                return Ok(Expr::Vector(items));
            }
            Some(Token::CloseParen) if !vector => {
                *index += 1;
                return Ok(Expr::List(items));
            }
            Some(Token::CloseVector | Token::CloseParen) => {
                return Err("mismatched closing delimiter".into());
            }
            None => return Err("unclosed delimiter".into()),
            _ => items.push(parse_one(tokens, index)?),
        }
    }
}

fn render(tokens: &[Token]) -> String {
    let rendered = tokens
        .iter()
        .map(|token| match token {
            Token::OpenParen => "(".into(),
            Token::CloseParen => ")".into(),
            Token::OpenVector => "[".into(),
            Token::CloseVector => "]".into(),
            Token::Name(value) => value.clone(),
            Token::Keyword(value) => format!(":{value}"),
            Token::Integer(value) => value.to_string(),
            Token::String(value) => format!("\"{value}\""),
        })
        .collect::<Vec<_>>()
        .join(" ");
    format!("{rendered}\n")
}

fn field(expression: &Expr) -> Result<Field> {
    let Expr::Vector(parts) = expression else {
        return Err("field must be a vector".into());
    };
    Ok(Field {
        name: name(parts.first())
            .ok_or("field name must be an identifier")?
            .to_string(),
        type_name: name(parts.get(1))
            .ok_or("field type must be an identifier")?
            .to_string(),
    })
}

fn decode_error(code: String, path: String, message: String) -> RuntimeValue {
    RuntimeValue::Variant {
        tag: "Err".into(),
        fields: vec![(
            "error".into(),
            RuntimeValue::Json(json!({
                "code": code, "path": path, "message": message
            })),
        )],
    }
}

fn name(expression: Option<&Expr>) -> Option<&str> {
    match expression {
        Some(Expr::Name(value)) => Some(value),
        _ => None,
    }
}

fn required<T>(items: &[T], index: usize) -> Result<&T> {
    items
        .get(index)
        .ok_or_else(|| format!("missing operand {index}"))
}

fn as_bool(value: RuntimeValue) -> Result<bool> {
    match value {
        RuntimeValue::Json(Json::Bool(value)) => Ok(value),
        _ => Err("expected Bool".into()),
    }
}

fn as_i64(value: RuntimeValue) -> Result<i64> {
    match value {
        RuntimeValue::Json(Json::Number(value)) => value.as_i64().ok_or("expected Int".into()),
        _ => Err("expected Int".into()),
    }
}

fn as_string(value: RuntimeValue) -> Result<String> {
    match value {
        RuntimeValue::Json(Json::String(value)) => Ok(value),
        RuntimeValue::Encoded(value) => Ok(value),
        _ => Err("expected String".into()),
    }
}

fn to_json(value: RuntimeValue) -> Result<Json> {
    match value {
        RuntimeValue::Json(value) => Ok(value),
        RuntimeValue::Encoded(value) => Ok(Json::String(value)),
        RuntimeValue::Variant { tag, fields } => {
            let mut object = Map::new();
            object.insert("tag".into(), Json::String(tag));
            for (name, value) in fields {
                object.insert(name, to_json(value)?);
            }
            Ok(Json::Object(object))
        }
    }
}

fn decode_string(source: &str) -> Result<String> {
    serde_json::from_str::<String>(&format!("\"{source}\""))
        .map_err(|error| format!("invalid string operand: {error}"))
}

fn read_bytecode(file: &str) -> Result<Vec<u8>> {
    let size = fs::metadata(file)
        .map_err(|error| format!("failed to inspect bytecode file {file}: {error}"))?
        .len();
    if size > MAX_BYTECODE_BYTES as u64 {
        return Err("bytecode exceeds maximum size".into());
    }
    fs::read(Path::new(file))
        .map_err(|error| format!("failed to read bytecode file {file}: {error}"))
}

fn hash(bytes: &[u8]) -> String {
    hash_raw(&Sha256::digest(bytes))
}
fn hash_raw(bytes: &[u8]) -> String {
    let mut value = String::from("sha256:");
    for byte in bytes {
        write!(&mut value, "{byte:02x}").expect("writing to a String cannot fail");
    }
    value
}

struct Cursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}
impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }
    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.offset)
    }
    fn take(&mut self, length: usize) -> Result<&'a [u8]> {
        if length > self.remaining() {
            return Err("truncated bytecode".into());
        }
        let start = self.offset;
        self.offset += length;
        Ok(&self.bytes[start..self.offset])
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn string(&mut self) -> Result<String> {
        let length = self.u32()? as usize;
        if length > MAX_BYTECODE_BYTES {
            return Err("invalid bytecode operand length".into());
        }
        String::from_utf8(self.take(length)?.to_vec())
            .map_err(|_| "bytecode operand is not UTF-8".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parser_rejects_mismatched_delimiters() {
        assert!(parse(&[Token::OpenParen, Token::CloseVector]).is_err());
    }

    #[test]
    fn fuel_is_enforced_before_execution() {
        let mut vm = Vm::new("{}".into(), 1);
        assert!(
            vm.execute(vec![Expr::String("{}".into())])
                .unwrap_err()
                .contains("fuel exhausted")
        );
    }

    #[test]
    fn record_input_is_closed_and_typed() {
        let mut vm = Vm::new("{}".into(), 100);
        vm.constructors.insert(
            "Request".into(),
            Constructor {
                tag: None,
                fields: vec![
                    Field {
                        name: "amount".into(),
                        type_name: "Int".into(),
                    },
                    Field {
                        name: "approved".into(),
                        type_name: "Bool".into(),
                    },
                ],
            },
        );
        assert!(
            vm.validate_json("Request", &json!({"amount": 3, "approved": true}), "$")
                .is_ok()
        );
        assert_eq!(
            "unknown-field",
            vm.validate_json(
                "Request",
                &json!({"amount": 3, "approved": true, "extra": 1}),
                "$"
            )
            .unwrap_err()
            .0
        );
        assert_eq!(
            "type-mismatch",
            vm.validate_json("Request", &json!({"amount": "3", "approved": true}), "$")
                .unwrap_err()
                .0
        );
    }
}
