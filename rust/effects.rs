use crate::{Expr, Form, ParsedProgram, SourceSpan};
use indexmap::{IndexMap, IndexSet};
use serde::Serialize;

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Effect {
    Allocation,
    HostIo,
    Mutation,
    PersistentState,
    ExternalCall,
    Account,
    Hashing,
    Signature,
    Authorization,
    ModuleLoad,
    DynamicCall,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct EffectOrigin {
    pub effect: Effect,
    pub operation: String,
    pub owner: String,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct FunctionEffects {
    pub name: String,
    pub effects: Vec<Effect>,
    pub calls: Vec<String>,
    pub span: SourceSpan,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct EffectReport {
    pub effects: Vec<Effect>,
    pub entry_effects: Vec<Effect>,
    pub functions: Vec<FunctionEffects>,
    pub origins: Vec<EffectOrigin>,
}

pub fn infer_effects(program: &ParsedProgram) -> EffectReport {
    Analyzer::new(program).analyze()
}

#[derive(Clone)]
struct FunctionSource<'a> {
    name: String,
    body: Vec<&'a Expr>,
    span: SourceSpan,
}

#[derive(Clone, Default)]
struct ToolSource {
    effect: String,
    approval: bool,
}

#[derive(Clone)]
struct Summary {
    name: String,
    direct: IndexSet<Effect>,
    effects: IndexSet<Effect>,
    calls: IndexSet<String>,
    span: SourceSpan,
}

struct Analyzer<'a> {
    program: &'a ParsedProgram,
    functions: IndexMap<String, FunctionSource<'a>>,
    tools: IndexMap<String, ToolSource>,
    constructors: IndexSet<String>,
    origins: Vec<EffectOrigin>,
}

impl<'a> Analyzer<'a> {
    fn new(program: &'a ParsedProgram) -> Self {
        let mut analyzer = Self {
            program,
            functions: IndexMap::new(),
            tools: IndexMap::new(),
            constructors: IndexSet::new(),
            origins: Vec::new(),
        };
        analyzer.collect_declarations(&program.expressions);
        analyzer
    }

    fn analyze(mut self) -> EffectReport {
        let mut summaries = IndexMap::<String, Summary>::new();
        let entry_span = self
            .program
            .expressions
            .first()
            .map(|expression| expression.span().clone())
            .unwrap_or_else(empty_span);
        let mut entry = Summary {
            name: "<entry>".into(),
            direct: IndexSet::new(),
            effects: IndexSet::new(),
            calls: IndexSet::new(),
            span: entry_span,
        };
        for expression in &self.program.expressions {
            if named_function(expression).is_none() && policy_name(expression).is_none() {
                self.scan(expression, "<entry>", &mut entry.direct, &mut entry.calls);
            }
        }
        entry.effects = entry.direct.clone();
        summaries.insert(entry.name.clone(), entry);

        let functions = self.functions.values().cloned().collect::<Vec<_>>();
        for function in functions {
            let mut summary = Summary {
                name: function.name.clone(),
                direct: IndexSet::new(),
                effects: IndexSet::new(),
                calls: IndexSet::new(),
                span: function.span,
            };
            for expression in function.body {
                self.scan(
                    expression,
                    &function.name,
                    &mut summary.direct,
                    &mut summary.calls,
                );
            }
            summary.effects = summary.direct.clone();
            summaries.insert(function.name, summary);
        }

        loop {
            let snapshot = summaries
                .iter()
                .map(|(name, summary)| (name.clone(), summary.effects.clone()))
                .collect::<IndexMap<_, _>>();
            let mut changed = false;
            for summary in summaries.values_mut() {
                for call in &summary.calls {
                    if let Some(effects) = snapshot.get(call) {
                        for effect in effects {
                            changed |= summary.effects.insert(*effect);
                        }
                    }
                }
            }
            if !changed {
                break;
            }
        }

        let entry_effects = summaries
            .get("<entry>")
            .map(|summary| summary.effects.iter().copied().collect())
            .unwrap_or_default();
        let mut all = IndexSet::new();
        for summary in summaries.values() {
            all.extend(summary.effects.iter().copied());
        }
        let functions = summaries
            .into_values()
            .map(|summary| FunctionEffects {
                name: summary.name,
                effects: summary.effects.into_iter().collect(),
                calls: summary.calls.into_iter().collect(),
                span: summary.span,
            })
            .collect();
        EffectReport {
            effects: all.into_iter().collect(),
            entry_effects,
            functions,
            origins: self.origins,
        }
    }

    fn collect_declarations(&mut self, expressions: &'a [Expr]) {
        for expression in expressions {
            if let Some((name, body, span)) = named_function(expression) {
                let key = if self.functions.contains_key(name) {
                    format!("{name}@{}", span.start)
                } else {
                    name.to_owned()
                };
                self.functions.insert(
                    key.clone(),
                    FunctionSource {
                        name: key,
                        body: body.iter().collect(),
                        span: span.clone(),
                    },
                );
                self.collect_declarations(body);
                continue;
            } else if let Some((name, body, span)) = policy_function(expression) {
                let key = if self.functions.contains_key(name) {
                    format!("{name}@{}", span.start)
                } else {
                    name.to_owned()
                };
                self.functions.insert(
                    key.clone(),
                    FunctionSource {
                        name: key,
                        body: body.clone(),
                        span: span.clone(),
                    },
                );
                for expression in body {
                    self.collect_declarations(std::slice::from_ref(expression));
                }
                continue;
            }
            let Expr::Form(Form::Tuple, values, _) = expression else {
                continue;
            };
            match values.first().and_then(Expr::atom) {
                Some("record") => {
                    if let Some(name) = values.get(1).and_then(Expr::atom) {
                        self.constructors.insert(name.to_owned());
                    }
                }
                Some("variant") => {
                    for case in &values[2..] {
                        if let Expr::Form(Form::Vector, parts, _) = case {
                            if let Some(name) = parts.first().and_then(Expr::atom) {
                                self.constructors.insert(name.to_owned());
                            }
                        }
                    }
                }
                Some("tool") => {
                    if let Some(name) = values.get(1).and_then(Expr::atom) {
                        self.tools.insert(name.to_owned(), tool_source(values));
                    }
                }
                Some("seq" | "module" | "if") => self.collect_declarations(&values[1..]),
                Some("define" | "fun") => self.collect_declarations(&values[2..]),
                Some("match") => {
                    for arm in &values[2..] {
                        if let Expr::Form(Form::Vector, parts, _) = arm {
                            if let Some(body) = parts.get(1) {
                                self.collect_declarations(std::slice::from_ref(body));
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }

    fn scan(
        &mut self,
        expression: &Expr,
        owner: &str,
        effects: &mut IndexSet<Effect>,
        calls: &mut IndexSet<String>,
    ) {
        let Expr::Form(form, values, _) = expression else {
            return;
        };
        if *form == Form::Vector {
            self.effect(expression, owner, Effect::Allocation, "vector", effects);
            self.scan_all(values, owner, effects, calls);
            return;
        }
        let Some(name) = values.first().and_then(Expr::atom) else {
            self.effect(
                expression,
                owner,
                Effect::DynamicCall,
                "computed-call",
                effects,
            );
            self.scan_all(values, owner, effects, calls);
            return;
        };
        match name {
            "define" if named_function(expression).is_some() => {}
            "define" => self.scan_all(&values[2..], owner, effects, calls),
            "fun" => {
                self.effect(expression, owner, Effect::Allocation, "closure", effects);
                self.scan_all(&values[2..], owner, effects, calls);
            }
            "if" => self.scan_all(&values[1..], owner, effects, calls),
            "seq" => self.scan_all(&values[1..], owner, effects, calls),
            "match" => {
                if let Some(target) = values.get(1) {
                    self.scan(target, owner, effects, calls);
                }
                for arm in &values[2..] {
                    if let Expr::Form(Form::Vector, parts, _) = arm {
                        if let Some(body) = parts.get(1) {
                            self.scan(body, owner, effects, calls);
                        }
                    }
                }
            }
            "policy" => {}
            "record" | "variant" => {}
            "set!" => {
                self.effect(expression, owner, Effect::Mutation, "set!", effects);
                self.scan_all(&values[2..], owner, effects, calls);
            }
            "module" => {
                self.effect(expression, owner, Effect::ModuleLoad, "module", effects);
                self.scan_all(&values[3..], owner, effects, calls);
            }
            "import" => self.effect(expression, owner, Effect::ModuleLoad, "import", effects),
            "tool" => self.effect(
                expression,
                owner,
                Effect::ExternalCall,
                "tool-declaration",
                effects,
            ),
            "invoke" => {
                self.effect(expression, owner, Effect::ExternalCall, "invoke", effects);
                if let Some(tool) = values.get(1).and_then(Expr::atom) {
                    if let Some(source) = self.tools.get(tool).cloned() {
                        if source.effect != "read" {
                            self.effect(
                                expression,
                                owner,
                                Effect::PersistentState,
                                format!("tool:{tool}:{}", source.effect),
                                effects,
                            );
                        }
                        if source.approval {
                            self.effect(
                                expression,
                                owner,
                                Effect::Authorization,
                                format!("tool:{tool}:approval"),
                                effects,
                            );
                        }
                    }
                }
                self.scan_all(&values[2..], owner, effects, calls);
            }
            "read-all" | "read-text" | "print" => {
                self.effect(expression, owner, Effect::HostIo, name, effects);
                self.scan_all(&values[1..], owner, effects, calls);
            }
            "decode-json" | "encode-json" | "json-schema" => {
                self.effect(expression, owner, Effect::Allocation, name, effects);
                let start = if name == "decode-json" { 2 } else { 1 };
                self.scan_all(&values[start..], owner, effects, calls);
            }
            "map" | "filter" | "fold" => {
                self.effect(expression, owner, Effect::Allocation, name, effects);
                let callback_index = values.len().saturating_sub(1);
                self.scan_all(&values[1..callback_index], owner, effects, calls);
                if let Some(callback) = values.get(callback_index) {
                    if let Some(callback_name) = callback.atom() {
                        if self.functions.contains_key(callback_name) {
                            calls.insert(callback_name.to_owned());
                        } else {
                            self.effect(
                                callback,
                                owner,
                                Effect::DynamicCall,
                                format!("callback:{callback_name}"),
                                effects,
                            );
                        }
                    } else {
                        self.scan(callback, owner, effects, calls);
                    }
                }
            }
            name if allocating_builtin(name) || self.constructors.contains(name) => {
                self.effect(expression, owner, Effect::Allocation, name, effects);
                self.scan_all(&values[1..], owner, effects, calls);
            }
            name if self.functions.contains_key(name) => {
                calls.insert(name.to_owned());
                self.scan_all(&values[1..], owner, effects, calls);
            }
            name if pure_builtin(name) => self.scan_all(&values[1..], owner, effects, calls),
            _ => {
                self.effect(
                    expression,
                    owner,
                    Effect::DynamicCall,
                    format!("call:{name}"),
                    effects,
                );
                self.scan_all(&values[1..], owner, effects, calls);
            }
        }
    }

    fn scan_all(
        &mut self,
        expressions: &[Expr],
        owner: &str,
        effects: &mut IndexSet<Effect>,
        calls: &mut IndexSet<String>,
    ) {
        for expression in expressions {
            self.scan(expression, owner, effects, calls);
        }
    }

    fn effect(
        &mut self,
        expression: &Expr,
        owner: &str,
        effect: Effect,
        operation: impl Into<String>,
        effects: &mut IndexSet<Effect>,
    ) {
        effects.insert(effect);
        let operation = operation.into();
        if !self.origins.iter().any(|origin| {
            origin.effect == effect
                && origin.operation == operation
                && origin.span == *expression.span()
        }) {
            self.origins.push(EffectOrigin {
                effect,
                operation,
                owner: owner.to_owned(),
                span: expression.span().clone(),
            });
        }
    }
}

fn named_function(expression: &Expr) -> Option<(&str, &[Expr], &SourceSpan)> {
    let Expr::Form(Form::Tuple, values, span) = expression else {
        return None;
    };
    if values.first()?.atom()? != "define" {
        return None;
    }
    let name = values.get(1)?.atom()?;
    let Expr::Form(Form::Tuple, function, _) = values.get(2)? else {
        return None;
    };
    if function.first()?.atom()? != "fun" {
        return None;
    }
    Some((name, function.get(2..)?, span))
}

fn policy_name(expression: &Expr) -> Option<&str> {
    let Expr::Form(Form::Tuple, values, _) = expression else {
        return None;
    };
    (values.first()?.atom()? == "policy")
        .then(|| values.get(1)?.atom())
        .flatten()
}

fn policy_function(expression: &Expr) -> Option<(&str, Vec<&Expr>, &SourceSpan)> {
    let Expr::Form(Form::Tuple, values, span) = expression else {
        return None;
    };
    if values.first()?.atom()? != "policy" {
        return None;
    }
    let name = values.get(1)?.atom()?;
    let mut body = Vec::new();
    for rule in &values[3..] {
        if let Expr::Form(Form::Tuple, parts, _) = rule {
            body.extend(parts.iter().skip(1));
        }
    }
    Some((name, body, span))
}

fn tool_source(values: &[Expr]) -> ToolSource {
    let property = |name: &str| {
        values
            .iter()
            .position(|value| value.atom() == Some(name))
            .and_then(|index| values.get(index + 1))
            .and_then(Expr::atom)
    };
    ToolSource {
        effect: property(":effect")
            .unwrap_or(":read")
            .trim_start_matches(':')
            .to_owned(),
        approval: property(":approval") == Some("true"),
    }
}

fn allocating_builtin(name: &str) -> bool {
    matches!(
        name,
        "append"
            | "range"
            | "slice"
            | "reverse"
            | "dict"
            | "dict/put"
            | "dict/remove"
            | "dict/keys"
            | "dict/values"
            | "set"
            | "set/add"
            | "set/remove"
            | "set/values"
            | "set/union"
            | "set/intersection"
            | "set/difference"
            | "ok"
            | "err"
            | "some"
            | "concat"
            | "substring"
            | "split"
            | "join"
            | "trim"
            | "to-string"
            | "parse-int"
            | "parse-float"
    )
}

fn pure_builtin(name: &str) -> bool {
    matches!(
        name,
        "+" | "-"
            | "*"
            | "/"
            | "<"
            | "<="
            | ">"
            | ">="
            | "="
            | "and"
            | "or"
            | "not"
            | "length"
            | "at"
            | "fold"
            | "contains"
            | "dict/get"
            | "dict/contains-key"
            | "dict/size"
            | "set/contains"
            | "set/size"
            | "string-length"
    )
}

fn empty_span() -> SourceSpan {
    SourceSpan {
        file: "<effects>".into(),
        start: 0,
        end: 0,
        line: 1,
        column: 1,
    }
}
