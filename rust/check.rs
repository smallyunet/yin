use crate::{Expr, Form, ParsedProgram, Type, YinError};
use indexmap::IndexMap;

pub fn check_program(program: &ParsedProgram) -> Result<Type, YinError> {
    let mut checker = Checker::new();
    checker.sequence(&program.expressions)
}

struct Checker {
    scopes: Vec<IndexMap<String, Type>>,
    records: IndexMap<String, Vec<(String, Type)>>,
}

impl Checker {
    fn new() -> Self {
        let mut root = IndexMap::new();
        for (name, kind) in [("true", Type::Bool), ("false", Type::Bool)] {
            root.insert(name.into(), kind);
        }
        root.insert("none".into(), Type::Option(Box::new(Type::Never)));
        root.insert("args".into(), Type::Vector(Box::new(Type::String)));
        for name in ["Int", "Float", "Bool", "String", "Any"] {
            root.insert(name.into(), Type::Any);
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
            root.insert(name.into(), builtin(name));
        }
        Self {
            scopes: vec![root],
            records: IndexMap::new(),
        }
    }

    fn sequence(&mut self, expressions: &[Expr]) -> Result<Type, YinError> {
        for expression in expressions {
            if let Expr::Form(Form::Tuple, parts, _) = expression {
                if parts.first().and_then(Expr::atom) == Some("define") {
                    if let Some(name) = parts.get(1).and_then(Expr::atom) {
                        if self
                            .scopes
                            .last()
                            .is_some_and(|scope| !scope.contains_key(name))
                        {
                            self.scopes
                                .last_mut()
                                .unwrap()
                                .insert(name.into(), Type::Any);
                        }
                    }
                }
            }
        }
        let mut result = Type::Void;
        for expression in expressions {
            result = self.expression(expression)?;
        }
        Ok(result)
    }

    fn expression(&mut self, expression: &Expr) -> Result<Type, YinError> {
        match expression {
            Expr::String(_, _) => Ok(Type::String),
            Expr::Atom(value, _) if integer(value) => Ok(Type::Int),
            Expr::Atom(value, _) if value.parse::<f64>().is_ok() && value.contains('.') => {
                Ok(Type::Float)
            }
            Expr::Atom(value, _) if value.contains('.') && !value.starts_with(':') => {
                let first = value.split('.').next().unwrap();
                self.lookup(first).map(|_| Type::Any)
            }
            Expr::Atom(value, _) => self.lookup(value),
            Expr::Form(Form::Vector, values, _) => {
                let types = values
                    .iter()
                    .map(|v| self.expression(v))
                    .collect::<Result<Vec<_>, _>>()?;
                Ok(Type::Vector(Box::new(union(types))))
            }
            Expr::Form(Form::Tuple, values, _) if values.is_empty() => Ok(Type::Void),
            Expr::Form(Form::Tuple, values, _) => self.tuple(values),
        }
    }

    fn tuple(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        match values[0].atom() {
            Some("seq") => self.sequence(&values[1..]),
            Some("if") => {
                arity_expr(values, 4, "if")?;
                require(self.expression(&values[1])?, &Type::Bool, "if condition")?;
                Ok(union(vec![
                    self.expression(&values[2])?,
                    self.expression(&values[3])?,
                ]))
            }
            Some("define") => {
                arity_expr(values, 3, "define")?;
                let kind = self.expression(&values[2])?;
                if let Some(name) = values[1].atom() {
                    self.scopes
                        .last_mut()
                        .unwrap()
                        .insert(name.into(), kind.clone());
                } else {
                    self.bind(&values[1], kind.clone())?;
                }
                Ok(kind)
            }
            Some("set!") => {
                arity_expr(values, 3, "set!")?;
                let name = values[1]
                    .atom()
                    .ok_or_else(|| YinError::language("set! expects a name"))?;
                let expected = self.lookup(name)?;
                let actual = self.expression(&values[2])?;
                require(actual, &expected, "assignment")?;
                Ok(expected)
            }
            Some("fun") => self.function(values),
            Some("match") => self.match_expression(values),
            Some("policy") => self.policy(values),
            Some("record") => self.record(values, None),
            Some("variant") => self.variant(values),
            Some("module") => self.sequence(&values[3..]),
            Some("import") => {
                if let Some(Expr::Form(Form::Vector, names, _)) = values.get(2) {
                    for name in names {
                        if let Some(name) = name.atom() {
                            self.define(name, Type::Any)?
                        }
                    }
                }
                Ok(Type::Void)
            }
            Some("tool") => {
                let name = values
                    .get(1)
                    .and_then(Expr::atom)
                    .ok_or_else(|| YinError::language("tool expects a name"))?;
                self.define(name, Type::Named("Tool".into()))?;
                Ok(Type::Named("Tool".into()))
            }
            Some("invoke") => {
                arity_expr(values, 3, "invoke")?;
                self.expression(&values[1])?;
                self.expression(&values[2])?;
                Ok(Type::Result(Box::new(Type::Any), Box::new(Type::Any)))
            }
            Some("field") => {
                arity_expr(values, 3, "field")?;
                self.expression(&values[1])?;
                Ok(Type::Any)
            }
            Some("decode-json") => {
                arity_expr(values, 3, "decode-json")?;
                require(
                    self.expression(&values[2])?,
                    &Type::String,
                    "decode-json input",
                )?;
                Ok(Type::Result(
                    Box::new(type_expression(&values[1])?),
                    Box::new(Type::Named("DecodeError".into())),
                ))
            }
            Some("encode-json") => {
                arity_expr(values, 2, "encode-json")?;
                self.expression(&values[1])?;
                Ok(Type::Result(
                    Box::new(Type::String),
                    Box::new(Type::Named("EncodeError".into())),
                ))
            }
            Some("json-schema") => Ok(Type::String),
            _ => self.call(values),
        }
    }

    fn function(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        if values.len() < 3 {
            return Err(YinError::language("fun expects a body"));
        }
        let Expr::Form(Form::Tuple, parameters, _) = &values[1] else {
            return Err(YinError::language("fun expects parameters"));
        };
        let mut inputs = Vec::new();
        let mut result = Type::Any;
        self.scopes.push(IndexMap::new());
        for parameter in parameters {
            match parameter {
                Expr::Atom(name, _) => {
                    inputs.push(Type::Any);
                    self.define(name, Type::Any)?
                }
                Expr::Form(Form::Vector, parts, _)
                    if parts.first().and_then(Expr::atom) == Some("->") =>
                {
                    result = type_expression(
                        parts
                            .get(1)
                            .ok_or_else(|| YinError::language("return type is missing"))?,
                    )?
                }
                Expr::Form(Form::Vector, parts, _) => {
                    let name = parts
                        .first()
                        .and_then(Expr::atom)
                        .ok_or_else(|| YinError::language("parameter name is missing"))?;
                    let kind = type_expression(
                        parts
                            .get(1)
                            .ok_or_else(|| YinError::language("parameter type is missing"))?,
                    )?;
                    inputs.push(kind.clone());
                    self.define(name, kind)?
                }
                _ => return Err(YinError::language("invalid parameter")),
            }
        }
        let actual = self.sequence(&values[2..])?;
        self.scopes.pop();
        require(actual, &result, "function return")?;
        Ok(Type::Function(inputs, Box::new(result)))
    }
    fn call(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let callable = self.expression(&values[0])?;
        let mut arguments = Vec::new();
        let mut index = 1;
        while index < values.len() {
            if values[index].atom().is_some_and(|v| v.starts_with(':')) {
                index += 1;
                if index >= values.len() {
                    return Err(YinError::language("keyword argument has no value"));
                }
            }
            arguments.push(self.expression(&values[index])?);
            index += 1
        }
        match callable {
            Type::Function(parameters, result) => {
                if parameters.len() != arguments.len() && parameters.len() != usize::MAX {
                    return Err(YinError::language(format!(
                        "expected {} arguments, got {}",
                        parameters.len(),
                        arguments.len()
                    )));
                }
                for (actual, expected) in arguments.into_iter().zip(parameters) {
                    require(actual, &expected, "call argument")?
                }
                Ok(*result)
            }
            Type::Any => Ok(Type::Any),
            Type::Named(name) if self.records.contains_key(&name) => Ok(Type::Named(name)),
            _ => Err(YinError::language("attempted to call a non-function")),
        }
    }
    fn record(&mut self, values: &[Expr], variant: Option<String>) -> Result<Type, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| YinError::language("record expects a name"))?
            .to_owned();
        let mut fields = Vec::new();
        if let Some(Expr::Form(Form::Tuple, parents, _)) = values.get(2) {
            for parent in parents {
                let parent = parent
                    .atom()
                    .ok_or_else(|| YinError::language("invalid parent record"))?;
                fields.extend(self.records.get(parent).cloned().ok_or_else(|| {
                    YinError::language(format!("unknown parent record: {parent}"))
                })?);
            }
        }
        for value in &values[2..] {
            if let Expr::Form(Form::Vector, parts, _) = value {
                let field = parts
                    .first()
                    .and_then(Expr::atom)
                    .ok_or_else(|| YinError::language("invalid record field"))?;
                fields.push((
                    field.into(),
                    type_expression(
                        parts
                            .get(1)
                            .ok_or_else(|| YinError::language("record field type is missing"))?,
                    )?,
                ));
            }
        }
        self.records.insert(name.clone(), fields);
        self.define(&name, Type::Named(name.clone()))?;
        let _ = variant;
        Ok(Type::Named(name))
    }
    fn variant(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| YinError::language("variant expects a name"))?
            .to_owned();
        for case in &values[2..] {
            if let Expr::Form(Form::Vector, parts, span) = case {
                let mut record = vec![Expr::Atom("record".into(), span.clone()), parts[0].clone()];
                record.extend_from_slice(&parts[1..]);
                self.record(&record, Some(name.clone()))?;
            }
        }
        Ok(Type::Named(name))
    }
    fn policy(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        if values.len() < 5 {
            return Err(YinError::language("policy expects rules"));
        }
        let name = values[1]
            .atom()
            .ok_or_else(|| YinError::language("policy expects a name"))?
            .to_owned();
        let mut body = None;
        for rule in values[3..].iter().rev() {
            let Expr::Form(Form::Tuple, parts, span) = rule else {
                return Err(YinError::language("invalid policy rule"));
            };
            match parts.first().and_then(Expr::atom) {
                Some("otherwise") => body = parts.get(1).cloned(),
                Some("when") => {
                    let fallback =
                        body.ok_or_else(|| YinError::language("policy requires otherwise"))?;
                    body = Some(Expr::Form(
                        Form::Tuple,
                        vec![
                            Expr::Atom("if".into(), span.clone()),
                            parts[1].clone(),
                            parts[2].clone(),
                            fallback,
                        ],
                        span.clone(),
                    ))
                }
                _ => return Err(YinError::language("invalid policy rule")),
            }
        }
        let function = self.function(&[values[0].clone(), values[2].clone(), body.unwrap()])?;
        self.define(&name, function.clone())?;
        Ok(function)
    }
    fn match_expression(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        if values.len() < 3 {
            return Err(YinError::language("match expects clauses"));
        }
        let target = self.expression(&values[1])?;
        let mut branches = Vec::new();
        for clause in &values[2..] {
            let Expr::Form(Form::Vector, parts, _) = clause else {
                return Err(YinError::language("match clause must be a vector"));
            };
            self.scopes.push(IndexMap::new());
            bind_match(
                parts
                    .first()
                    .ok_or_else(|| YinError::language("match pattern missing"))?,
                &target,
                self.scopes.last_mut().unwrap(),
            )?;
            branches.push(
                self.expression(
                    parts
                        .get(1)
                        .ok_or_else(|| YinError::language("match body missing"))?,
                )?,
            );
            self.scopes.pop();
        }
        Ok(union(branches))
    }
    fn bind(&mut self, pattern: &Expr, kind: Type) -> Result<(), YinError> {
        match pattern {
            Expr::Atom(name, _) => self.define(name, kind),
            Expr::Form(Form::Vector, parts, _) => {
                for part in parts {
                    self.bind(part, Type::Any)?
                }
                Ok(())
            }
            _ => Err(YinError::language("invalid binding pattern")),
        }
    }
    fn define(&mut self, name: &str, kind: Type) -> Result<(), YinError> {
        let scope = self.scopes.last_mut().unwrap();
        if scope.contains_key(name) {
            return Err(YinError::language(format!("duplicate definition: {name}")));
        }
        scope.insert(name.into(), kind);
        Ok(())
    }
    fn lookup(&self, name: &str) -> Result<Type, YinError> {
        self.scopes
            .iter()
            .rev()
            .find_map(|scope| scope.get(name).cloned())
            .ok_or_else(|| YinError::language(format!("unbound name: {name}")))
    }
}

fn builtin(name: &str) -> Type {
    use Type::*;
    let f = |args, result| Function(args, Box::new(result));
    match name {
        "+" | "-" | "*" | "/" => f(vec![Any, Any], Any),
        "<" | "<=" | ">" | ">=" | "=" => f(vec![Any, Any], Bool),
        "and" | "or" => f(vec![Bool, Bool], Bool),
        "not" => f(vec![Bool], Bool),
        "length" => f(vec![Any], Int),
        "at" => f(vec![Any, Int], Any),
        "append" => f(vec![Any, Any], Any),
        "map" | "filter" => f(vec![Any, Any], Any),
        "fold" => f(vec![Any, Any, Any], Any),
        "range" => f(vec![Any, Any], Vector(Box::new(Int))),
        "slice" => f(vec![Any, Int, Int], Any),
        "reverse" => f(vec![Any], Any),
        "contains" => f(vec![Any, Any], Bool),
        "dict" | "set" | "print" => Any,
        "dict/get" => f(vec![Any, Any], Option(Box::new(Any))),
        "dict/put" => f(vec![Any, Any, Any], Any),
        "dict/remove" | "set/remove" => f(vec![Any, Any], Any),
        "dict/keys" | "dict/values" | "set/values" => f(vec![Any], Vector(Box::new(Any))),
        "dict/contains-key" | "set/contains" => f(vec![Any, Any], Bool),
        "dict/size" | "set/size" => f(vec![Any], Int),
        "set/add" => f(vec![Any, Any], Any),
        "set/union" | "set/intersection" | "set/difference" => f(vec![Any, Any], Any),
        "ok" => f(vec![Any], Result(Box::new(Any), Box::new(Never))),
        "err" => f(vec![Any], Result(Box::new(Never), Box::new(Any))),
        "some" => f(vec![Any], Option(Box::new(Any))),
        "string-length" => f(vec![String], Int),
        "concat" => f(vec![String, String], String),
        "substring" => f(vec![String, Int, Int], String),
        "split" => f(vec![String, String], Vector(Box::new(String))),
        "join" => f(vec![String, Any], String),
        "trim" => f(vec![String], String),
        "to-string" => f(vec![Any], String),
        "parse-int" => f(vec![String], Union(vec![Int, Bool])),
        "parse-float" => f(vec![String], Union(vec![Float, Bool])),
        "read-all" => f(vec![], String),
        "read-text" => f(vec![String], String),
        _ => Any,
    }
}
fn type_expression(expr: &Expr) -> Result<Type, YinError> {
    match expr {
        Expr::Atom(name, _) => Ok(match name.as_str() {
            "Int" => Type::Int,
            "Float" => Type::Float,
            "Bool" => Type::Bool,
            "String" => Type::String,
            "Any" => Type::Any,
            _ => Type::Named(name.clone()),
        }),
        Expr::Form(Form::Tuple, parts, _) => {
            let op = parts.first().and_then(Expr::atom).unwrap_or("");
            let args = parts[1..]
                .iter()
                .map(type_expression)
                .collect::<Result<Vec<_>, _>>()?;
            Ok(match op {
                "U" => Type::Union(args),
                "Vector" => Type::Vector(Box::new(args.first().cloned().unwrap_or(Type::Any))),
                "Set" => Type::Set(Box::new(args.first().cloned().unwrap_or(Type::Any))),
                "Option" => Type::Option(Box::new(args.first().cloned().unwrap_or(Type::Any))),
                "Dict" => Type::Dict(
                    Box::new(args.first().cloned().unwrap_or(Type::Any)),
                    Box::new(args.get(1).cloned().unwrap_or(Type::Any)),
                ),
                "Result" => Type::Result(
                    Box::new(args.first().cloned().unwrap_or(Type::Any)),
                    Box::new(args.get(1).cloned().unwrap_or(Type::Any)),
                ),
                _ => Type::Any,
            })
        }
        _ => Err(YinError::language("invalid type expression")),
    }
}
fn bind_match(
    pattern: &Expr,
    target: &Type,
    scope: &mut IndexMap<String, Type>,
) -> Result<(), YinError> {
    match pattern {
        Expr::Atom(name, _) if name != "_" && name != "true" && name != "false" => {
            scope.insert(name.clone(), target.clone());
            Ok(())
        }
        Expr::Form(_, parts, _) => {
            for part in parts.iter().skip(1) {
                bind_match(part, &Type::Any, scope)?
            }
            Ok(())
        }
        _ => Ok(()),
    }
}
fn require(actual: Type, expected: &Type, context: &str) -> Result<(), YinError> {
    if subtype(&actual, expected) {
        Ok(())
    } else {
        Err(YinError::language(format!(
            "{context}: expected {expected:?}, got {actual:?}"
        )))
    }
}
fn subtype(actual: &Type, expected: &Type) -> bool {
    actual == &Type::Any
        || expected == &Type::Any
        || actual == &Type::Never
        || actual == expected
        || matches!(actual,Type::Union(values) if values.iter().all(|v|subtype(v,expected)))
        || matches!(expected,Type::Union(values) if values.iter().any(|v|subtype(actual,v)))
        || matches!(
            (actual, expected),
            (Type::Result(_, _), Type::Result(_, _))
                | (Type::Option(_), Type::Option(_))
                | (Type::Vector(_), Type::Vector(_))
                | (Type::Named(_), Type::Named(_))
        )
}
fn union(values: Vec<Type>) -> Type {
    let mut result = Vec::new();
    for value in values {
        match value {
            Type::Never => {}
            Type::Union(items) => {
                for item in items {
                    if !result.contains(&item) {
                        result.push(item)
                    }
                }
            }
            other => {
                if !result.contains(&other) {
                    result.push(other)
                }
            }
        }
    }
    match result.as_slice() {
        [] => Type::Never,
        [one] => one.clone(),
        _ => Type::Union(result),
    }
}
fn integer(value: &str) -> bool {
    value.parse::<i128>().is_ok()
        || value
            .strip_prefix("0x")
            .is_some_and(|v| i128::from_str_radix(v, 16).is_ok())
        || value
            .strip_prefix("0b")
            .is_some_and(|v| i128::from_str_radix(v, 2).is_ok())
}
fn arity_expr(values: &[Expr], expected: usize, name: &str) -> Result<(), YinError> {
    if values.len() == expected {
        Ok(())
    } else {
        Err(YinError::language(format!(
            "{name} expects {} arguments",
            expected - 1
        )))
    }
}
