use crate::syntax::{Expr, Form, parse};
use crate::value::{Function, RecordDefinition};
use crate::{Type, Value, YinError, check_program};
use indexmap::IndexMap;
use num_bigint::BigInt;
use num_traits::{ToPrimitive, Zero};
use serde_json::{Map as JsonMap, Value as JsonValue};
use std::cell::RefCell;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::rc::Rc;

pub type ToolExecutor = Rc<dyn Fn(&str, &JsonValue) -> Result<JsonValue, YinError>>;

#[derive(Clone, Default)]
pub struct Host {
    pub input: String,
    pub arguments: Vec<String>,
    pub root: Option<PathBuf>,
    pub allow_read: bool,
    pub tool_executor: Option<ToolExecutor>,
}

impl Host {
    pub fn standard(input: impl Into<String>, arguments: Vec<String>) -> Self {
        Self {
            input: input.into(),
            arguments,
            root: None,
            allow_read: true,
            tool_executor: None,
        }
    }

    pub fn browser(input: impl Into<String>, arguments: Vec<String>) -> Self {
        Self {
            input: input.into(),
            arguments,
            root: None,
            allow_read: false,
            tool_executor: None,
        }
    }
}

#[derive(Clone, Debug)]
pub struct ProgramResult {
    pub value: Value,
    pub output: Vec<String>,
}

#[derive(Clone)]
pub struct Parameter {
    pub name: String,
    pub default: Option<Value>,
}

#[derive(Clone)]
pub struct Environment(Rc<RefCell<Frame>>);

#[derive(Clone, Default)]
struct Frame {
    values: IndexMap<String, Value>,
    parent: Option<Environment>,
}

impl Environment {
    fn root() -> Self {
        Self(Rc::new(RefCell::new(Frame::default())))
    }
    fn child(&self) -> Self {
        Self(Rc::new(RefCell::new(Frame {
            values: IndexMap::new(),
            parent: Some(self.clone()),
        })))
    }
    fn define(&self, name: impl Into<String>, value: Value) -> Result<(), YinError> {
        let name = name.into();
        let mut frame = self.0.borrow_mut();
        if frame.values.contains_key(&name) {
            return Err(YinError::language(format!("duplicate definition: {name}")));
        }
        frame.values.insert(name, value);
        Ok(())
    }
    fn force_define(&self, name: impl Into<String>, value: Value) {
        self.0.borrow_mut().values.insert(name.into(), value);
    }
    fn get(&self, name: &str) -> Option<Value> {
        let frame = self.0.borrow();
        frame
            .values
            .get(name)
            .cloned()
            .or_else(|| frame.parent.as_ref().and_then(|p| p.get(name)))
    }
    fn assign(&self, name: &str, value: Value) -> Result<(), YinError> {
        let parent = {
            let mut frame = self.0.borrow_mut();
            if frame.values.contains_key(name) {
                frame.values.insert(name.to_owned(), value);
                return Ok(());
            }
            frame.parent.clone()
        };
        parent
            .ok_or_else(|| YinError::language(format!("unbound name: {name}")))?
            .assign(name, value)
    }
}

pub struct Engine {
    host: Host,
    output: Vec<String>,
    modules: IndexMap<PathBuf, IndexMap<String, Value>>,
    loading: HashSet<PathBuf>,
    current_file: Option<PathBuf>,
}

impl Engine {
    pub fn new(host: Host) -> Self {
        Self {
            host,
            output: Vec::new(),
            modules: IndexMap::new(),
            loading: HashSet::new(),
            current_file: None,
        }
    }

    pub fn run_file(&mut self, path: impl AsRef<Path>) -> Result<ProgramResult, YinError> {
        let path = path.as_ref().canonicalize().map_err(|e| {
            YinError::io(format!(
                "failed to read source {}: {e}",
                path.as_ref().display()
            ))
        })?;
        let source = fs::read_to_string(&path)
            .map_err(|e| YinError::io(format!("failed to read source {}: {e}", path.display())))?;
        self.current_file = Some(path.clone());
        if self.host.root.is_none() {
            self.host.root = path.parent().map(Path::to_path_buf);
        }
        self.run_source(path.to_string_lossy(), &source)
    }

    pub fn run_source(
        &mut self,
        name: impl Into<String>,
        source: &str,
    ) -> Result<ProgramResult, YinError> {
        let program = parse(name, source)?;
        check_program(&program)?;
        let env = self.initial_environment();
        let value = self.eval_sequence(&program.expressions, &env)?;
        Ok(ProgramResult {
            value,
            output: std::mem::take(&mut self.output),
        })
    }

    fn initial_environment(&self) -> Environment {
        let env = Environment::root();
        env.force_define("true", Value::Bool(true));
        env.force_define("false", Value::Bool(false));
        env.force_define("none", Value::Option(None));
        env.force_define(
            "args",
            Value::Vector(
                self.host
                    .arguments
                    .iter()
                    .cloned()
                    .map(Value::String)
                    .collect(),
            ),
        );
        for name in ["Int", "Float", "Bool", "String", "Any"] {
            env.force_define(
                name,
                Value::Type(match name {
                    "Int" => Type::Int,
                    "Float" => Type::Float,
                    "Bool" => Type::Bool,
                    "String" => Type::String,
                    _ => Type::Any,
                }),
            );
        }
        for name in [
            "+",
            "-",
            "*",
            "/",
            "<",
            "<=",
            ">",
            ">=",
            "=",
            "and",
            "or",
            "not",
            "length",
            "at",
            "append",
            "map",
            "filter",
            "fold",
            "range",
            "slice",
            "reverse",
            "contains",
            "dict",
            "dict/get",
            "dict/put",
            "dict/remove",
            "dict/keys",
            "dict/values",
            "dict/contains-key",
            "dict/size",
            "set",
            "set/add",
            "set/remove",
            "set/contains",
            "set/values",
            "set/size",
            "set/union",
            "set/intersection",
            "set/difference",
            "ok",
            "err",
            "some",
            "string-length",
            "concat",
            "substring",
            "split",
            "join",
            "trim",
            "to-string",
            "parse-int",
            "parse-float",
            "read-all",
            "read-text",
            "print",
            "U",
            "Vector",
            "Fn",
            "Result",
            "Option",
            "Dict",
            "Set",
        ] {
            env.force_define(name, Value::Primitive(name));
        }
        env
    }

    fn eval_sequence(
        &mut self,
        expressions: &[Expr],
        env: &Environment,
    ) -> Result<Value, YinError> {
        let mut value = Value::Void;
        for expression in expressions {
            value = self.eval(expression, env)?;
        }
        Ok(value)
    }

    fn eval(&mut self, expression: &Expr, env: &Environment) -> Result<Value, YinError> {
        match expression {
            Expr::String(value, _) => Ok(Value::String(value.clone())),
            Expr::Atom(atom, _) => self.eval_atom(atom, env),
            Expr::Form(Form::Vector, values, _) => values
                .iter()
                .map(|v| self.eval(v, env))
                .collect::<Result<Vec<_>, _>>()
                .map(Value::Vector),
            Expr::Form(Form::Tuple, values, _) if values.is_empty() => Ok(Value::Void),
            Expr::Form(Form::Tuple, values, _) => self.eval_tuple(values, env),
        }
    }

    fn eval_atom(&self, atom: &str, env: &Environment) -> Result<Value, YinError> {
        if let Some(value) = parse_integer(atom) {
            return Ok(Value::Int(value));
        }
        if atom.contains('.') && atom.chars().any(|ch| ch.is_ascii_digit()) {
            if let Ok(value) = atom.parse::<f64>() {
                return Ok(Value::Float(value));
            }
        }
        if atom.contains('.') && !atom.starts_with(':') {
            let mut parts = atom.split('.');
            let first = parts.next().unwrap();
            let mut value = env
                .get(first)
                .ok_or_else(|| YinError::language(format!("unbound name: {first}")))?;
            for field in parts {
                value = field_value(&value, field)?;
            }
            return Ok(value);
        }
        env.get(atom)
            .ok_or_else(|| YinError::language(format!("unbound name: {atom}")))
    }

    fn eval_tuple(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        let keyword = values[0].atom();
        match keyword {
            Some("seq") => self.eval_sequence(&values[1..], env),
            Some("if") => self.eval_if(values, env),
            Some("define") => self.eval_define(values, env),
            Some("set!") => self.eval_assign(values, env),
            Some("fun") => self.eval_fun(values, env),
            Some("match") => self.eval_match(values, env),
            Some("policy") => self.eval_policy(values, env),
            Some("record") => self.eval_record(values, env, None),
            Some("variant") => self.eval_variant(values, env),
            Some("module") => self.eval_module(values, env),
            Some("import") => self.eval_import(values, env),
            Some("tool") => self.eval_tool(values, env),
            Some("invoke") => self.eval_invoke(values, env),
            Some("field") => {
                expect_len(values, 3, "field")?;
                let target = self.eval(&values[1], env)?;
                let field = values[2]
                    .atom()
                    .and_then(|x| x.strip_prefix(':'))
                    .ok_or_else(|| YinError::language("field expects a keyword"))?;
                field_value(&target, field)
            }
            Some("decode-json") => self.eval_decode_json(values, env),
            Some("encode-json") => self.eval_encode_json(values, env),
            Some("json-schema") => self.eval_json_schema(values),
            _ => self.eval_call(values, env),
        }
    }

    fn eval_if(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 4, "if")?;
        match self.eval(&values[1], env)? {
            Value::Bool(true) => self.eval(&values[2], env),
            Value::Bool(false) => self.eval(&values[3], env),
            _ => Err(YinError::language("if condition must be Bool")),
        }
    }

    fn eval_define(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 3, "define")?;
        let value = self.eval(&values[2], env)?;
        bind_pattern(&values[1], value.clone(), env, true)?;
        Ok(value)
    }

    fn eval_assign(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 3, "set!")?;
        let name = values[1]
            .atom()
            .ok_or_else(|| YinError::language("set! expects a name"))?;
        let value = self.eval(&values[2], env)?;
        env.assign(name, value.clone())?;
        Ok(value)
    }

    fn eval_fun(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 3 {
            return Err(YinError::language("fun expects parameters and a body"));
        }
        let Expr::Form(Form::Tuple, forms, _) = &values[1] else {
            return Err(YinError::language("fun expects a parameter list"));
        };
        let mut parameters = Vec::new();
        for form in forms {
            match form {
                Expr::Atom(name, _) => parameters.push(Parameter {
                    name: name.clone(),
                    default: None,
                }),
                Expr::Form(Form::Vector, fields, _)
                    if fields.first().and_then(Expr::atom) == Some("->") => {}
                Expr::Form(Form::Vector, fields, _) => {
                    let name = fields
                        .first()
                        .and_then(Expr::atom)
                        .ok_or_else(|| YinError::language("invalid parameter"))?;
                    let default_expression = fields
                        .iter()
                        .position(|x| x.atom() == Some(":default"))
                        .and_then(|i| fields.get(i + 1))
                        .cloned();
                    let default = default_expression
                        .as_ref()
                        .map(|expression| self.eval(expression, env))
                        .transpose()?;
                    parameters.push(Parameter {
                        name: name.to_owned(),
                        default,
                    });
                }
                _ => return Err(YinError::language("invalid parameter")),
            }
        }
        Ok(Value::Function(Rc::new(Function {
            parameters,
            body: values[2..].to_vec(),
            environment: env.clone(),
        })))
    }

    fn eval_call(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        let callable = self.eval(&values[0], env)?;
        let mut positional = Vec::new();
        let mut keywords = IndexMap::new();
        let mut index = 1;
        while index < values.len() {
            if let Some(keyword) = values[index].atom().and_then(|x| x.strip_prefix(':')) {
                let next = values
                    .get(index + 1)
                    .ok_or_else(|| YinError::language("keyword argument is missing a value"))?;
                if keywords
                    .insert(keyword.to_owned(), self.eval(next, env)?)
                    .is_some()
                {
                    return Err(YinError::language("duplicate keyword argument"));
                }
                index += 2;
            } else {
                positional.push(self.eval(&values[index], env)?);
                index += 1;
            }
        }
        self.call(callable, positional, keywords)
    }

    fn call(
        &mut self,
        callable: Value,
        positional: Vec<Value>,
        keywords: IndexMap<String, Value>,
    ) -> Result<Value, YinError> {
        match callable {
            Value::Primitive(name) => self.primitive(name, positional),
            Value::Function(function) => {
                if !keywords.is_empty() && !positional.is_empty() {
                    return Err(YinError::language(
                        "cannot mix positional and keyword arguments",
                    ));
                }
                let call_env = function.environment.child();
                if keywords.is_empty() {
                    if positional.len() != function.parameters.len() {
                        return Err(YinError::language(format!(
                            "expected {} arguments, got {}",
                            function.parameters.len(),
                            positional.len()
                        )));
                    }
                    for (parameter, value) in function.parameters.iter().zip(positional) {
                        call_env.define(&parameter.name, value)?;
                    }
                } else {
                    for parameter in &function.parameters {
                        let value = if let Some(value) = keywords.get(&parameter.name) {
                            value.clone()
                        } else if let Some(default) = &parameter.default {
                            default.clone()
                        } else {
                            return Err(YinError::language(format!(
                                "missing argument: {}",
                                parameter.name
                            )));
                        };
                        call_env.define(&parameter.name, value)?;
                    }
                    if keywords
                        .keys()
                        .any(|key| !function.parameters.iter().any(|p| p.name == *key))
                    {
                        return Err(YinError::language("unknown keyword argument"));
                    }
                }
                self.eval_sequence(&function.body, &call_env)
            }
            Value::RecordDefinition(definition) => {
                self.construct_record(&definition, positional, keywords)
            }
            _ => Err(YinError::language("attempted to call a non-callable value")),
        }
    }

    fn primitive(&mut self, name: &str, args: Vec<Value>) -> Result<Value, YinError> {
        match name {
            "+" | "-" | "*" | "/" => numeric(name, &args),
            "<" | "<=" | ">" | ">=" => compare(name, &args),
            "=" => {
                arity(&args, 2, name)?;
                Ok(Value::Bool(args[0] == args[1]))
            }
            "and" | "or" => {
                arity(&args, 2, name)?;
                let (a, b) = (bool_value(&args[0])?, bool_value(&args[1])?);
                Ok(Value::Bool(if name == "and" { a && b } else { a || b }))
            }
            "not" => {
                arity(&args, 1, name)?;
                Ok(Value::Bool(!bool_value(&args[0])?))
            }
            "length" => {
                arity(&args, 1, name)?;
                Ok(Value::Int(BigInt::from(vector(&args[0])?.len())))
            }
            "at" => {
                arity(&args, 2, name)?;
                let values = vector(&args[0])?;
                let i = usize_value(&args[1])?;
                values
                    .get(i)
                    .cloned()
                    .ok_or_else(|| YinError::language("vector index out of bounds"))
            }
            "append" => {
                arity(&args, 2, name)?;
                let mut values = vector(&args[0])?.to_vec();
                values.extend(vector(&args[1])?.iter().cloned());
                Ok(Value::Vector(values))
            }
            "map" => {
                arity(&args, 2, name)?;
                let mut out = Vec::new();
                for v in vector(&args[0])? {
                    out.push(self.call(args[1].clone(), vec![v.clone()], IndexMap::new())?);
                }
                Ok(Value::Vector(out))
            }
            "filter" => {
                arity(&args, 2, name)?;
                let mut out = Vec::new();
                for v in vector(&args[0])? {
                    if bool_value(&self.call(args[1].clone(), vec![v.clone()], IndexMap::new())?)? {
                        out.push(v.clone())
                    }
                }
                Ok(Value::Vector(out))
            }
            "fold" => {
                arity(&args, 3, name)?;
                let mut acc = args[1].clone();
                for v in vector(&args[0])? {
                    acc = self.call(args[2].clone(), vec![acc, v.clone()], IndexMap::new())?;
                }
                Ok(acc)
            }
            "range" => primitive_range(&args),
            "slice" => {
                arity(&args, 3, name)?;
                let v = vector(&args[0])?;
                let a = usize_value(&args[1])?;
                let b = usize_value(&args[2])?;
                if a > b || b > v.len() {
                    Err(YinError::language("invalid slice bounds"))
                } else {
                    Ok(Value::Vector(v[a..b].to_vec()))
                }
            }
            "reverse" => {
                arity(&args, 1, name)?;
                let mut v = vector(&args[0])?.to_vec();
                v.reverse();
                Ok(Value::Vector(v))
            }
            "contains" => {
                arity(&args, 2, name)?;
                Ok(Value::Bool(vector(&args[0])?.contains(&args[1])))
            }
            "dict" => {
                if args.len() % 2 != 0 {
                    return Err(YinError::language("dict expects key/value pairs"));
                }
                let mut out = Vec::new();
                for pair in args.chunks(2) {
                    put_pair(&mut out, pair[0].clone(), pair[1].clone());
                }
                Ok(Value::Dict(out))
            }
            "dict/get" => {
                arity(&args, 2, name)?;
                Ok(Value::Option(
                    dict(&args[0])?
                        .iter()
                        .find(|(k, _)| k == &args[1])
                        .map(|(_, v)| Box::new(v.clone())),
                ))
            }
            "dict/put" => {
                arity(&args, 3, name)?;
                let mut d = dict(&args[0])?.to_vec();
                put_pair(&mut d, args[1].clone(), args[2].clone());
                Ok(Value::Dict(d))
            }
            "dict/remove" => {
                arity(&args, 2, name)?;
                Ok(Value::Dict(
                    dict(&args[0])?
                        .iter()
                        .filter(|(k, _)| k != &args[1])
                        .cloned()
                        .collect(),
                ))
            }
            "dict/keys" => {
                arity(&args, 1, name)?;
                Ok(Value::Vector(
                    dict(&args[0])?.iter().map(|(k, _)| k.clone()).collect(),
                ))
            }
            "dict/values" => {
                arity(&args, 1, name)?;
                Ok(Value::Vector(
                    dict(&args[0])?.iter().map(|(_, v)| v.clone()).collect(),
                ))
            }
            "dict/contains-key" => {
                arity(&args, 2, name)?;
                Ok(Value::Bool(
                    dict(&args[0])?.iter().any(|(k, _)| k == &args[1]),
                ))
            }
            "dict/size" => {
                arity(&args, 1, name)?;
                Ok(Value::Int(BigInt::from(dict(&args[0])?.len())))
            }
            "set" => Ok(Value::Set(unique(args))),
            "set/add" => {
                arity(&args, 2, name)?;
                let mut s = set(&args[0])?.to_vec();
                if !s.contains(&args[1]) {
                    s.push(args[1].clone())
                };
                Ok(Value::Set(s))
            }
            "set/remove" => {
                arity(&args, 2, name)?;
                Ok(Value::Set(
                    set(&args[0])?
                        .iter()
                        .filter(|v| *v != &args[1])
                        .cloned()
                        .collect(),
                ))
            }
            "set/contains" => {
                arity(&args, 2, name)?;
                Ok(Value::Bool(set(&args[0])?.contains(&args[1])))
            }
            "set/values" => {
                arity(&args, 1, name)?;
                Ok(Value::Vector(set(&args[0])?.to_vec()))
            }
            "set/size" => {
                arity(&args, 1, name)?;
                Ok(Value::Int(BigInt::from(set(&args[0])?.len())))
            }
            "set/union" => {
                arity(&args, 2, name)?;
                let mut s = set(&args[0])?.to_vec();
                for value in set(&args[1])? {
                    if !s.contains(value) {
                        s.push(value.clone());
                    }
                }
                Ok(Value::Set(s))
            }
            "set/intersection" => {
                arity(&args, 2, name)?;
                Ok(Value::Set(
                    set(&args[0])?
                        .iter()
                        .filter(|v| set(&args[1]).unwrap().contains(v))
                        .cloned()
                        .collect(),
                ))
            }
            "set/difference" => {
                arity(&args, 2, name)?;
                Ok(Value::Set(
                    set(&args[0])?
                        .iter()
                        .filter(|v| !set(&args[1]).unwrap().contains(v))
                        .cloned()
                        .collect(),
                ))
            }
            "ok" | "err" => {
                arity(&args, 1, name)?;
                Ok(Value::Result {
                    ok: name == "ok",
                    value: Box::new(args[0].clone()),
                })
            }
            "some" => {
                arity(&args, 1, name)?;
                Ok(Value::Option(Some(Box::new(args[0].clone()))))
            }
            "string-length" => {
                arity(&args, 1, name)?;
                Ok(Value::Int(BigInt::from(string(&args[0])?.chars().count())))
            }
            "concat" => {
                arity(&args, 2, name)?;
                Ok(Value::String(format!(
                    "{}{}",
                    string(&args[0])?,
                    string(&args[1])?
                )))
            }
            "substring" => primitive_substring(&args),
            "split" => {
                arity(&args, 2, name)?;
                Ok(Value::Vector(
                    string(&args[0])?
                        .split(string(&args[1])?)
                        .map(|s| Value::String(s.to_owned()))
                        .collect(),
                ))
            }
            "join" => {
                arity(&args, 2, name)?;
                let strings = vector(&args[1])?
                    .iter()
                    .map(string)
                    .collect::<Result<Vec<_>, _>>()?;
                Ok(Value::String(strings.join(string(&args[0])?)))
            }
            "trim" => {
                arity(&args, 1, name)?;
                Ok(Value::String(string(&args[0])?.trim().to_owned()))
            }
            "to-string" => {
                arity(&args, 1, name)?;
                Ok(Value::String(args[0].to_string()))
            }
            "parse-int" => {
                arity(&args, 1, name)?;
                Ok(match parse_integer(string(&args[0])?) {
                    Some(v) => Value::Int(v),
                    None => Value::Bool(false),
                })
            }
            "parse-float" => {
                arity(&args, 1, name)?;
                Ok(match string(&args[0])?.parse::<f64>().ok() {
                    Some(v) => Value::Float(v),
                    None => Value::Bool(false),
                })
            }
            "read-all" => {
                arity(&args, 0, name)?;
                Ok(Value::String(self.host.input.clone()))
            }
            "read-text" => {
                arity(&args, 1, name)?;
                if !self.host.allow_read {
                    return Err(YinError::language("read-text is unavailable"));
                };
                let path = self.resolve_resource(string(&args[0])?)?;
                Ok(Value::String(fs::read_to_string(&path).map_err(|e| {
                    YinError::io(format!("failed to read {}: {e}", path.display()))
                })?))
            }
            "print" => {
                self.output.push(
                    args.iter()
                        .map(ToString::to_string)
                        .collect::<Vec<_>>()
                        .join(", "),
                );
                Ok(Value::Void)
            }
            "U" => Ok(Value::Type(Type::Union(
                args.into_iter().map(value_type).collect(),
            ))),
            "Vector" | "Set" | "Option" => {
                arity(&args, 1, name)?;
                let t = Box::new(value_type(args[0].clone()));
                Ok(Value::Type(match name {
                    "Vector" => Type::Vector(t),
                    "Set" => Type::Set(t),
                    _ => Type::Option(t),
                }))
            }
            "Dict" | "Result" => {
                arity(&args, 2, name)?;
                let a = Box::new(value_type(args[0].clone()));
                let b = Box::new(value_type(args[1].clone()));
                Ok(Value::Type(if name == "Dict" {
                    Type::Dict(a, b)
                } else {
                    Type::Result(a, b)
                }))
            }
            "Fn" => Ok(Value::Type(Type::Any)),
            _ => Err(YinError::language(format!("unknown primitive: {name}"))),
        }
    }

    fn eval_match(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 3 {
            return Err(YinError::language("match expects a value and clauses"));
        }
        let target = self.eval(&values[1], env)?;
        for clause in &values[2..] {
            let Expr::Form(Form::Vector, parts, _) = clause else {
                return Err(YinError::language("match clause must be a vector"));
            };
            if parts.len() != 2 {
                return Err(YinError::language("match clause expects pattern and body"));
            }
            let child = env.child();
            if match_pattern(&parts[0], &target, &child)? {
                return self.eval(&parts[1], &child);
            }
        }
        Err(YinError::language("non-exhaustive match"))
    }

    fn eval_policy(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 5 {
            return Err(YinError::language(
                "policy expects name, parameters, rules, and otherwise",
            ));
        }
        let name = values[1]
            .atom()
            .ok_or_else(|| YinError::language("policy expects a name"))?
            .to_owned();
        let mut body = None;
        for rule in values[3..].iter().rev() {
            let Expr::Form(Form::Tuple, parts, _) = rule else {
                return Err(YinError::language("invalid policy rule"));
            };
            match parts.first().and_then(Expr::atom) {
                Some("otherwise") if parts.len() == 2 => body = Some(parts[1].clone()),
                Some("when") if parts.len() == 3 => {
                    let fallback =
                        body.ok_or_else(|| YinError::language("policy requires otherwise"))?;
                    body = Some(Expr::Form(
                        Form::Tuple,
                        vec![
                            atom_like("if", rule),
                            parts[1].clone(),
                            parts[2].clone(),
                            fallback,
                        ],
                        rule.span().clone(),
                    ))
                }
                _ => return Err(YinError::language("invalid policy rule")),
            }
        }
        let function = self.eval_fun(
            &[
                atom_like("fun", &values[0]),
                values[2].clone(),
                body.ok_or_else(|| YinError::language("policy requires otherwise"))?,
            ],
            env,
        )?;
        env.define(name, function.clone())?;
        Ok(function)
    }

    fn eval_record(
        &mut self,
        values: &[Expr],
        env: &Environment,
        variant: Option<String>,
    ) -> Result<Value, YinError> {
        if values.len() < 2 {
            return Err(YinError::language("record expects a name"));
        }
        let name = values[1]
            .atom()
            .ok_or_else(|| YinError::language("record expects a name"))?
            .to_owned();
        let mut index = 2;
        let mut parents = Vec::new();
        if let Some(Expr::Form(Form::Tuple, items, _)) = values.get(index) {
            parents = items
                .iter()
                .filter_map(Expr::atom)
                .map(str::to_owned)
                .collect();
            index += 1
        }
        let mut fields = Vec::new();
        for parent in &parents {
            let Some(Value::RecordDefinition(parent_definition)) = env.get(parent) else {
                return Err(YinError::language(format!(
                    "unknown parent record: {parent}"
                )));
            };
            fields.extend(parent_definition.fields.iter().cloned());
        }
        for field in &values[index..] {
            let Expr::Form(Form::Vector, parts, _) = field else {
                return Err(YinError::language("record field must be a vector"));
            };
            let field_name = parts
                .first()
                .and_then(Expr::atom)
                .ok_or_else(|| YinError::language("invalid record field"))?
                .to_owned();
            let field_type = parts
                .get(1)
                .cloned()
                .ok_or_else(|| YinError::language("record field requires a type"))?;
            let default_expression = parts
                .iter()
                .position(|x| x.atom() == Some(":default"))
                .and_then(|i| parts.get(i + 1))
                .cloned();
            let default = default_expression
                .as_ref()
                .map(|expression| self.eval(expression, env))
                .transpose()?;
            fields.push((field_name, field_type, default));
        }
        let definition = Value::RecordDefinition(Rc::new(RecordDefinition {
            name: name.clone(),
            fields,
            parents,
            variant,
        }));
        env.define(name, definition.clone())?;
        Ok(definition)
    }

    fn eval_variant(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 3 {
            return Err(YinError::language("variant expects cases"));
        }
        let variant = values[1]
            .atom()
            .ok_or_else(|| YinError::language("variant expects a name"))?
            .to_owned();
        env.define(&variant, Value::Type(Type::Named(variant.clone())))?;
        for case in &values[2..] {
            let Expr::Form(Form::Vector, parts, span) = case else {
                return Err(YinError::language("variant case must be a vector"));
            };
            let name = parts
                .first()
                .and_then(Expr::atom)
                .ok_or_else(|| YinError::language("invalid variant case"))?;
            let mut record = vec![
                Expr::Atom("record".into(), span.clone()),
                Expr::Atom(name.into(), span.clone()),
            ];
            record.extend_from_slice(&parts[1..]);
            self.eval_record(&record, env, Some(variant.clone()))?;
        }
        Ok(Value::Type(Type::Named(variant)))
    }

    fn construct_record(
        &mut self,
        definition: &RecordDefinition,
        positional: Vec<Value>,
        keywords: IndexMap<String, Value>,
    ) -> Result<Value, YinError> {
        if !positional.is_empty() && !keywords.is_empty() {
            return Err(YinError::language(
                "cannot mix positional and keyword fields",
            ));
        }
        let mut fields = IndexMap::new();
        if !positional.is_empty() {
            if positional.len() != definition.fields.len() {
                return Err(YinError::language("wrong record arity"));
            }
            for ((name, _, _), value) in definition.fields.iter().zip(positional) {
                fields.insert(name.clone(), value);
            }
        } else {
            for (name, _, default) in &definition.fields {
                let value = if let Some(v) = keywords.get(name) {
                    v.clone()
                } else if let Some(value) = default {
                    value.clone()
                } else {
                    return Err(YinError::language(format!("missing field: {name}")));
                };
                fields.insert(name.clone(), value);
            }
            if keywords
                .keys()
                .any(|k| !definition.fields.iter().any(|(n, _, _)| n == k))
            {
                return Err(YinError::language("unknown record field"));
            }
        }
        Ok(Value::Record {
            name: definition.name.clone(),
            fields,
            parents: definition.parents.clone(),
            variant: definition.variant.clone(),
        })
    }

    fn eval_module(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 4 {
            return Err(YinError::language("module expects name, exports, and body"));
        }
        self.eval_sequence(&values[3..], env)
    }

    fn eval_import(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 3, "import")?;
        let Expr::String(relative, _) = &values[1] else {
            return Err(YinError::language("import expects a string path"));
        };
        let Expr::Form(Form::Vector, names, _) = &values[2] else {
            return Err(YinError::language("import expects an import list"));
        };
        let base = self
            .current_file
            .as_ref()
            .and_then(|p| p.parent())
            .unwrap_or(Path::new("."));
        let path = base
            .join(relative)
            .canonicalize()
            .map_err(|e| YinError::io(format!("failed to resolve module {relative}: {e}")))?;
        if self.loading.contains(&path) {
            return Err(YinError::language("circular module import"));
        }
        if !self.modules.contains_key(&path) {
            self.loading.insert(path.clone());
            let source = fs::read_to_string(&path).map_err(|e| {
                YinError::io(format!("failed to read module {}: {e}", path.display()))
            })?;
            let program = parse(path.to_string_lossy(), &source)?;
            let module_env = self.initial_environment();
            let previous = self.current_file.replace(path.clone());
            let exports = eval_module_file(self, &program.expressions, &module_env)?;
            self.current_file = previous;
            self.modules.insert(path.clone(), exports);
            self.loading.remove(&path);
        }
        let exports = self.modules.get(&path).cloned().unwrap();
        for name in names {
            let name = name
                .atom()
                .ok_or_else(|| YinError::language("invalid import name"))?;
            let value = exports
                .get(name)
                .cloned()
                .ok_or_else(|| YinError::language(format!("undefined export: {name}")))?;
            env.define(name, value)?;
        }
        Ok(Value::Void)
    }

    fn eval_tool(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        if values.len() < 5 {
            return Err(YinError::language(
                "tool expects name, input, output, and error types",
            ));
        }
        let name = values[1].atom().unwrap().to_owned();
        let capability = property_atom(values, ":capability").unwrap_or_default();
        let effect = property_atom(values, ":effect").unwrap_or(":read".into());
        let approval = property_atom(values, ":approval").as_deref() == Some("true");
        let tool = Value::Tool {
            name: name.clone(),
            input: values[2].clone(),
            output: values[3].clone(),
            error: values[4].clone(),
            capability,
            effect,
            approval,
        };
        env.define(name, tool.clone())?;
        Ok(tool)
    }
    fn eval_invoke(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 3, "invoke")?;
        let tool = self.eval(&values[1], env)?;
        let input = self.eval(&values[2], env)?;
        let Value::Tool {
            name,
            output,
            error: error_type,
            ..
        } = tool
        else {
            return Err(YinError::language("invoke expects a Tool"));
        };
        let Some(executor) = &self.host.tool_executor else {
            return Ok(Value::Result {
                ok: false,
                value: Box::new(tool_error(
                    "unavailable",
                    &name,
                    "tool invocation requires a configured host gateway",
                )),
            });
        };
        let input_json = to_json(&input)?;
        match executor(&name, &input_json) {
            Ok(result) => match from_json_typed(&output, result, env) {
                Ok(value) => Ok(Value::Result {
                    ok: true,
                    value: Box::new(value),
                }),
                Err(error) => Ok(Value::Result {
                    ok: false,
                    value: Box::new(tool_error("invalid-output", &name, &error.to_string())),
                }),
            },
            Err(error) => {
                let _ = error_type;
                Ok(Value::Result {
                    ok: false,
                    value: Box::new(tool_error("remote-error", &name, &error.to_string())),
                })
            }
        }
    }

    fn eval_decode_json(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 3, "decode-json")?;
        let text = string(&self.eval(&values[2], env)?)?.to_owned();
        Ok(match serde_json::from_str::<JsonValue>(&text) {
            Ok(json) => match from_json_typed(&values[1], json, env) {
                Ok(value) => Value::Result {
                    ok: true,
                    value: Box::new(value),
                },
                Err(error) => Value::Result {
                    ok: false,
                    value: Box::new(decode_error(error.to_string())),
                },
            },
            Err(e) => Value::Result {
                ok: false,
                value: Box::new(decode_error(e.to_string())),
            },
        })
    }
    fn eval_encode_json(&mut self, values: &[Expr], env: &Environment) -> Result<Value, YinError> {
        expect_len(values, 2, "encode-json")?;
        let value = self.eval(&values[1], env)?;
        match to_json(&value) {
            Ok(json) => Ok(Value::Result {
                ok: true,
                value: Box::new(Value::String(serde_json::to_string(&json).unwrap())),
            }),
            Err(e) => Ok(Value::Result {
                ok: false,
                value: Box::new(decode_error(e.to_string())),
            }),
        }
    }
    fn eval_json_schema(&self, values: &[Expr]) -> Result<Value, YinError> {
        expect_len(values, 2, "json-schema")?;
        Ok(Value::String(
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}".into(),
        ))
    }

    fn resolve_resource(&self, resource: &str) -> Result<PathBuf, YinError> {
        let base = self.host.root.as_deref().unwrap_or(Path::new("."));
        let path = base
            .join(resource)
            .canonicalize()
            .map_err(|e| YinError::io(format!("failed to resolve resource: {e}")))?;
        if !path.starts_with(base.canonicalize().unwrap_or_else(|_| base.to_path_buf())) {
            return Err(YinError::language("resource escapes host root"));
        }
        Ok(path)
    }
}

fn eval_module_file(
    engine: &mut Engine,
    expressions: &[Expr],
    env: &Environment,
) -> Result<IndexMap<String, Value>, YinError> {
    if expressions.len() != 1 {
        return Err(YinError::language(
            "module file must contain exactly one module",
        ));
    }
    let Expr::Form(Form::Tuple, parts, _) = &expressions[0] else {
        return Err(YinError::language("module file must contain a module"));
    };
    if parts.first().and_then(Expr::atom) != Some("module") || parts.len() < 4 {
        return Err(YinError::language("module file must contain a module"));
    }
    let Expr::Form(Form::Vector, names, _) = &parts[2] else {
        return Err(YinError::language("module exports must be a vector"));
    };
    engine.eval_sequence(&parts[3..], env)?;
    let mut exports = IndexMap::new();
    for name in names {
        let name = name
            .atom()
            .ok_or_else(|| YinError::language("invalid export"))?;
        exports.insert(
            name.into(),
            env.get(name)
                .ok_or_else(|| YinError::language(format!("undefined export: {name}")))?,
        );
    }
    Ok(exports)
}

fn bind_pattern(
    pattern: &Expr,
    value: Value,
    env: &Environment,
    define: bool,
) -> Result<(), YinError> {
    match pattern {
        Expr::Atom(name, _) => {
            if define {
                env.define(name, value)
            } else {
                env.assign(name, value)
            }
        }
        Expr::Form(Form::Vector, patterns, _) => {
            let Value::Vector(values) = value else {
                return Err(YinError::language("vector destructuring expects a vector"));
            };
            if patterns.len() != values.len() {
                return Err(YinError::language("vector destructuring length mismatch"));
            }
            for (p, v) in patterns.iter().zip(values) {
                bind_pattern(p, v, env, define)?
            }
            Ok(())
        }
        _ => Err(YinError::language("invalid binding pattern")),
    }
}

fn match_pattern(pattern: &Expr, value: &Value, env: &Environment) -> Result<bool, YinError> {
    match pattern {
        Expr::Atom(name, _) if name == "_" => Ok(true),
        Expr::Atom(name, _) if name == "true" || name == "false" => {
            Ok(value == &Value::Bool(name == "true"))
        }
        Expr::Atom(name, _) => {
            if let Some(literal) = parse_integer(name) {
                Ok(value == &Value::Int(literal))
            } else {
                env.define(name, value.clone())?;
                Ok(true)
            }
        }
        Expr::String(text, _) => Ok(value == &Value::String(text.clone())),
        Expr::Form(Form::Vector, patterns, _) => {
            let Value::Vector(values) = value else {
                return Ok(false);
            };
            if patterns.len() != values.len() {
                return Ok(false);
            }
            for (p, v) in patterns.iter().zip(values) {
                if !match_pattern(p, v, env)? {
                    return Ok(false);
                }
            }
            Ok(true)
        }
        Expr::Form(Form::Tuple, parts, _) => {
            let tag = parts.first().and_then(Expr::atom).unwrap_or("");
            match (tag, value) {
                ("Ok", Value::Result { ok: true, value })
                | ("Err", Value::Result { ok: false, value })
                | ("Some", Value::Option(Some(value))) => parts
                    .get(1)
                    .map_or(Ok(true), |p| match_pattern(p, value, env)),
                ("None", Value::Option(None)) => Ok(true),
                ("Int", Value::Int(_))
                | ("Float", Value::Float(_))
                | ("Bool", Value::Bool(_))
                | ("String", Value::String(_)) => parts
                    .get(1)
                    .map_or(Ok(true), |p| match_pattern(p, value, env)),
                (
                    name,
                    Value::Record {
                        name: actual,
                        fields,
                        parents,
                        ..
                    },
                ) if name == actual || parents.iter().any(|p| p == name) => {
                    if parts.len() - 1 != fields.len() {
                        return Ok(false);
                    }
                    for (p, v) in parts[1..].iter().zip(fields.values()) {
                        if !match_pattern(p, v, env)? {
                            return Ok(false);
                        }
                    }
                    Ok(true)
                }
                _ => Ok(false),
            }
        }
    }
}

fn parse_integer(atom: &str) -> Option<BigInt> {
    let (s, radix) = if let Some(v) = atom.strip_prefix("-0x") {
        (format!("-{v}"), 16)
    } else if let Some(v) = atom.strip_prefix("0x") {
        (v.into(), 16)
    } else if let Some(v) = atom.strip_prefix("-0b") {
        (format!("-{v}"), 2)
    } else if let Some(v) = atom.strip_prefix("0b") {
        (v.into(), 2)
    } else {
        (atom.into(), 10)
    };
    BigInt::parse_bytes(s.as_bytes(), radix)
}
fn expect_len(values: &[Expr], n: usize, name: &str) -> Result<(), YinError> {
    if values.len() == n {
        Ok(())
    } else {
        Err(YinError::language(format!(
            "{name} expects {} arguments",
            n - 1
        )))
    }
}
fn arity(values: &[Value], n: usize, name: &str) -> Result<(), YinError> {
    if values.len() == n {
        Ok(())
    } else {
        Err(YinError::language(format!("{name} expects {n} arguments")))
    }
}
fn vector(value: &Value) -> Result<&[Value], YinError> {
    if let Value::Vector(v) = value {
        Ok(v)
    } else {
        Err(YinError::language("expected Vector"))
    }
}
fn dict(value: &Value) -> Result<&[(Value, Value)], YinError> {
    if let Value::Dict(v) = value {
        Ok(v)
    } else {
        Err(YinError::language("expected Dict"))
    }
}
fn set(value: &Value) -> Result<&[Value], YinError> {
    if let Value::Set(v) = value {
        Ok(v)
    } else {
        Err(YinError::language("expected Set"))
    }
}
fn string(value: &Value) -> Result<&str, YinError> {
    if let Value::String(v) = value {
        Ok(v)
    } else {
        Err(YinError::language("expected String"))
    }
}
fn bool_value(value: &Value) -> Result<bool, YinError> {
    if let Value::Bool(v) = value {
        Ok(*v)
    } else {
        Err(YinError::language("expected Bool"))
    }
}
fn usize_value(value: &Value) -> Result<usize, YinError> {
    if let Value::Int(v) = value {
        v.to_usize()
            .ok_or_else(|| YinError::language("expected non-negative Int"))
    } else {
        Err(YinError::language("expected Int"))
    }
}
fn numeric(name: &str, args: &[Value]) -> Result<Value, YinError> {
    arity(args, 2, name)?;
    match (&args[0], &args[1]) {
        (Value::Int(a), Value::Int(b)) => Ok(Value::Int(match name {
            "+" => a + b,
            "-" => a - b,
            "*" => a * b,
            "/" => {
                if b.is_zero() {
                    return Err(YinError::language("division by zero"));
                }
                a / b
            }
            _ => unreachable!(),
        })),
        _ => {
            let a = number(&args[0])?;
            let b = number(&args[1])?;
            if name == "/" && b == 0.0 {
                return Err(YinError::language("division by zero"));
            }
            Ok(Value::Float(match name {
                "+" => a + b,
                "-" => a - b,
                "*" => a * b,
                "/" => a / b,
                _ => unreachable!(),
            }))
        }
    }
}
fn number(value: &Value) -> Result<f64, YinError> {
    match value {
        Value::Int(v) => v
            .to_f64()
            .ok_or_else(|| YinError::language("number out of range")),
        Value::Float(v) => Ok(*v),
        _ => Err(YinError::language("expected number")),
    }
}
fn compare(name: &str, args: &[Value]) -> Result<Value, YinError> {
    arity(args, 2, name)?;
    let a = number(&args[0])?;
    let b = number(&args[1])?;
    Ok(Value::Bool(match name {
        "<" => a < b,
        "<=" => a <= b,
        ">" => a > b,
        ">=" => a >= b,
        _ => false,
    }))
}
fn primitive_range(args: &[Value]) -> Result<Value, YinError> {
    if args.len() != 2 && args.len() != 3 {
        return Err(YinError::language("range expects 2 or 3 arguments"));
    }
    let start = number(&args[0])? as i64;
    let end = number(&args[1])? as i64;
    let step = if args.len() == 3 {
        number(&args[2])? as i64
    } else {
        1
    };
    if step == 0 {
        return Err(YinError::language("range step cannot be zero"));
    }
    let mut out = Vec::new();
    let mut i = start;
    while if step > 0 { i < end } else { i > end } {
        out.push(Value::Int(i.into()));
        i += step;
    }
    Ok(Value::Vector(out))
}
fn primitive_substring(args: &[Value]) -> Result<Value, YinError> {
    arity(args, 3, "substring")?;
    let chars = string(&args[0])?.chars().collect::<Vec<_>>();
    let a = usize_value(&args[1])?;
    let b = usize_value(&args[2])?;
    if a > b || b > chars.len() {
        return Err(YinError::language("invalid substring bounds"));
    }
    Ok(Value::String(chars[a..b].iter().collect()))
}
fn unique(values: Vec<Value>) -> Vec<Value> {
    let mut out = Vec::new();
    for v in values {
        if !out.contains(&v) {
            out.push(v)
        }
    }
    out
}
fn put_pair(entries: &mut Vec<(Value, Value)>, key: Value, value: Value) {
    if let Some(pair) = entries.iter_mut().find(|(k, _)| k == &key) {
        pair.1 = value
    } else {
        entries.push((key, value))
    }
}
fn field_value(value: &Value, field: &str) -> Result<Value, YinError> {
    if let Value::Record { fields, .. } = value {
        fields
            .get(field)
            .cloned()
            .ok_or_else(|| YinError::language(format!("unknown field: {field}")))
    } else {
        Err(YinError::language("field access expects a record"))
    }
}
fn atom_like(value: &str, expr: &Expr) -> Expr {
    Expr::Atom(value.into(), expr.span().clone())
}
fn property_atom(values: &[Expr], name: &str) -> Option<String> {
    values
        .iter()
        .position(|v| v.atom() == Some(name))
        .and_then(|i| values.get(i + 1))
        .and_then(|v| match v {
            Expr::String(s, _) => Some(s.clone()),
            Expr::Atom(s, _) => Some(s.clone()),
            _ => None,
        })
}
fn value_type(value: Value) -> Type {
    if let Value::Type(t) = value {
        t
    } else {
        Type::Any
    }
}
fn decode_error(message: String) -> Value {
    let mut fields = IndexMap::new();
    fields.insert("code".into(), Value::String("invalid-json".into()));
    fields.insert("path".into(), Value::String("$".into()));
    fields.insert("message".into(), Value::String(message));
    Value::Record {
        name: "DecodeError".into(),
        fields,
        parents: vec![],
        variant: None,
    }
}
fn tool_error(code: &str, tool: &str, message: &str) -> Value {
    let mut fields = IndexMap::new();
    fields.insert("code".into(), Value::String(code.into()));
    fields.insert("tool".into(), Value::String(tool.into()));
    fields.insert("message".into(), Value::String(message.into()));
    Value::Record {
        name: "ToolError".into(),
        fields,
        parents: vec![],
        variant: None,
    }
}
fn from_json(value: JsonValue) -> Value {
    match value {
        JsonValue::Null => Value::Option(None),
        JsonValue::Bool(v) => Value::Bool(v),
        JsonValue::Number(v) => v
            .as_i64()
            .map(|i| Value::Int(i.into()))
            .or_else(|| v.as_u64().map(|i| Value::Int(i.into())))
            .unwrap_or_else(|| Value::Float(v.as_f64().unwrap())),
        JsonValue::String(v) => Value::String(v),
        JsonValue::Array(v) => Value::Vector(v.into_iter().map(from_json).collect()),
        JsonValue::Object(v) => Value::Dict(
            v.into_iter()
                .map(|(k, v)| (Value::String(k), from_json(v)))
                .collect(),
        ),
    }
}

fn from_json_typed(kind: &Expr, value: JsonValue, env: &Environment) -> Result<Value, YinError> {
    match kind {
        Expr::Atom(name, _) => match name.as_str() {
            "Any" => Ok(from_json(value)),
            "Int" => value
                .as_i64()
                .map(|v| Value::Int(v.into()))
                .or_else(|| value.as_u64().map(|v| Value::Int(v.into())))
                .ok_or_else(|| YinError::language("expected JSON integer")),
            "Float" => value
                .as_f64()
                .map(Value::Float)
                .ok_or_else(|| YinError::language("expected JSON number")),
            "Bool" => value
                .as_bool()
                .map(Value::Bool)
                .ok_or_else(|| YinError::language("expected JSON boolean")),
            "String" => value
                .as_str()
                .map(|v| Value::String(v.to_owned()))
                .ok_or_else(|| YinError::language("expected JSON string")),
            _ => {
                if let Some(Value::RecordDefinition(definition)) = env.get(name) {
                    return decode_record(&definition, value, env);
                }
                if let JsonValue::Object(object) = &value {
                    if let Some(tag) = object.get("tag").and_then(JsonValue::as_str) {
                        if let Some(Value::RecordDefinition(definition)) = env.get(tag) {
                            if definition.variant.as_deref() == Some(name) {
                                return decode_record(&definition, value, env);
                            }
                        }
                    }
                }
                Err(YinError::language(format!(
                    "unknown JSON contract type: {name}"
                )))
            }
        },
        Expr::Form(Form::Tuple, parts, _) => {
            let operation = parts.first().and_then(Expr::atom).unwrap_or("");
            match operation {
                "Vector" => {
                    let element = parts
                        .get(1)
                        .ok_or_else(|| YinError::language("Vector requires an element type"))?;
                    let JsonValue::Array(values) = value else {
                        return Err(YinError::language("expected JSON array"));
                    };
                    Ok(Value::Vector(
                        values
                            .into_iter()
                            .map(|value| from_json_typed(element, value, env))
                            .collect::<Result<_, _>>()?,
                    ))
                }
                "Set" => {
                    let element = parts
                        .get(1)
                        .ok_or_else(|| YinError::language("Set requires an element type"))?;
                    let JsonValue::Array(values) = value else {
                        return Err(YinError::language("expected JSON array"));
                    };
                    Ok(Value::Set(unique(
                        values
                            .into_iter()
                            .map(|value| from_json_typed(element, value, env))
                            .collect::<Result<_, _>>()?,
                    )))
                }
                "Dict" => {
                    let key = parts
                        .get(1)
                        .ok_or_else(|| YinError::language("Dict requires a key type"))?;
                    if key.atom() != Some("String") {
                        return Err(YinError::language("JSON dictionaries require String keys"));
                    }
                    let element = parts
                        .get(2)
                        .ok_or_else(|| YinError::language("Dict requires a value type"))?;
                    let JsonValue::Object(values) = value else {
                        return Err(YinError::language("expected JSON object"));
                    };
                    Ok(Value::Dict(
                        values
                            .into_iter()
                            .map(|(key, value)| {
                                Ok((Value::String(key), from_json_typed(element, value, env)?))
                            })
                            .collect::<Result<_, YinError>>()?,
                    ))
                }
                "Option" => {
                    if value.is_null() {
                        Ok(Value::Option(None))
                    } else {
                        Ok(Value::Option(Some(Box::new(from_json_typed(
                            parts
                                .get(1)
                                .ok_or_else(|| YinError::language("Option requires a type"))?,
                            value,
                            env,
                        )?))))
                    }
                }
                _ => Err(YinError::language(format!(
                    "unsupported JSON contract: {operation}"
                ))),
            }
        }
        _ => Err(YinError::language("invalid JSON contract type")),
    }
}

fn decode_record(
    definition: &RecordDefinition,
    value: JsonValue,
    env: &Environment,
) -> Result<Value, YinError> {
    let JsonValue::Object(mut object) = value else {
        return Err(YinError::language(format!(
            "expected JSON object for {}",
            definition.name
        )));
    };
    if definition.variant.is_some() {
        let tag = object
            .remove("tag")
            .and_then(|v| v.as_str().map(str::to_owned))
            .ok_or_else(|| YinError::language("variant JSON requires tag"))?;
        if tag != definition.name {
            return Err(YinError::language(format!(
                "expected variant tag {}, got {tag}",
                definition.name
            )));
        }
    }
    let mut fields = IndexMap::new();
    for (name, kind, _default) in &definition.fields {
        let field = object
            .remove(name)
            .ok_or_else(|| YinError::language(format!("missing JSON field: {name}")))?;
        fields.insert(name.clone(), from_json_typed(kind, field, env)?);
    }
    if let Some(name) = object.keys().next() {
        return Err(YinError::language(format!("unknown JSON field: {name}")));
    }
    Ok(Value::Record {
        name: definition.name.clone(),
        fields,
        parents: definition.parents.clone(),
        variant: definition.variant.clone(),
    })
}

fn to_json(value: &Value) -> Result<JsonValue, YinError> {
    Ok(match value {
        Value::Void => JsonValue::Null,
        Value::Int(v) => serde_json::from_str(&v.to_string())
            .map_err(|_| YinError::language("integer is outside JSON range"))?,
        Value::Float(v) => serde_json::Number::from_f64(*v)
            .map(JsonValue::Number)
            .ok_or_else(|| YinError::language("non-finite Float"))?,
        Value::Bool(v) => JsonValue::Bool(*v),
        Value::String(v) => JsonValue::String(v.clone()),
        Value::Vector(v) | Value::Set(v) => {
            JsonValue::Array(v.iter().map(to_json).collect::<Result<_, _>>()?)
        }
        Value::Dict(v) => {
            let mut m = JsonMap::new();
            for (k, v) in v {
                m.insert(string(k)?.into(), to_json(v)?);
            }
            JsonValue::Object(m)
        }
        Value::Record {
            name,
            fields,
            variant,
            ..
        } => {
            let mut m = JsonMap::new();
            if variant.is_some() {
                m.insert("tag".into(), JsonValue::String(name.clone()));
            }
            for (k, v) in fields {
                m.insert(k.clone(), to_json(v)?);
            }
            JsonValue::Object(m)
        }
        Value::Result { ok, value } => {
            let mut m = JsonMap::new();
            m.insert(
                "tag".into(),
                JsonValue::String(if *ok { "Ok" } else { "Err" }.into()),
            );
            m.insert(if *ok { "value" } else { "error" }.into(), to_json(value)?);
            JsonValue::Object(m)
        }
        Value::Option(Some(v)) => to_json(v)?,
        Value::Option(None) => JsonValue::Null,
        _ => return Err(YinError::language("value is not JSON encodable")),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(source: &str) -> Value {
        Engine::new(Host::default())
            .run_source("test.yin", source)
            .unwrap()
            .value
    }
    #[test]
    fn arithmetic_and_closures() {
        assert_eq!(
            run("(define twice (fun (x) (* x 2))) (twice 21)"),
            Value::Int(42.into())
        )
    }
    #[test]
    fn collections_are_immutable() {
        assert_eq!(
            run("(define d (dict \"a\" 1)) (dict/get (dict/put d \"b\" 2) \"b\")"),
            Value::Option(Some(Box::new(Value::Int(2.into()))))
        )
    }
}
