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
    PatternBinding,
    Type,
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
    Variant {
        symbol: SymbolId,
        cases: Vec<HirVariantCase>,
    },
    Constructor {
        constructor: HirConstructor,
        arguments: Vec<HirArgument>,
    },
    Match {
        target: Box<HirExpr>,
        arms: Vec<HirMatchArm>,
    },
    DecodeJson {
        target_type: Type,
        input: Box<HirExpr>,
    },
    EncodeJson(Box<HirExpr>),
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HirConstructor {
    Ok,
    Err,
    Some,
    None,
    Record(SymbolId),
    VariantCase(SymbolId),
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirVariantCase {
    pub symbol: SymbolId,
    pub name: String,
    pub fields: Vec<HirRecordField>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirMatchArm {
    pub pattern: HirPattern,
    pub body: HirExpr,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, PartialEq)]
pub struct HirPattern {
    pub kind: HirPatternKind,
    pub ty: Type,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, PartialEq)]
pub enum HirPatternKind {
    Wildcard,
    Binding(SymbolId),
    Literal(HirLiteral),
    Vector(Vec<HirPattern>),
    Constructor {
        constructor: HirPatternConstructor,
        payloads: Vec<HirPattern>,
    },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HirPatternConstructor {
    Ok,
    Err,
    Some,
    None,
    Int,
    Float,
    Bool,
    String,
    Record(SymbolId),
    VariantCase(SymbolId),
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
        record_types: &report.record_types,
        variants: &report.variants,
        record_variants: &report.record_variants,
        scopes: vec![IndexMap::new()],
        builtins: IndexMap::new(),
        symbols: Vec::new(),
        symbol_types: IndexMap::new(),
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
    record_types: &'a IndexMap<String, Type>,
    variants: &'a IndexMap<String, Vec<String>>,
    record_variants: &'a IndexMap<String, String>,
    scopes: Vec<IndexMap<String, SymbolId>>,
    builtins: IndexMap<String, SymbolId>,
    symbols: Vec<HirSymbol>,
    symbol_types: IndexMap<SymbolId, Type>,
}

impl Lowerer<'_> {
    fn sequence(&mut self, expressions: &[Expr]) -> Result<Vec<HirExpr>, YinError> {
        expressions
            .iter()
            .map(|expression| self.expression(expression))
            .collect()
    }

    fn expression(&mut self, expression: &Expr) -> Result<HirExpr, YinError> {
        let mut ty = self.type_of(expression)?;
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
            Expr::Atom(value, _) if value == "none" => HirKind::Constructor {
                constructor: HirConstructor::None,
                arguments: Vec::new(),
            },
            Expr::Atom(value, _) if dotted(value) => {
                let mut parts = value.split('.');
                let root = self.resolve(parts.next().unwrap_or(value));
                let fields = parts.map(str::to_owned).collect::<Vec<_>>();
                if let Some(root_type) = self.symbol_types.get(&root) {
                    ty = self.field_path_type(root_type.clone(), &fields);
                }
                HirKind::FieldPath { root, fields }
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
                    self.unsupported(&values[1], "destructuring define is outside HIR phase 2")
                })?;
                let symbol = self.lookup(name).ok_or_else(|| {
                    self.error(expression, format!("unresolved definition: {name}"))
                })?;
                let value = self.expression(&values[2])?;
                self.symbol_types.insert(symbol, value.ty.clone());
                Ok(HirKind::Define {
                    symbol,
                    value: Box::new(value),
                })
            }
            Some("fun") => self.function(expression, values, ty),
            Some("record") => self.record(expression, values),
            Some("variant") => self.variant(expression, values),
            Some("match") => self.match_expression(expression, values),
            Some("policy") => self.policy(expression, values, ty),
            Some("decode-json") => {
                let target_type = match ty {
                    Type::Result(value, _) => (**value).clone(),
                    _ => {
                        return Err(self
                            .error(expression, "checked decode-json result has no payload type"));
                    }
                };
                Ok(HirKind::DecodeJson {
                    target_type,
                    input: Box::new(self.expression(&values[2])?),
                })
            }
            Some("encode-json") => Ok(HirKind::EncodeJson(Box::new(self.expression(&values[1])?))),
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
            Some("set!" | "module" | "import" | "tool" | "invoke" | "json-schema") => Err(self
                .unsupported(
                    expression,
                    format!(
                        "{} is outside HIR phase 2",
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
                        self.unsupported(descriptor, "default parameters are outside HIR phase 2")
                    );
                }
                let parameter_type = inputs.get(input_index).cloned().ok_or_else(|| {
                    self.error(descriptor, "function parameter type is unavailable")
                })?;
                let symbol = self.define(name, HirSymbolKind::Parameter);
                self.symbol_types.insert(symbol, parameter_type.clone());
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

    fn policy(
        &mut self,
        expression: &Expr,
        values: &[Expr],
        ty: &Type,
    ) -> Result<HirKind, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| self.error(expression, "policy expects a name"))?;
        let mut body = None;
        for rule in values[3..].iter().rev() {
            let Expr::Form(Form::Tuple, parts, span) = rule else {
                return Err(self.error(rule, "invalid policy rule"));
            };
            match parts.first().and_then(Expr::atom) {
                Some("otherwise") => body = parts.get(1).cloned(),
                Some("when") => {
                    let fallback = body
                        .take()
                        .ok_or_else(|| self.error(rule, "policy requires otherwise"))?;
                    body = Some(Expr::Form(
                        Form::Tuple,
                        vec![
                            Expr::Atom("if".into(), span.clone()),
                            parts[1].clone(),
                            parts[2].clone(),
                            fallback,
                        ],
                        span.clone(),
                    ));
                }
                _ => return Err(self.error(rule, "invalid policy rule")),
            }
        }
        let body = body.ok_or_else(|| self.error(expression, "policy requires otherwise"))?;
        let function_kind = self.function(
            expression,
            &[
                values[0].clone(),
                values
                    .get(2)
                    .cloned()
                    .ok_or_else(|| self.error(expression, "policy parameters are missing"))?,
                body,
            ],
            ty,
        )?;
        let symbol = self.define(name, HirSymbolKind::Binding);
        self.symbol_types.insert(symbol, ty.clone());
        Ok(HirKind::Define {
            symbol,
            value: Box::new(HirExpr {
                kind: function_kind,
                ty: ty.clone(),
                span: expression.span().clone(),
            }),
        })
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
                return Err(self.unsupported(descriptor, "record defaults are outside HIR phase 2"));
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
        self.symbol_types.insert(
            symbol,
            self.record_types
                .get(name)
                .cloned()
                .unwrap_or_else(|| Type::Named(name.to_owned())),
        );
        Ok(HirKind::Record {
            symbol,
            parents,
            fields,
        })
    }

    fn variant(&mut self, expression: &Expr, values: &[Expr]) -> Result<HirKind, YinError> {
        let name = values
            .get(1)
            .and_then(Expr::atom)
            .ok_or_else(|| self.error(expression, "variant expects a name"))?;
        let symbol = self.define(name, HirSymbolKind::Type);
        self.symbol_types
            .insert(symbol, Type::Named(name.to_owned()));
        let expected_cases = self
            .variants
            .get(name)
            .ok_or_else(|| self.error(expression, format!("variant cases unavailable: {name}")))?;
        let mut cases = Vec::new();
        for case in &values[2..] {
            let Expr::Form(Form::Vector, parts, _) = case else {
                return Err(self.error(case, "variant case must be a vector"));
            };
            let case_name = parts
                .first()
                .and_then(Expr::atom)
                .ok_or_else(|| self.error(case, "variant case requires a name"))?;
            if !expected_cases.iter().any(|expected| expected == case_name) {
                return Err(self.error(case, format!("variant case unavailable: {case_name}")));
            }
            let checked_fields = self.record_fields.get(case_name).ok_or_else(|| {
                self.error(case, format!("variant fields unavailable: {case_name}"))
            })?;
            let mut fields = Vec::new();
            for descriptor in &parts[1..] {
                let Expr::Form(Form::Vector, field_parts, _) = descriptor else {
                    return Err(self.error(descriptor, "variant field must be a descriptor"));
                };
                if field_parts
                    .iter()
                    .any(|part| part.atom() == Some(":default"))
                {
                    return Err(
                        self.unsupported(descriptor, "variant defaults are outside HIR phase 2")
                    );
                }
                let field_name = field_parts
                    .first()
                    .and_then(Expr::atom)
                    .ok_or_else(|| self.error(descriptor, "variant field name is missing"))?;
                let field_type = checked_fields
                    .iter()
                    .find(|(candidate, _)| candidate == field_name)
                    .map(|(_, ty)| ty.clone())
                    .ok_or_else(|| {
                        self.error(
                            descriptor,
                            format!("variant field type is unavailable: {field_name}"),
                        )
                    })?;
                fields.push(HirRecordField {
                    name: field_name.to_owned(),
                    ty: field_type,
                });
            }
            let case_symbol = self.define(case_name, HirSymbolKind::Binding);
            if let Some(case_type) = self.record_types.get(case_name) {
                self.symbol_types.insert(case_symbol, case_type.clone());
            }
            cases.push(HirVariantCase {
                symbol: case_symbol,
                name: case_name.to_owned(),
                fields,
            });
        }
        Ok(HirKind::Variant { symbol, cases })
    }

    fn match_expression(
        &mut self,
        expression: &Expr,
        values: &[Expr],
    ) -> Result<HirKind, YinError> {
        let target = self.expression(&values[1])?;
        let target_type = target.ty.clone();
        let mut arms = Vec::new();
        for clause in &values[2..] {
            let Expr::Form(Form::Vector, parts, span) = clause else {
                return Err(self.error(clause, "match clause must be a vector"));
            };
            self.push_scope();
            let lowered = (|| {
                let pattern_expression = parts
                    .first()
                    .ok_or_else(|| self.error(clause, "match pattern missing"))?;
                let pattern = self.pattern(pattern_expression, &target_type)?;
                let body_expression = parts
                    .get(1)
                    .ok_or_else(|| self.error(clause, "match body missing"))?;
                let body = self.expression(body_expression)?;
                Ok(HirMatchArm {
                    pattern,
                    body,
                    span: span.clone(),
                })
            })();
            self.pop_scope();
            arms.push(lowered?);
        }
        if arms.is_empty() {
            return Err(self.error(expression, "match expects clauses"));
        }
        Ok(HirKind::Match {
            target: Box::new(target),
            arms,
        })
    }

    fn pattern(&mut self, pattern: &Expr, target: &Type) -> Result<HirPattern, YinError> {
        let (kind, ty) = match pattern {
            Expr::Atom(name, _) if name == "_" => (HirPatternKind::Wildcard, target.clone()),
            Expr::Atom(name, _) if name == "true" || name == "false" => (
                HirPatternKind::Literal(HirLiteral::Bool(name == "true")),
                Type::Bool,
            ),
            Expr::Atom(value, _) if integer(value) => (
                HirPatternKind::Literal(HirLiteral::Int(value.clone())),
                Type::Int,
            ),
            Expr::Atom(name, _) => {
                let symbol = self.define(name, HirSymbolKind::PatternBinding);
                self.symbol_types.insert(symbol, target.clone());
                (HirPatternKind::Binding(symbol), target.clone())
            }
            Expr::String(value, _) => (
                HirPatternKind::Literal(HirLiteral::String(value.clone())),
                Type::String,
            ),
            Expr::Form(Form::Vector, parts, _) => {
                let element_types = pattern_vector_elements(target).ok_or_else(|| {
                    self.error(pattern, "checked vector pattern target is not a vector")
                })?;
                let mut patterns = Vec::new();
                for (index, part) in parts.iter().enumerate() {
                    let element_type = element_types
                        .get(index)
                        .cloned()
                        .unwrap_or_else(|| union_types(element_types.clone()));
                    patterns.push(self.pattern(part, &element_type)?);
                }
                (HirPatternKind::Vector(patterns), target.clone())
            }
            Expr::Form(Form::Tuple, parts, _) => {
                let name = parts
                    .first()
                    .and_then(Expr::atom)
                    .ok_or_else(|| self.error(pattern, "invalid match pattern"))?;
                let (constructor, payload_types, pattern_type) =
                    self.pattern_constructor(pattern, name, target)?;
                let mut payloads = Vec::new();
                for (part, payload_type) in parts[1..].iter().zip(payload_types) {
                    payloads.push(self.pattern(part, &payload_type)?);
                }
                (
                    HirPatternKind::Constructor {
                        constructor,
                        payloads,
                    },
                    pattern_type,
                )
            }
        };
        Ok(HirPattern {
            kind,
            ty,
            span: pattern.span().clone(),
        })
    }

    fn pattern_constructor(
        &self,
        pattern: &Expr,
        name: &str,
        target: &Type,
    ) -> Result<(HirPatternConstructor, Vec<Type>, Type), YinError> {
        let constructor = match name {
            "Ok" => {
                let payload = result_payload(target, true).unwrap_or(Type::Any);
                return Ok((
                    HirPatternConstructor::Ok,
                    vec![payload.clone()],
                    Type::Ok(Box::new(payload)),
                ));
            }
            "Err" => {
                let payload = result_payload(target, false).unwrap_or(Type::Any);
                return Ok((
                    HirPatternConstructor::Err,
                    vec![payload.clone()],
                    Type::Err(Box::new(payload)),
                ));
            }
            "Some" => {
                let payload = option_payload(target).unwrap_or(Type::Any);
                return Ok((
                    HirPatternConstructor::Some,
                    vec![payload.clone()],
                    Type::Some(Box::new(payload)),
                ));
            }
            "None" => {
                return Ok((HirPatternConstructor::None, Vec::new(), Type::None));
            }
            "Int" => HirPatternConstructor::Int,
            "Float" => HirPatternConstructor::Float,
            "Bool" => HirPatternConstructor::Bool,
            "String" => HirPatternConstructor::String,
            record if self.record_fields.contains_key(record) => {
                let symbol = self.lookup(record).ok_or_else(|| {
                    self.error(pattern, format!("unresolved pattern constructor: {record}"))
                })?;
                let constructor = if self.record_variants.contains_key(record) {
                    HirPatternConstructor::VariantCase(symbol)
                } else {
                    HirPatternConstructor::Record(symbol)
                };
                let payloads = self.record_fields[record]
                    .iter()
                    .map(|(_, ty)| ty.clone())
                    .collect();
                let ty = self
                    .record_types
                    .get(record)
                    .cloned()
                    .unwrap_or_else(|| Type::Named(record.to_owned()));
                return Ok((constructor, payloads, ty));
            }
            _ => return Err(self.error(pattern, format!("unknown pattern: {name}"))),
        };
        let ty = match name {
            "Int" => Type::Int,
            "Float" => Type::Float,
            "Bool" => Type::Bool,
            _ => Type::String,
        };
        Ok((constructor, vec![ty.clone()], ty))
    }

    fn call(&mut self, values: &[Expr]) -> Result<HirKind, YinError> {
        let arguments = self.arguments(values)?;
        if let Some(name) = values[0].atom() {
            let builtin_constructor = match name {
                "ok" => Some(HirConstructor::Ok),
                "err" => Some(HirConstructor::Err),
                "some" => Some(HirConstructor::Some),
                _ => None,
            };
            if let Some(constructor) = builtin_constructor {
                return Ok(HirKind::Constructor {
                    constructor,
                    arguments,
                });
            }
            if let Ok(Type::Record { .. }) = self.type_of(&values[0]) {
                let symbol = self.lookup(name).ok_or_else(|| {
                    self.error(&values[0], format!("unresolved constructor: {name}"))
                })?;
                let constructor = if self.record_variants.contains_key(name) {
                    HirConstructor::VariantCase(symbol)
                } else {
                    HirConstructor::Record(symbol)
                };
                return Ok(HirKind::Constructor {
                    constructor,
                    arguments,
                });
            }
        }
        let callee = Box::new(self.expression(&values[0])?);
        Ok(HirKind::Call { callee, arguments })
    }

    fn arguments(&mut self, values: &[Expr]) -> Result<Vec<HirArgument>, YinError> {
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
        Ok(arguments)
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
                self.unsupported(expression, "destructuring define is outside HIR phase 2")
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
        self.symbol_types.insert(symbol, builtin(name));
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
        self.symbol_types.insert(symbol, Type::Any);
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

    fn field_path_type(&self, mut ty: Type, fields: &[String]) -> Type {
        for field in fields {
            ty = match ty {
                Type::Record { name, .. } => self
                    .record_fields
                    .get(&name)
                    .and_then(|fields| {
                        fields
                            .iter()
                            .find(|(candidate, _)| candidate == field)
                            .map(|(_, ty)| ty.clone())
                    })
                    .unwrap_or(Type::Any),
                Type::Union(members) => union_types(
                    members
                        .into_iter()
                        .map(|member| self.field_path_type(member, std::slice::from_ref(field)))
                        .collect(),
                ),
                _ => Type::Any,
            };
        }
        ty
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
        HirKind::Variant { symbol, cases } => {
            output.push_str(&format!(
                "{indent}variant %{} -> {:?}\n",
                symbol.0, expression.ty
            ));
            for case in cases {
                output.push_str(&format!(
                    "{}case %{} {}\n",
                    "  ".repeat(depth + 1),
                    case.symbol.0,
                    case.name
                ));
                for field in &case.fields {
                    output.push_str(&format!(
                        "{}field :{} {:?}\n",
                        "  ".repeat(depth + 2),
                        field.name,
                        field.ty
                    ));
                }
            }
        }
        HirKind::Constructor {
            constructor,
            arguments,
        } => {
            output.push_str(&format!(
                "{indent}constructor {constructor:?} -> {:?}\n",
                expression.ty
            ));
            render_arguments(arguments, depth, output);
        }
        HirKind::Match { target, arms } => {
            output.push_str(&format!("{indent}match -> {:?}\n", expression.ty));
            render_expression(target, depth + 1, output);
            for arm in arms {
                output.push_str(&format!("{}arm\n", "  ".repeat(depth + 1)));
                render_pattern(&arm.pattern, depth + 2, output);
                render_expression(&arm.body, depth + 2, output);
            }
        }
        HirKind::DecodeJson { target_type, input } => {
            output.push_str(&format!(
                "{indent}decode-json {target_type:?} -> {:?}\n",
                expression.ty
            ));
            render_expression(input, depth + 1, output);
        }
        HirKind::EncodeJson(value) => {
            output.push_str(&format!("{indent}encode-json -> {:?}\n", expression.ty));
            render_expression(value, depth + 1, output);
        }
        HirKind::Call { callee, arguments } => {
            output.push_str(&format!("{indent}call -> {:?}\n", expression.ty));
            render_expression(callee, depth + 1, output);
            render_arguments(arguments, depth, output);
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

fn render_arguments(arguments: &[HirArgument], depth: usize, output: &mut String) {
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

fn render_pattern(pattern: &HirPattern, depth: usize, output: &mut String) {
    let indent = "  ".repeat(depth);
    match &pattern.kind {
        HirPatternKind::Wildcard => {
            output.push_str(&format!("{indent}pattern wildcard -> {:?}\n", pattern.ty));
        }
        HirPatternKind::Binding(symbol) => {
            output.push_str(&format!(
                "{indent}pattern binding %{} -> {:?}\n",
                symbol.0, pattern.ty
            ));
        }
        HirPatternKind::Literal(literal) => {
            output.push_str(&format!(
                "{indent}pattern literal {literal:?} -> {:?}\n",
                pattern.ty
            ));
        }
        HirPatternKind::Vector(patterns) => {
            output.push_str(&format!("{indent}pattern vector -> {:?}\n", pattern.ty));
            for pattern in patterns {
                render_pattern(pattern, depth + 1, output);
            }
        }
        HirPatternKind::Constructor {
            constructor,
            payloads,
        } => {
            output.push_str(&format!(
                "{indent}pattern constructor {constructor:?} -> {:?}\n",
                pattern.ty
            ));
            for payload in payloads {
                render_pattern(payload, depth + 1, output);
            }
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

fn pattern_vector_elements(ty: &Type) -> Option<Vec<Type>> {
    match ty {
        Type::ExactVector(values) => Some(values.clone()),
        Type::Vector(element) => Some(vec![(**element).clone()]),
        _ => None,
    }
}

fn result_payload(ty: &Type, ok: bool) -> Option<Type> {
    match (ty, ok) {
        (Type::Result(value, _), true) | (Type::Ok(value), true) => Some((**value).clone()),
        (Type::Result(_, value), false) | (Type::Err(value), false) => Some((**value).clone()),
        _ => None,
    }
}

fn option_payload(ty: &Type) -> Option<Type> {
    match ty {
        Type::Option(value) | Type::Some(value) => Some((**value).clone()),
        _ => None,
    }
}

fn union_types(types: Vec<Type>) -> Type {
    let mut unique = Vec::new();
    for ty in types {
        if !unique.contains(&ty) {
            unique.push(ty);
        }
    }
    match unique.as_slice() {
        [] => Type::Never,
        [only] => only.clone(),
        _ => Type::Union(unique),
    }
}
