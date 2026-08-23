use crate::{Expr, Form, ParsedProgram, Type, YinError};
use indexmap::IndexMap;

pub fn check_program(program: &ParsedProgram) -> Result<Type, YinError> {
    let mut checker = Checker::new();
    checker.sequence(&program.expressions)
}

#[derive(Clone)]
pub struct CheckSession {
    checker: Checker,
}

impl CheckSession {
    pub fn new() -> Self {
        Self {
            checker: Checker::new(),
        }
    }

    pub fn check_source(&mut self, file: &str, source: &str) -> Result<Type, YinError> {
        let program = crate::parse(file, source)?;
        let mut trial = self.checker.clone();
        trial.scopes.push(IndexMap::new());
        let result = trial.sequence(&program.expressions)?;
        self.checker = trial;
        Ok(result)
    }
}

impl Default for CheckSession {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone)]
struct Checker {
    scopes: Vec<IndexMap<String, Type>>,
    records: IndexMap<String, Vec<(String, Type)>>,
    record_defaults: IndexMap<String, std::collections::HashSet<String>>,
    variants: IndexMap<String, Vec<String>>,
    record_variants: IndexMap<String, String>,
}

impl Checker {
    fn new() -> Self {
        let mut root = IndexMap::new();
        let mut records = IndexMap::new();
        let mut record_defaults = IndexMap::new();
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
        for (name, fields) in [
            (
                "DecodeError",
                vec![
                    ("code".into(), Type::String),
                    ("path".into(), Type::String),
                    ("message".into(), Type::String),
                ],
            ),
            (
                "EncodeError",
                vec![
                    ("code".into(), Type::String),
                    ("path".into(), Type::String),
                    ("message".into(), Type::String),
                ],
            ),
            (
                "ToolError",
                vec![
                    ("code".into(), Type::String),
                    ("tool".into(), Type::String),
                    ("message".into(), Type::String),
                ],
            ),
        ] {
            root.insert(
                name.into(),
                Type::Record {
                    name: name.into(),
                    parents: vec![],
                },
            );
            records.insert(name.into(), fields);
            record_defaults.insert(name.into(), std::collections::HashSet::new());
        }
        Self {
            scopes: vec![root],
            records,
            record_defaults,
            variants: IndexMap::new(),
            record_variants: IndexMap::new(),
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
                Ok(Type::ExactVector(types))
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
                let actual = self.expression(&values[2])?;
                self.assign(&values[1], actual.clone())?;
                Ok(actual)
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
                let input = self.resolve_type(type_expression(
                    values
                        .get(2)
                        .ok_or_else(|| YinError::language("tool input type is missing"))?,
                )?);
                let output = self.resolve_type(type_expression(
                    values
                        .get(3)
                        .ok_or_else(|| YinError::language("tool output type is missing"))?,
                )?);
                let error = self.resolve_type(type_expression(
                    values
                        .get(4)
                        .ok_or_else(|| YinError::language("tool error type is missing"))?,
                )?);
                let kind = Type::Tool(Box::new(input), Box::new(output), Box::new(error));
                self.define(name, kind.clone())?;
                Ok(kind)
            }
            Some("invoke") => {
                arity_expr(values, 3, "invoke")?;
                match self.expression(&values[1])? {
                    Type::Tool(input, output, error) => {
                        require(self.expression(&values[2])?, &input, "tool input")?;
                        Ok(Type::Result(
                            output,
                            Box::new(union(vec![
                                *error,
                                self.resolve_type(Type::Named("ToolError".into())),
                            ])),
                        ))
                    }
                    Type::Any => Ok(Type::Result(Box::new(Type::Any), Box::new(Type::Any))),
                    _ => Err(YinError::language("invoke expects a Tool")),
                }
            }
            Some("field") => self.field_access(values),
            Some("decode-json") => {
                arity_expr(values, 3, "decode-json")?;
                require(
                    self.expression(&values[2])?,
                    &Type::String,
                    "decode-json input",
                )?;
                Ok(Type::Result(
                    Box::new(self.resolve_type(type_expression(&values[1])?)),
                    Box::new(self.resolve_type(Type::Named("DecodeError".into()))),
                ))
            }
            Some("encode-json") => {
                arity_expr(values, 2, "encode-json")?;
                self.expression(&values[1])?;
                Ok(Type::Result(
                    Box::new(Type::String),
                    Box::new(self.resolve_type(Type::Named("EncodeError".into()))),
                ))
            }
            Some("json-schema") => Ok(Type::String),
            Some("dict") => self.dict_constructor(values),
            Some("set") => self.set_constructor(values),
            Some("dict/get") => self.dict_get(values),
            Some("dict/put")
            | Some("dict/remove")
            | Some("dict/keys")
            | Some("dict/values")
            | Some("dict/contains-key")
            | Some("dict/size")
            | Some("set/add")
            | Some("set/remove")
            | Some("set/contains")
            | Some("set/values")
            | Some("set/size")
            | Some("set/union")
            | Some("set/intersection")
            | Some("set/difference") => self.collection_operation(values),
            Some("ok") | Some("err") | Some("some") => self.tagged_constructor(values),
            Some("map") | Some("filter") | Some("fold") => self.collection_callback(values),
            Some("length") | Some("at") | Some("append") | Some("slice") | Some("reverse") => {
                self.vector_operation(values)
            }
            Some("+") | Some("-") | Some("*") | Some("/") => self.numeric_call(values),
            _ => self.call(values),
        }
    }

    fn dict_constructor(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        if (values.len() - 1) % 2 != 0 {
            return Err(YinError::language("dict expects key/value pairs"));
        }
        let mut keys = Vec::new();
        let mut entries = Vec::new();
        for pair in values[1..].chunks(2) {
            let key = self.expression(&pair[0])?;
            if !comparable_type(&key) {
                return Err(YinError::language(
                    "dict key requires a structurally comparable type",
                ));
            }
            keys.push(key);
            entries.push(self.expression(&pair[1])?);
        }
        Ok(Type::Dict(Box::new(union(keys)), Box::new(union(entries))))
    }

    fn set_constructor(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let mut elements = Vec::new();
        for value in &values[1..] {
            let kind = self.expression(value)?;
            if !comparable_type(&kind) {
                return Err(YinError::language(
                    "set requires a structurally comparable type",
                ));
            }
            elements.push(kind);
        }
        Ok(Type::Set(Box::new(union(elements))))
    }

    fn dict_get(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        arity_expr(values, 3, "dict/get")?;
        let dictionary = self.expression(&values[1])?;
        let key = self.expression(&values[2])?;
        let Type::Dict(expected, value) = dictionary else {
            return Err(YinError::language("dict/get expects a Dict"));
        };
        require(key, &expected, "dict/get key")?;
        Ok(Type::Option(value))
    }

    fn tagged_constructor(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        arity_expr(values, 2, values[0].atom().unwrap_or("constructor"))?;
        let payload = Box::new(self.expression(&values[1])?);
        Ok(match values[0].atom().unwrap_or("") {
            "ok" => Type::Ok(payload),
            "err" => Type::Err(payload),
            _ => Type::Some(payload),
        })
    }

    fn collection_operation(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let name = values[0].atom().unwrap_or("");
        let collection = self.expression(
            values
                .get(1)
                .ok_or_else(|| YinError::language(format!("{name} expects a collection")))?,
        )?;
        match (name, collection) {
            ("dict/put", Type::Dict(key, value)) => {
                arity_expr(values, 4, name)?;
                require(self.expression(&values[2])?, &key, "dict/put key")?;
                require(self.expression(&values[3])?, &value, "dict/put value")?;
                Ok(Type::Dict(key, value))
            }
            ("dict/remove", Type::Dict(key, value)) => {
                arity_expr(values, 3, name)?;
                require(self.expression(&values[2])?, &key, "dict/remove key")?;
                Ok(Type::Dict(key, value))
            }
            ("dict/keys", Type::Dict(key, _)) => {
                arity_expr(values, 2, name)?;
                Ok(Type::Vector(key))
            }
            ("dict/values", Type::Dict(_, value)) => {
                arity_expr(values, 2, name)?;
                Ok(Type::Vector(value))
            }
            ("dict/contains-key", Type::Dict(key, _)) => {
                arity_expr(values, 3, name)?;
                require(self.expression(&values[2])?, &key, "dict/contains-key key")?;
                Ok(Type::Bool)
            }
            ("dict/size", Type::Dict(_, _)) => {
                arity_expr(values, 2, name)?;
                Ok(Type::Int)
            }
            (operation, Type::Set(element)) => match operation {
                "set/add" | "set/remove" => {
                    arity_expr(values, 3, name)?;
                    require(self.expression(&values[2])?, &element, operation)?;
                    Ok(Type::Set(element))
                }
                "set/contains" => {
                    arity_expr(values, 3, name)?;
                    require(self.expression(&values[2])?, &element, operation)?;
                    Ok(Type::Bool)
                }
                "set/values" => {
                    arity_expr(values, 2, name)?;
                    Ok(Type::Vector(element))
                }
                "set/size" => {
                    arity_expr(values, 2, name)?;
                    Ok(Type::Int)
                }
                "set/union" | "set/intersection" | "set/difference" => {
                    arity_expr(values, 3, name)?;
                    require(
                        self.expression(&values[2])?,
                        &Type::Set(element.clone()),
                        operation,
                    )?;
                    Ok(Type::Set(element))
                }
                _ => Err(YinError::language(format!(
                    "{operation} expects the appropriate collection"
                ))),
            },
            _ => Err(YinError::language(format!(
                "{name} expects the appropriate collection"
            ))),
        }
    }

    fn collection_callback(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let name = values[0].atom().unwrap_or("");
        match name {
            "map" | "filter" => {
                arity_expr(values, 3, name)?;
                let collection = self.expression(&values[1])?;
                let callback = self.expression(&values[2])?;
                let elements = vector_elements(&collection)
                    .ok_or_else(|| YinError::language(format!("{name} expects a vector")))?;
                let Type::Function(parameters, required, result) = callback else {
                    return Err(YinError::language(format!("{name} expects a function")));
                };
                if parameters.len() != 1 || required > 1 {
                    return Err(YinError::language(format!(
                        "{name} callback expects one argument"
                    )));
                }
                for element in &elements {
                    require(element.clone(), &parameters[0], &format!("{name} callback"))?;
                }
                if name == "filter" {
                    require(*result, &Type::Bool, "filter callback return")?;
                    Ok(collection)
                } else {
                    Ok(match collection {
                        Type::ExactVector(items) => {
                            Type::ExactVector(vec![(*result).clone(); items.len()])
                        }
                        _ => Type::Vector(result),
                    })
                }
            }
            "fold" => {
                arity_expr(values, 4, name)?;
                let collection = self.expression(&values[1])?;
                let initial = self.expression(&values[2])?;
                let callback = self.expression(&values[3])?;
                let elements = vector_elements(&collection)
                    .ok_or_else(|| YinError::language("fold expects a vector"))?;
                let Type::Function(parameters, _, result) = callback else {
                    return Err(YinError::language("fold expects a function"));
                };
                if parameters.len() != 2 {
                    return Err(YinError::language("fold callback expects two arguments"));
                }
                require(initial.clone(), &parameters[0], "fold initial")?;
                for element in elements {
                    require(element, &parameters[1], "fold callback")?;
                }
                require((*result).clone(), &parameters[0], "fold callback return")?;
                Ok(initial)
            }
            _ => unreachable!(),
        }
    }

    fn numeric_call(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        arity_expr(values, 3, values[0].atom().unwrap_or("numeric primitive"))?;
        let left = self.expression(&values[1])?;
        let right = self.expression(&values[2])?;
        for kind in [&left, &right] {
            if !matches!(kind, Type::Int | Type::Float | Type::Any) {
                return Err(YinError::language(format!(
                    "numeric argument must be Int or Float, got {kind:?}"
                )));
            }
        }
        Ok(if left == Type::Float || right == Type::Float {
            Type::Float
        } else if left == Type::Any || right == Type::Any {
            Type::Any
        } else {
            Type::Int
        })
    }

    fn vector_operation(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let name = values[0].atom().unwrap_or("");
        match name {
            "length" => {
                arity_expr(values, 2, name)?;
                let target = self.expression(&values[1])?;
                if matches!(target, Type::Any | Type::ExactVector(_) | Type::Vector(_)) {
                    Ok(Type::Int)
                } else {
                    Err(YinError::language("length requires a vector"))
                }
            }
            "at" => {
                arity_expr(values, 3, name)?;
                let target = self.expression(&values[1])?;
                let index = self.expression(&values[2])?;
                if index != Type::Any && index != Type::Int {
                    return Err(YinError::language("at index must be Int"));
                }
                match target {
                    Type::Any => Ok(Type::Any),
                    Type::ExactVector(items) => {
                        if items.is_empty() {
                            return Err(YinError::language("at cannot index an empty vector"));
                        }
                        if let Some(literal) = values[2].atom().and_then(parse_index) {
                            items.get(literal).cloned().ok_or_else(|| {
                                YinError::language(format!(
                                    "vector index out of bounds: {literal} for length {}",
                                    items.len()
                                ))
                            })
                        } else {
                            Ok(union(items))
                        }
                    }
                    Type::Vector(element) => Ok(*element),
                    Type::Union(members) => Ok(union(
                        members
                            .into_iter()
                            .map(|member| match member {
                                Type::ExactVector(items) => {
                                    items.first().cloned().ok_or_else(|| {
                                        YinError::language("at cannot index an empty vector")
                                    })
                                }
                                Type::Vector(element) => Ok(*element),
                                _ => Err(YinError::language("at requires a vector")),
                            })
                            .collect::<Result<Vec<_>, _>>()?,
                    )),
                    _ => Err(YinError::language("at requires a vector")),
                }
            }
            "append" => {
                arity_expr(values, 3, name)?;
                append_types(self.expression(&values[1])?, self.expression(&values[2])?)
            }
            "reverse" => {
                arity_expr(values, 2, name)?;
                match self.expression(&values[1])? {
                    Type::ExactVector(mut items) => {
                        items.reverse();
                        Ok(Type::ExactVector(items))
                    }
                    Type::Vector(element) => Ok(Type::Vector(element)),
                    Type::Any => Ok(Type::Any),
                    _ => Err(YinError::language("reverse requires a vector")),
                }
            }
            "slice" => {
                arity_expr(values, 4, name)?;
                let target = self.expression(&values[1])?;
                require(self.expression(&values[2])?, &Type::Int, "slice start")?;
                require(self.expression(&values[3])?, &Type::Int, "slice end")?;
                match target {
                    Type::ExactVector(items) => {
                        let start = values[2].atom().and_then(parse_index);
                        let end = values[3].atom().and_then(parse_index);
                        if let (Some(start), Some(end)) = (start, end) {
                            if start > end || end > items.len() {
                                Err(YinError::language("invalid slice bounds"))
                            } else {
                                Ok(Type::ExactVector(items[start..end].to_vec()))
                            }
                        } else {
                            Ok(Type::Vector(Box::new(union(items))))
                        }
                    }
                    Type::Vector(element) => Ok(Type::Vector(element)),
                    Type::Any => Ok(Type::Any),
                    _ => Err(YinError::language("slice requires a vector")),
                }
            }
            _ => unreachable!(),
        }
    }

    fn field_access(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        arity_expr(values, 3, "field")?;
        let field = values[2]
            .atom()
            .and_then(|name| name.strip_prefix(':'))
            .ok_or_else(|| YinError::language("field name must be a keyword"))?;
        let target = self.expression(&values[1])?;
        self.field_type(target, field)
    }

    fn field_type(&self, target: Type, field: &str) -> Result<Type, YinError> {
        match target {
            Type::Any => Ok(Type::Any),
            Type::Record { name, .. } => self.records[&name]
                .iter()
                .find(|(candidate, _)| candidate == field)
                .map(|(_, kind)| kind.clone())
                .ok_or_else(|| YinError::language(format!("record type has no field: {field}"))),
            Type::Union(members) => Ok(union(
                members
                    .into_iter()
                    .map(|member| self.field_type(member, field))
                    .collect::<Result<Vec<_>, _>>()?,
            )),
            _ => Err(YinError::language("field access requires a record type")),
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
        let mut required = 0;
        let mut result = None;
        let mut saw_return = false;
        self.scopes.push(IndexMap::new());
        for parameter in parameters {
            match parameter {
                Expr::Atom(name, _) => {
                    if saw_return {
                        return Err(YinError::language("return descriptor must be last"));
                    }
                    inputs.push(Type::Any);
                    required += 1;
                    self.define(name, Type::Any)?
                }
                Expr::Form(Form::Vector, parts, _)
                    if parts.first().and_then(Expr::atom) == Some("->") =>
                {
                    result = Some(
                        self.resolve_type(type_expression(
                            parts
                                .get(1)
                                .ok_or_else(|| YinError::language("return type is missing"))?,
                        )?),
                    );
                    saw_return = true;
                }
                Expr::Form(Form::Vector, parts, _) => {
                    if saw_return {
                        return Err(YinError::language("return descriptor must be last"));
                    }
                    validate_descriptor(parts)?;
                    let name = parts
                        .first()
                        .and_then(Expr::atom)
                        .ok_or_else(|| YinError::language("parameter name is missing"))?;
                    let kind = self.resolve_type(type_expression(
                        parts
                            .get(1)
                            .ok_or_else(|| YinError::language("parameter type is missing"))?,
                    )?);
                    inputs.push(kind.clone());
                    if !parts.iter().any(|part| part.atom() == Some(":default")) {
                        required += 1;
                    }
                    self.define(name, kind)?
                }
                _ => return Err(YinError::language("invalid parameter")),
            }
        }
        let actual = self.sequence(&values[2..])?;
        self.scopes.pop();
        let result = if let Some(result) = result {
            require(actual, &result, "function return")?;
            result
        } else {
            actual
        };
        Ok(Type::Function(inputs, required, Box::new(result)))
    }
    fn call(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let callable = self.expression(&values[0])?;
        let mut arguments = Vec::new();
        let mut keyword_names = std::collections::HashSet::new();
        let mut keyword_order = Vec::new();
        let mut saw_keyword = false;
        let mut saw_positional = false;
        let mut index = 1;
        while index < values.len() {
            if let Some(keyword) = values[index].atom().and_then(|v| v.strip_prefix(':')) {
                saw_keyword = true;
                if !keyword_names.insert(keyword.to_owned()) {
                    return Err(YinError::language(format!(
                        "duplicate keyword argument: {keyword}"
                    )));
                }
                keyword_order.push(keyword.to_owned());
                index += 1;
                if index >= values.len() {
                    return Err(YinError::language("keyword argument has no value"));
                }
            } else {
                saw_positional = true;
            }
            arguments.push(self.expression(&values[index])?);
            index += 1
        }
        if saw_keyword && saw_positional {
            return Err(YinError::language(
                "cannot mix positional and keyword arguments",
            ));
        }
        match callable {
            Type::Function(parameters, required, result) => {
                if arguments.len() < required || arguments.len() > parameters.len() {
                    return Err(YinError::language(format!(
                        "expected {required}..={} arguments, got {}",
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
            Type::Record { name, parents } if self.records.contains_key(&name) => {
                let fields = self.records[&name].clone();
                if saw_keyword {
                    for (keyword, actual) in keyword_order.iter().zip(arguments) {
                        let expected = fields
                            .iter()
                            .find(|(field, _)| field == keyword)
                            .map(|(_, kind)| kind)
                            .ok_or_else(|| {
                                YinError::language(format!("unknown record field: {keyword}"))
                            })?;
                        require(actual, expected, &format!("record field {keyword}"))?;
                    }
                    let supplied = keyword_order
                        .into_iter()
                        .collect::<std::collections::HashSet<_>>();
                    for (field, _) in &fields {
                        if !supplied.contains(field) && !self.record_defaults[&name].contains(field)
                        {
                            return Err(YinError::language(format!(
                                "missing record field: {field}"
                            )));
                        }
                    }
                } else {
                    if arguments.is_empty() {
                        for (field, _) in &fields {
                            if !self.record_defaults[&name].contains(field) {
                                return Err(YinError::language(format!(
                                    "missing record field: {field}"
                                )));
                            }
                        }
                    } else if arguments.len() != fields.len() {
                        return Err(YinError::language(format!(
                            "record {name} expects {} fields, got {}",
                            fields.len(),
                            arguments.len()
                        )));
                    }
                    for (actual, (_, expected)) in arguments.into_iter().zip(fields) {
                        require(actual, &expected, "record field")?;
                    }
                }
                Ok(Type::Record { name, parents })
            }
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
        let mut defaults = std::collections::HashSet::new();
        for value in &values[2..] {
            if let Expr::Form(Form::Vector, parts, _) = value {
                validate_descriptor(parts)?;
                let field = parts
                    .first()
                    .and_then(Expr::atom)
                    .ok_or_else(|| YinError::language("invalid record field"))?;
                if fields.iter().any(|(name, _)| name == field) {
                    return Err(YinError::language(format!(
                        "duplicate record field: {field}"
                    )));
                }
                fields.push((
                    field.into(),
                    self.resolve_type(type_expression(
                        parts
                            .get(1)
                            .ok_or_else(|| YinError::language("record field type is missing"))?,
                    )?),
                ));
                if parts.iter().any(|part| part.atom() == Some(":default")) {
                    defaults.insert(field.into());
                }
            }
        }
        if let Some(Expr::Form(Form::Tuple, parents, _)) = values.get(2) {
            for parent in parents {
                let parent = parent
                    .atom()
                    .ok_or_else(|| YinError::language("invalid parent record"))?;
                for inherited in
                    self.records.get(parent).cloned().ok_or_else(|| {
                        YinError::language(format!("unknown parent record: {parent}"))
                    })?
                {
                    if fields.iter().any(|(name, _)| name == &inherited.0) {
                        return Err(YinError::language(format!(
                            "conflicting field {} inherited from parent {parent}",
                            inherited.0
                        )));
                    }
                    fields.push(inherited);
                }
                if let Some(parent_defaults) = self.record_defaults.get(parent) {
                    defaults.extend(parent_defaults.iter().cloned());
                }
            }
        }
        self.records.insert(name.clone(), fields);
        self.record_defaults.insert(name.clone(), defaults);
        if let Some(variant) = &variant {
            self.record_variants.insert(name.clone(), variant.clone());
        }
        let mut parents: Vec<String> = values
            .get(2)
            .and_then(|value| match value {
                Expr::Form(Form::Tuple, parents, _) => Some(
                    parents
                        .iter()
                        .filter_map(Expr::atom)
                        .map(str::to_owned)
                        .collect(),
                ),
                _ => None,
            })
            .unwrap_or_default();
        if let Some(variant) = &variant {
            parents.push(variant.clone());
        }
        let kind = Type::Record {
            name: name.clone(),
            parents,
        };
        self.define(&name, kind.clone())?;
        Ok(kind)
    }
    fn variant(&mut self, values: &[Expr]) -> Result<Type, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| YinError::language("variant expects a name"))?
            .to_owned();
        let mut cases = Vec::new();
        for case in &values[2..] {
            if let Expr::Form(Form::Vector, parts, span) = case {
                let case_name = parts
                    .first()
                    .and_then(Expr::atom)
                    .ok_or_else(|| YinError::language("variant case requires a name"))?;
                if cases.iter().any(|existing| existing == case_name) {
                    return Err(YinError::language(format!(
                        "duplicated variant case: {case_name}"
                    )));
                }
                cases.push(case_name.to_owned());
                let mut record = vec![Expr::Atom("record".into(), span.clone()), parts[0].clone()];
                record.extend_from_slice(&parts[1..]);
                self.record(&record, Some(name.clone()))?;
            }
        }
        self.variants.insert(name.clone(), cases);
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
        let mut covered = std::collections::HashSet::new();
        let mut wildcard = false;
        for clause in &values[2..] {
            let Expr::Form(Form::Vector, parts, _) = clause else {
                return Err(YinError::language("match clause must be a vector"));
            };
            self.scopes.push(IndexMap::new());
            let pattern = parts
                .first()
                .ok_or_else(|| YinError::language("match pattern missing"))?;
            let case = bind_match(
                pattern,
                &target,
                self.scopes.last_mut().unwrap(),
                &self.records,
            )?;
            if pattern.atom() == Some("_") {
                wildcard = true;
            }
            if let Some(case) = case {
                covered.insert(case);
            }
            branches.push(
                self.expression(
                    parts
                        .get(1)
                        .ok_or_else(|| YinError::language("match body missing"))?,
                )?,
            );
            self.scopes.pop();
        }
        if !wildcard {
            for required in required_cases(&target, &self.variants, &self.record_variants) {
                if !covered.contains(&required) {
                    return Err(YinError::language(format!(
                        "non-exhaustive match for type: {required}"
                    )));
                }
            }
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
    fn assign(&mut self, pattern: &Expr, kind: Type) -> Result<(), YinError> {
        match pattern {
            Expr::Atom(name, _) => {
                let expected = self.lookup(name)?;
                require(kind, &expected, "assignment")
            }
            Expr::Form(Form::Vector, parts, _) => match kind {
                Type::ExactVector(elements) => {
                    if elements.len() != parts.len() {
                        return Err(YinError::language("vector assignment length mismatch"));
                    }
                    for (part, element) in parts.iter().zip(elements) {
                        self.assign(part, element)?;
                    }
                    Ok(())
                }
                Type::Vector(element) => {
                    for part in parts {
                        self.assign(part, (*element).clone())?;
                    }
                    Ok(())
                }
                _ => Err(YinError::language("vector assignment expects a vector")),
            },
            _ => Err(YinError::language("invalid assignment pattern")),
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

    fn resolve_type(&self, kind: Type) -> Type {
        match kind {
            Type::Named(name) if self.records.contains_key(&name) => self
                .scopes
                .iter()
                .rev()
                .find_map(|scope| scope.get(&name).cloned())
                .unwrap_or(Type::Named(name)),
            Type::Vector(value) => Type::Vector(Box::new(self.resolve_type(*value))),
            Type::Set(value) => Type::Set(Box::new(self.resolve_type(*value))),
            Type::Option(value) => Type::Option(Box::new(self.resolve_type(*value))),
            Type::Result(ok, error) => Type::Result(
                Box::new(self.resolve_type(*ok)),
                Box::new(self.resolve_type(*error)),
            ),
            Type::Union(values) => Type::Union(
                values
                    .into_iter()
                    .map(|value| self.resolve_type(value))
                    .collect(),
            ),
            Type::Function(parameters, required, result) => Type::Function(
                parameters
                    .into_iter()
                    .map(|value| self.resolve_type(value))
                    .collect(),
                required,
                Box::new(self.resolve_type(*result)),
            ),
            Type::Tool(input, output, error) => Type::Tool(
                Box::new(self.resolve_type(*input)),
                Box::new(self.resolve_type(*output)),
                Box::new(self.resolve_type(*error)),
            ),
            other => other,
        }
    }
}

fn builtin(name: &str) -> Type {
    use Type::*;
    let f = |args: Vec<Type>, result| {
        let required = args.len();
        Function(args, required, Box::new(result))
    };
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

fn validate_descriptor(parts: &[Expr]) -> Result<(), YinError> {
    let mut properties = std::collections::HashSet::new();
    let mut index = 2;
    while index < parts.len() {
        let property = parts[index]
            .atom()
            .and_then(|value| value.strip_prefix(':'))
            .ok_or_else(|| YinError::language("descriptor property must be a keyword"))?;
        if property != "default" {
            return Err(YinError::language(format!(
                "unsupported descriptor property: :{property}"
            )));
        }
        if !properties.insert(property) {
            return Err(YinError::language(format!(
                "duplicate descriptor property: {property}"
            )));
        }
        if parts.get(index + 1).is_none() {
            return Err(YinError::language(format!(
                "descriptor property has no value: {property}"
            )));
        }
        index += 2;
    }
    Ok(())
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
            if op == "Fn" {
                let parameters = match parts.get(1) {
                    Some(Expr::Form(Form::Vector, values, _)) => values
                        .iter()
                        .map(type_expression)
                        .collect::<Result<Vec<_>, _>>()?,
                    _ => return Err(YinError::language("Fn requires a parameter vector")),
                };
                let result = type_expression(
                    parts
                        .get(2)
                        .ok_or_else(|| YinError::language("Fn requires a result type"))?,
                )?;
                return Ok(Type::Function(
                    parameters.clone(),
                    parameters.len(),
                    Box::new(result),
                ));
            }
            let args = parts[1..]
                .iter()
                .map(type_expression)
                .collect::<Result<Vec<_>, _>>()?;
            Ok(match op {
                "U" if args.is_empty() => {
                    return Err(YinError::language(
                        "union type requires at least one member",
                    ));
                }
                "U" => Type::Union(args),
                "Vector" if args.len() == 1 => Type::Vector(Box::new(args[0].clone())),
                "Set" if args.len() == 1 => Type::Set(Box::new(args[0].clone())),
                "Option" if args.len() == 1 => Type::Option(Box::new(args[0].clone())),
                "Dict" if args.len() == 2 => Type::Dict(
                    Box::new(args.first().cloned().unwrap_or(Type::Any)),
                    Box::new(args.get(1).cloned().unwrap_or(Type::Any)),
                ),
                "Result" if args.len() == 2 => Type::Result(
                    Box::new(args.first().cloned().unwrap_or(Type::Any)),
                    Box::new(args.get(1).cloned().unwrap_or(Type::Any)),
                ),
                _ => {
                    return Err(YinError::language(format!(
                        "unsupported type expression: {op}"
                    )));
                }
            })
        }
        _ => Err(YinError::language("invalid type expression")),
    }
}
fn bind_match(
    pattern: &Expr,
    target: &Type,
    scope: &mut IndexMap<String, Type>,
    records: &IndexMap<String, Vec<(String, Type)>>,
) -> Result<Option<String>, YinError> {
    match pattern {
        Expr::Atom(name, _) if name != "_" && name != "true" && name != "false" => {
            bind_name(scope, name, target.clone())?;
            Ok(None)
        }
        Expr::Form(Form::Vector, parts, _) => {
            let element_types = vector_elements(target)
                .ok_or_else(|| YinError::language("vector pattern expects a vector"))?;
            if matches!(target, Type::ExactVector(_)) && parts.len() != element_types.len() {
                return Err(YinError::language("vector pattern length mismatch"));
            }
            for (index, part) in parts.iter().enumerate() {
                let kind = element_types
                    .get(index)
                    .cloned()
                    .unwrap_or_else(|| union(element_types.clone()));
                bind_match(part, &kind, scope, records)?;
            }
            Ok(Some("Vector".into()))
        }
        Expr::Form(Form::Tuple, parts, _) => {
            let case = parts
                .first()
                .and_then(Expr::atom)
                .ok_or_else(|| YinError::language("invalid match pattern"))?;
            let payloads = match case {
                "Ok" => {
                    expect_pattern_arity(parts, 2, "Ok")?;
                    vec![match target {
                        Type::Result(ok, _) | Type::Ok(ok) => (**ok).clone(),
                        _ => Type::Any,
                    }]
                }
                "Err" => {
                    expect_pattern_arity(parts, 2, "Err")?;
                    vec![match target {
                        Type::Result(_, error) | Type::Err(error) => (**error).clone(),
                        _ => Type::Any,
                    }]
                }
                "Some" => {
                    expect_pattern_arity(parts, 2, "Some")?;
                    vec![match target {
                        Type::Option(value) | Type::Some(value) => (**value).clone(),
                        _ => Type::Any,
                    }]
                }
                "None" => {
                    expect_pattern_arity(parts, 1, "None")?;
                    vec![]
                }
                "Int" | "Float" | "Bool" | "String" => {
                    expect_pattern_arity(parts, 2, case)?;
                    vec![match case {
                        "Int" => Type::Int,
                        "Float" => Type::Float,
                        "Bool" => Type::Bool,
                        _ => Type::String,
                    }]
                }
                record if records.contains_key(record) => records[record]
                    .iter()
                    .map(|(_, kind)| kind.clone())
                    .collect(),
                _ => return Err(YinError::language(format!("unknown pattern: {case}"))),
            };
            if parts.len() - 1 != payloads.len() {
                return Err(YinError::language(format!(
                    "{case} pattern expects exactly {} payloads",
                    payloads.len()
                )));
            }
            for (part, kind) in parts[1..].iter().zip(payloads) {
                bind_match(part, &kind, scope, records)?;
            }
            Ok(Some(case.into()))
        }
        _ => Ok(None),
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
        || matches!((actual, expected), (Type::ExactVector(values), Type::Vector(element)) if values.iter().all(|value| subtype(value, element)))
        || matches!((actual, expected), (Type::Ok(value), Type::Result(ok, _)) if subtype(value, ok))
        || matches!((actual, expected), (Type::Err(value), Type::Result(_, error)) if subtype(value, error))
        || matches!((actual, expected), (Type::Some(value), Type::Option(element)) if subtype(value, element))
        || matches!((actual, expected), (Type::None, Type::Option(_)))
        || matches!(
            (actual, expected),
            (Type::Result(a,b), Type::Result(c,d)) if subtype(a,c) && subtype(b,d)
        )
        || matches!(
            (actual, expected),
            (Type::Option(a), Type::Option(b)) if subtype(a,b)
        )
        || matches!(
            (actual, expected),
            (Type::Vector(a), Type::Vector(b)) if subtype(a,b)
        )
        || matches!(
            (actual, expected),
            (Type::Function(a,ar,az), Type::Function(b,br,bz)) if ar == br && a.len() == b.len() && a.iter().zip(b).all(|(x,y)| subtype(y,x)) && subtype(az,bz)
        )
        || matches!(
            (actual, expected),
            (Type::ExactVector(a), Type::ExactVector(b)) if a.len() == b.len() && a.iter().zip(b).all(|(x,y)| subtype(x,y))
        )
        || matches!(
            (actual, expected),
            (Type::Dict(ak,av), Type::Dict(bk,bv)) if subtype(ak,bk) && subtype(av,bv)
        )
        || matches!(
            (actual, expected),
            (Type::Set(a), Type::Set(b)) if subtype(a,b)
        )
        || matches!((actual, expected), (Type::Named(a), Type::Named(b)) if a == b)
        || matches!((actual, expected), (Type::Record { name, parents }, Type::Named(expected)) if name == expected || parents.contains(expected))
        || matches!((actual, expected), (Type::Record { name: a, parents }, Type::Record { name: b, .. }) if a == b || parents.contains(b))
}

fn vector_elements(kind: &Type) -> Option<Vec<Type>> {
    match kind {
        Type::ExactVector(values) => Some(values.clone()),
        Type::Vector(element) => Some(vec![(**element).clone()]),
        _ => None,
    }
}

fn parse_index(value: &str) -> Option<usize> {
    value.parse::<usize>().ok()
}

fn append_types(left: Type, right: Type) -> Result<Type, YinError> {
    match (left, right) {
        (Type::Any, _) | (_, Type::Any) => Ok(Type::Any),
        (Type::ExactVector(mut left), Type::ExactVector(right)) => {
            left.extend(right);
            Ok(Type::ExactVector(left))
        }
        (Type::Vector(left), Type::Vector(right)) => {
            Ok(Type::Vector(Box::new(union(vec![*left, *right]))))
        }
        (Type::ExactVector(left), Type::Vector(right)) => Ok(Type::Vector(Box::new(union(
            left.into_iter().chain([*right]).collect(),
        )))),
        (Type::Vector(left), Type::ExactVector(right)) => Ok(Type::Vector(Box::new(union(
            [*left].into_iter().chain(right).collect(),
        )))),
        (Type::Union(left), right) => Ok(union(
            left.into_iter()
                .map(|member| append_types(member, right.clone()))
                .collect::<Result<Vec<_>, _>>()?,
        )),
        (left, Type::Union(right)) => Ok(union(
            right
                .into_iter()
                .map(|member| append_types(left.clone(), member))
                .collect::<Result<Vec<_>, _>>()?,
        )),
        _ => Err(YinError::language("append requires vectors")),
    }
}

fn comparable_type(kind: &Type) -> bool {
    match kind {
        Type::Function(_, _, _) | Type::Any => false,
        Type::Tool(_, _, _) => false,
        Type::ExactVector(values) | Type::Union(values) => values.iter().all(comparable_type),
        Type::Vector(value)
        | Type::Set(value)
        | Type::Option(value)
        | Type::Some(value)
        | Type::Ok(value)
        | Type::Err(value) => comparable_type(value),
        Type::Dict(key, value) | Type::Result(key, value) => {
            comparable_type(key) && comparable_type(value)
        }
        _ => true,
    }
}

fn bind_name(scope: &mut IndexMap<String, Type>, name: &str, kind: Type) -> Result<(), YinError> {
    if name == "_" {
        return Ok(());
    }
    if scope.contains_key(name) {
        return Err(YinError::language(format!(
            "duplicate binding in pattern: {name}"
        )));
    }
    scope.insert(name.into(), kind);
    Ok(())
}

fn expect_pattern_arity(parts: &[Expr], expected: usize, name: &str) -> Result<(), YinError> {
    if parts.len() == expected {
        Ok(())
    } else {
        Err(YinError::language(format!(
            "{name} pattern expects exactly {} payloads",
            expected - 1
        )))
    }
}

fn required_cases(
    target: &Type,
    variants: &IndexMap<String, Vec<String>>,
    record_variants: &IndexMap<String, String>,
) -> Vec<String> {
    match target {
        Type::Result(_, _) => vec!["Ok".into(), "Err".into()],
        Type::Option(_) => vec!["Some".into(), "None".into()],
        Type::Union(values) => values
            .iter()
            .flat_map(|value| required_cases(value, variants, record_variants))
            .collect(),
        Type::Named(name) if variants.contains_key(name) => variants[name].clone(),
        Type::Named(name) if record_variants.contains_key(name) => vec![name.clone()],
        Type::Record { name, .. } => vec![name.clone()],
        Type::Int => vec!["Int".into()],
        Type::Float => vec!["Float".into()],
        Type::Bool => vec!["Bool".into()],
        Type::String => vec!["String".into()],
        Type::Any => vec!["Any".into()],
        Type::ExactVector(_) | Type::Vector(_) => vec!["Vector".into()],
        _ => vec![],
    }
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
