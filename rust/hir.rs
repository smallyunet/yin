use crate::check::{SpanKey, builtin, check_program_report};
use crate::{ErrorCode, Expr, Form, ParsedProgram, SourceSpan, Type, YinError};
use indexmap::IndexMap;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct SymbolId(pub u32);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HirSymbolKind {
    Builtin,
    Binding,
    Parameter,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HirSymbol {
    pub id: SymbolId,
    pub name: String,
    pub kind: HirSymbolKind,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirProgram {
    pub symbols: Vec<HirSymbol>,
    pub expressions: Vec<HirExpr>,
    pub result_type: Type,
}

#[derive(Clone, Debug, PartialEq)]
pub struct CheckedProgram {
    pub hir: HirProgram,
}

impl CheckedProgram {
    pub fn result_type(&self) -> &Type {
        &self.hir.result_type
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirExpr {
    pub kind: HirKind,
    pub ty: Type,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, PartialEq)]
pub enum HirKind {
    Literal(HirLiteral),
    Reference(SymbolId),
    Vector(Vec<HirExpr>),
    Define {
        symbol: SymbolId,
        value: Box<HirExpr>,
    },
    Function {
        parameters: Vec<HirParameter>,
        body: Vec<HirExpr>,
    },
    Record {
        symbol: SymbolId,
        parents: Vec<SymbolId>,
        fields: Vec<HirRecordField>,
    },
    Call {
        callee: Box<HirExpr>,
        arguments: Vec<HirArgument>,
    },
    If {
        condition: Box<HirExpr>,
        then_branch: Box<HirExpr>,
        else_branch: Box<HirExpr>,
    },
    Sequence(Vec<HirExpr>),
    FieldPath {
        root: SymbolId,
        fields: Vec<String>,
    },
    Field {
        target: Box<HirExpr>,
        name: String,
    },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HirLiteral {
    Int(String),
    Float(String),
    Bool(bool),
    String(String),
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirParameter {
    pub symbol: SymbolId,
    pub ty: Type,
    pub required: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirRecordField {
    pub name: String,
    pub ty: Type,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirArgument {
    pub keyword: Option<String>,
    pub value: HirExpr,
}

pub fn check_hir_program(program: &ParsedProgram) -> Result<CheckedProgram, YinError> {
    let report = check_program_report(program)?;
    let mut lowerer = Lowerer {
        types: &report.expression_types,
        record_fields: &report.record_fields,
        scopes: vec![IndexMap::new()],
        builtins: IndexMap::new(),
        symbols: Vec::new(),
    };
    lowerer.predeclare(&program.expressions)?;
    let expressions = lowerer.sequence(&program.expressions)?;
    Ok(CheckedProgram {
        hir: HirProgram {
            symbols: lowerer.symbols,
            expressions,
            result_type: report.result_type,
        },
    })
}

struct Lowerer<'a> {
    types: &'a std::collections::HashMap<SpanKey, Type>,
    record_fields: &'a IndexMap<String, Vec<(String, Type)>>,
    scopes: Vec<IndexMap<String, SymbolId>>,
    builtins: IndexMap<String, SymbolId>,
    symbols: Vec<HirSymbol>,
}

impl Lowerer<'_> {
    fn sequence(&mut self, expressions: &[Expr]) -> Result<Vec<HirExpr>, YinError> {
        expressions
            .iter()
            .map(|expression| self.expression(expression))
            .collect()
    }

    fn expression(&mut self, expression: &Expr) -> Result<HirExpr, YinError> {
        let ty = self.type_of(expression)?;
        let kind = match expression {
            Expr::String(value, _) => HirKind::Literal(HirLiteral::String(value.clone())),
            Expr::Atom(value, _) if integer(value) => {
                HirKind::Literal(HirLiteral::Int(value.clone()))
            }
            Expr::Atom(value, _) if float(value) => {
                HirKind::Literal(HirLiteral::Float(value.clone()))
            }
            Expr::Atom(value, _) if value == "true" || value == "false" => {
                HirKind::Literal(HirLiteral::Bool(value == "true"))
            }
            Expr::Atom(value, _) if dotted(value) => {
                let mut parts = value.split('.');
                let root = self.resolve(parts.next().unwrap_or(value));
                HirKind::FieldPath {
                    root,
                    fields: parts.map(str::to_owned).collect(),
                }
            }
            Expr::Atom(value, _) => HirKind::Reference(self.resolve(value)),
            Expr::Form(Form::Vector, values, _) => HirKind::Vector(self.sequence(values)?),
            Expr::Form(Form::Tuple, values, _) if values.is_empty() => {
                HirKind::Sequence(Vec::new())
            }
            Expr::Form(Form::Tuple, values, _) => self.tuple(expression, values, &ty)?,
        };
        Ok(HirExpr {
            kind,
            ty,
            span: expression.span().clone(),
        })
    }

    fn tuple(
        &mut self,
        expression: &Expr,
        values: &[Expr],
        ty: &Type,
    ) -> Result<HirKind, YinError> {
        match values[0].atom() {
            Some("seq") => {
                self.predeclare(&values[1..])?;
                Ok(HirKind::Sequence(self.sequence(&values[1..])?))
            }
            Some("if") => Ok(HirKind::If {
                condition: Box::new(self.expression(&values[1])?),
                then_branch: Box::new(self.expression(&values[2])?),
                else_branch: Box::new(self.expression(&values[3])?),
            }),
            Some("define") => {
                let name = values[1].atom().ok_or_else(|| {
                    self.unsupported(&values[1], "destructuring define is outside HIR phase 1")
                })?;
                let symbol = self.lookup(name).ok_or_else(|| {
                    self.error(expression, format!("unresolved definition: {name}"))
                })?;
                Ok(HirKind::Define {
                    symbol,
                    value: Box::new(self.expression(&values[2])?),
                })
            }
            Some("fun") => self.function(expression, values, ty),
            Some("record") => self.record(expression, values),
            Some("field") => {
                let name = values[2]
                    .atom()
                    .and_then(|value| value.strip_prefix(':'))
                    .ok_or_else(|| self.error(&values[2], "field name must be a keyword"))?;
                Ok(HirKind::Field {
                    target: Box::new(self.expression(&values[1])?),
                    name: name.to_owned(),
                })
            }
            Some(
                "set!" | "match" | "policy" | "variant" | "module" | "import" | "tool" | "invoke"
                | "decode-json" | "encode-json" | "json-schema",
            ) => Err(self.unsupported(
                expression,
                format!(
                    "{} is outside HIR phase 1",
                    values[0].atom().unwrap_or("form")
                ),
            )),
            _ => self.call(values),
        }
    }

    fn function(
        &mut self,
        expression: &Expr,
        values: &[Expr],
        ty: &Type,
    ) -> Result<HirKind, YinError> {
        let Type::Function(inputs, required, _) = ty else {
            return Err(self.error(expression, "checked function has no function type"));
        };
        let Expr::Form(Form::Tuple, descriptors, _) = &values[1] else {
            return Err(self.error(&values[1], "fun expects parameters"));
        };
        self.push_scope();
        let lowered = (|| {
            let mut parameters = Vec::new();
            let mut input_index = 0;
            for descriptor in descriptors {
                let (name, has_default) = match descriptor {
                    Expr::Atom(name, _) => (name.as_str(), false),
                    Expr::Form(Form::Vector, parts, _)
                        if parts.first().and_then(Expr::atom) == Some("->") =>
                    {
                        continue;
                    }
                    Expr::Form(Form::Vector, parts, _) => {
                        let name = parts
                            .first()
                            .and_then(Expr::atom)
                            .ok_or_else(|| self.error(descriptor, "parameter name is missing"))?;
                        (
                            name,
                            parts.iter().any(|part| part.atom() == Some(":default")),
                        )
                    }
                    _ => return Err(self.error(descriptor, "invalid parameter")),
                };
                if has_default {
                    return Err(
                        self.unsupported(descriptor, "default parameters are outside HIR phase 1")
                    );
                }
                let parameter_type = inputs.get(input_index).cloned().ok_or_else(|| {
                    self.error(descriptor, "function parameter type is unavailable")
                })?;
                let symbol = self.define(name, HirSymbolKind::Parameter);
                parameters.push(HirParameter {
                    symbol,
                    ty: parameter_type,
                    required: input_index < *required,
                });
                input_index += 1;
            }
            self.predeclare(&values[2..])?;
            let body = self.sequence(&values[2..])?;
            Ok(HirKind::Function { parameters, body })
        })();
        self.pop_scope();
        lowered
    }

    fn record(&mut self, expression: &Expr, values: &[Expr]) -> Result<HirKind, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| self.error(expression, "record expects a name"))?;
        let mut descriptor_start = 2;
        let parents = if let Some(Expr::Form(Form::Tuple, parent_expressions, _)) = values.get(2) {
            descriptor_start = 3;
            parent_expressions
                .iter()
                .map(|parent| {
                    let name = parent
                        .atom()
                        .ok_or_else(|| self.error(parent, "invalid parent record"))?;
                    self.lookup(name)
                        .ok_or_else(|| self.error(parent, format!("unresolved parent: {name}")))
                })
                .collect::<Result<Vec<_>, _>>()?
        } else {
            Vec::new()
        };
        let checked_fields = self
            .record_fields
            .get(name)
            .ok_or_else(|| self.error(expression, format!("record fields unavailable: {name}")))?;
        let mut fields = Vec::new();
        for descriptor in &values[descriptor_start..] {
            let Expr::Form(Form::Vector, parts, _) = descriptor else {
                continue;
            };
            if parts.iter().any(|part| part.atom() == Some(":default")) {
                return Err(self.unsupported(descriptor, "record defaults are outside HIR phase 1"));
            }
            let field_name = parts
                .first()
                .and_then(Expr::atom)
                .ok_or_else(|| self.error(descriptor, "record field name is missing"))?;
            let field_type = checked_fields
                .iter()
                .find(|(candidate, _)| candidate == field_name)
                .map(|(_, ty)| ty.clone())
                .ok_or_else(|| {
                    self.error(
                        descriptor,
                        format!("record field type is unavailable: {field_name}"),
                    )
                })?;
            fields.push(HirRecordField {
                name: field_name.to_owned(),
                ty: field_type,
            });
        }
        let symbol = self.define(name, HirSymbolKind::Binding);
        Ok(HirKind::Record {
            symbol,
            parents,
            fields,
        })
    }

    fn call(&mut self, values: &[Expr]) -> Result<HirKind, YinError> {
        let callee = Box::new(self.expression(&values[0])?);
        let mut arguments = Vec::new();
        let mut index = 1;
        while index < values.len() {
            let keyword = values[index]
                .atom()
                .and_then(|value| value.strip_prefix(':'))
                .map(str::to_owned);
            if keyword.is_some() {
                index += 1;
            }
            let value = values.get(index).ok_or_else(|| {
                self.error(
                    values.last().unwrap_or(&values[0]),
                    "keyword argument has no value",
                )
            })?;
            arguments.push(HirArgument {
                keyword,
                value: self.expression(value)?,
            });
            index += 1;
        }
        Ok(HirKind::Call { callee, arguments })
    }

    fn predeclare(&mut self, expressions: &[Expr]) -> Result<(), YinError> {
        for expression in expressions {
            let Expr::Form(Form::Tuple, values, _) = expression else {
                continue;
            };
            if values.first().and_then(Expr::atom) != Some("define") {
                continue;
            }
            let name = values.get(1).and_then(Expr::atom).ok_or_else(|| {
                self.unsupported(expression, "destructuring define is outside HIR phase 1")
            })?;
            if !self.scopes.last().unwrap().contains_key(name) {
                self.define(name, HirSymbolKind::Binding);
            }
        }
        Ok(())
    }

    fn type_of(&self, expression: &Expr) -> Result<Type, YinError> {
        if let Some(ty) = self.types.get(&SpanKey::of(expression)) {
            return Ok(ty.clone());
        }
        if let Expr::Atom(name, _) = expression {
            return Ok(builtin(name));
        }
        Err(self.error(expression, "checked expression type is unavailable"))
    }

    fn resolve(&mut self, name: &str) -> SymbolId {
        if let Some(symbol) = self.lookup(name) {
            return symbol;
        }
        if let Some(symbol) = self.builtins.get(name) {
            return *symbol;
        }
        let symbol = self.allocate(name, HirSymbolKind::Builtin);
        self.builtins.insert(name.to_owned(), symbol);
        symbol
    }

    fn lookup(&self, name: &str) -> Option<SymbolId> {
        self.scopes
            .iter()
            .rev()
            .find_map(|scope| scope.get(name).copied())
    }

    fn define(&mut self, name: &str, kind: HirSymbolKind) -> SymbolId {
        let symbol = self.allocate(name, kind);
        self.scopes
            .last_mut()
            .unwrap()
            .insert(name.to_owned(), symbol);
        symbol
    }

    fn allocate(&mut self, name: &str, kind: HirSymbolKind) -> SymbolId {
        let symbol = SymbolId(self.symbols.len() as u32);
        self.symbols.push(HirSymbol {
            id: symbol,
            name: name.to_owned(),
            kind,
        });
        symbol
    }

    fn push_scope(&mut self) {
        self.scopes.push(IndexMap::new());
    }

    fn pop_scope(&mut self) {
        self.scopes.pop();
    }

    fn unsupported(&self, expression: &Expr, message: impl Into<String>) -> YinError {
        self.error(expression, message)
    }

    fn error(&self, expression: &Expr, message: impl Into<String>) -> YinError {
        YinError::new(
            ErrorCode::Language,
            message,
            Some(expression.span().clone()),
        )
    }
}

pub fn render_hir(program: &HirProgram) -> String {
    let mut output = format!("program -> {:?}\n", program.result_type);
    output.push_str("symbols\n");
    for symbol in &program.symbols {
        output.push_str(&format!(
            "  %{} {} {:?}\n",
            symbol.id.0, symbol.name, symbol.kind
        ));
    }
    output.push_str("body\n");
    for expression in &program.expressions {
        render_expression(expression, 1, &mut output);
    }
    output
}

fn render_expression(expression: &HirExpr, depth: usize, output: &mut String) {
    let indent = "  ".repeat(depth);
    match &expression.kind {
        HirKind::Literal(value) => {
            output.push_str(&format!(
                "{indent}literal {value:?} -> {:?}\n",
                expression.ty
            ));
        }
        HirKind::Reference(symbol) => {
            output.push_str(&format!(
                "{indent}reference %{} -> {:?}\n",
                symbol.0, expression.ty
            ));
        }
        HirKind::Vector(values) => {
            output.push_str(&format!("{indent}vector -> {:?}\n", expression.ty));
            for value in values {
                render_expression(value, depth + 1, output);
            }
        }
        HirKind::Define { symbol, value } => {
            output.push_str(&format!(
                "{indent}define %{} -> {:?}\n",
                symbol.0, expression.ty
            ));
            render_expression(value, depth + 1, output);
        }
        HirKind::Function { parameters, body } => {
            output.push_str(&format!("{indent}function -> {:?}\n", expression.ty));
            for parameter in parameters {
                output.push_str(&format!(
                    "{}parameter %{} {:?} required={}\n",
                    "  ".repeat(depth + 1),
                    parameter.symbol.0,
                    parameter.ty,
                    parameter.required
                ));
            }
            for value in body {
                render_expression(value, depth + 1, output);
            }
        }
        HirKind::Record {
            symbol,
            parents,
            fields,
        } => {
            output.push_str(&format!(
                "{indent}record %{} parents={:?} -> {:?}\n",
                symbol.0,
                parents.iter().map(|parent| parent.0).collect::<Vec<_>>(),
                expression.ty
            ));
            for field in fields {
                output.push_str(&format!(
                    "{}field :{} {:?}\n",
                    "  ".repeat(depth + 1),
                    field.name,
                    field.ty
                ));
            }
        }
        HirKind::Call { callee, arguments } => {
            output.push_str(&format!("{indent}call -> {:?}\n", expression.ty));
            render_expression(callee, depth + 1, output);
            for argument in arguments {
                output.push_str(&format!(
                    "{}argument{}\n",
                    "  ".repeat(depth + 1),
                    argument
                        .keyword
                        .as_ref()
                        .map(|keyword| format!(" :{keyword}"))
                        .unwrap_or_default()
                ));
                render_expression(&argument.value, depth + 2, output);
            }
        }
        HirKind::If {
            condition,
            then_branch,
            else_branch,
        } => {
            output.push_str(&format!("{indent}if -> {:?}\n", expression.ty));
            render_expression(condition, depth + 1, output);
            render_expression(then_branch, depth + 1, output);
            render_expression(else_branch, depth + 1, output);
        }
        HirKind::Sequence(values) => {
            output.push_str(&format!("{indent}sequence -> {:?}\n", expression.ty));
            for value in values {
                render_expression(value, depth + 1, output);
            }
        }
        HirKind::FieldPath { root, fields } => {
            output.push_str(&format!(
                "{indent}field-path %{} .{} -> {:?}\n",
                root.0,
                fields.join("."),
                expression.ty
            ));
        }
        HirKind::Field { target, name } => {
            output.push_str(&format!("{indent}field :{name} -> {:?}\n", expression.ty));
            render_expression(target, depth + 1, output);
        }
    }
}

fn integer(value: &str) -> bool {
    value.parse::<num_bigint::BigInt>().is_ok()
        || value
            .strip_prefix("0x")
            .is_some_and(|value| num_bigint::BigInt::parse_bytes(value.as_bytes(), 16).is_some())
        || value
            .strip_prefix("0b")
            .is_some_and(|value| num_bigint::BigInt::parse_bytes(value.as_bytes(), 2).is_some())
}

fn float(value: &str) -> bool {
    value.contains('.') && value.parse::<f64>().is_ok()
}

fn dotted(value: &str) -> bool {
    value.contains('.') && !value.starts_with(':') && !float(value)
}
