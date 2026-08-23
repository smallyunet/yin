use crate::eval::{Environment, Parameter};
use crate::syntax::Expr;
use indexmap::IndexMap;
use num_bigint::BigInt;
use std::fmt;
use std::rc::Rc;

#[derive(Clone, Debug, PartialEq)]
pub enum Type {
    Any,
    Never,
    Void,
    Int,
    Float,
    Bool,
    String,
    ExactVector(Vec<Type>),
    Vector(Box<Type>),
    Dict(Box<Type>, Box<Type>),
    Set(Box<Type>),
    Result(Box<Type>, Box<Type>),
    Ok(Box<Type>),
    Err(Box<Type>),
    Option(Box<Type>),
    Some(Box<Type>),
    None,
    Function(Vec<Type>, usize, Box<Type>),
    Tool(Box<Type>, Box<Type>, Box<Type>),
    Record { name: String, parents: Vec<String> },
    Named(String),
    Union(Vec<Type>),
}

#[derive(Clone)]
pub struct Function {
    pub parameters: Vec<Parameter>,
    pub body: Vec<Expr>,
    pub environment: Environment,
}

#[derive(Clone, Debug)]
pub struct RecordDefinition {
    pub name: String,
    pub fields: Vec<(String, Expr, Option<Value>)>,
    pub parents: Vec<String>,
    pub variant: Option<String>,
}

#[derive(Clone)]
pub enum Value {
    Void,
    Int(BigInt),
    Float(f64),
    Bool(bool),
    String(String),
    Vector(Vec<Value>),
    Dict(Vec<(Value, Value)>),
    Set(Vec<Value>),
    Function(Rc<Function>),
    Primitive(&'static str),
    RecordDefinition(Rc<RecordDefinition>),
    VariantDefinition {
        name: String,
        cases: Vec<String>,
    },
    Record {
        name: String,
        fields: IndexMap<String, Value>,
        parents: Vec<String>,
        variant: Option<String>,
    },
    Result {
        ok: bool,
        value: Box<Value>,
    },
    Option(Option<Box<Value>>),
    Tool {
        name: String,
        input: Expr,
        output: Expr,
        error: Expr,
        capability: String,
        effect: String,
        approval: bool,
    },
    Type(Type),
}

impl fmt::Debug for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self}")
    }
}

impl PartialEq for Value {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Void, Self::Void) => true,
            (Self::Int(a), Self::Int(b)) => a == b,
            (Self::Float(a), Self::Float(b)) => a == b,
            (Self::Int(a), Self::Float(b)) | (Self::Float(b), Self::Int(a)) => {
                a.to_string().parse::<f64>().ok() == Some(*b)
            }
            (Self::Bool(a), Self::Bool(b)) => a == b,
            (Self::String(a), Self::String(b)) => a == b,
            (Self::Vector(a), Self::Vector(b)) => a == b,
            (Self::Dict(a), Self::Dict(b)) => unordered_pairs_equal(a, b),
            (Self::Set(a), Self::Set(b)) => a.len() == b.len() && a.iter().all(|v| b.contains(v)),
            (
                Self::Record {
                    name: an,
                    fields: af,
                    ..
                },
                Self::Record {
                    name: bn,
                    fields: bf,
                    ..
                },
            ) => an == bn && af == bf,
            (Self::Result { ok: ao, value: av }, Self::Result { ok: bo, value: bv }) => {
                ao == bo && av == bv
            }
            (Self::Option(a), Self::Option(b)) => a == b,
            (Self::Type(a), Self::Type(b)) => a == b,
            _ => false,
        }
    }
}

fn unordered_pairs_equal(left: &[(Value, Value)], right: &[(Value, Value)]) -> bool {
    left.len() == right.len() && left.iter().all(|pair| right.contains(pair))
}

impl fmt::Display for Value {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Void => f.write_str("void"),
            Self::Int(value) => write!(f, "{value}"),
            Self::Float(value) => write!(f, "{value}"),
            Self::Bool(value) => write!(f, "{value}"),
            Self::String(value) => {
                let encoded = serde_json::to_string(value).map_err(|_| fmt::Error)?;
                f.write_str(&encoded)
            }
            Self::Vector(values) => write_joined(f, "[", "]", values),
            Self::Dict(entries) => {
                f.write_str("(dict")?;
                for (key, value) in entries {
                    write!(f, " {key} {value}")?;
                }
                f.write_str(")")
            }
            Self::Set(values) => {
                f.write_str("(set")?;
                for value in values {
                    write!(f, " {value}")?;
                }
                f.write_str(")")
            }
            Self::Function(_) => f.write_str("<function>"),
            Self::Primitive(name) => write!(f, "<primitive:{name}>"),
            Self::RecordDefinition(definition) => write!(f, "<record:{}>", definition.name),
            Self::VariantDefinition { name, .. } => write!(f, "<variant:{name}>"),
            Self::Record { name, fields, .. } => {
                write!(f, "(record {name}")?;
                for (field, value) in fields {
                    write!(f, " [{field} {value}]")?;
                }
                f.write_str(")")
            }
            Self::Result { ok, value } => write!(f, "({} {value})", if *ok { "ok" } else { "err" }),
            Self::Option(Some(value)) => write!(f, "(some {value})"),
            Self::Option(None) => f.write_str("none"),
            Self::Tool { name, .. } => write!(f, "<tool:{name}>"),
            Self::Type(kind) => write!(f, "{kind:?}"),
        }
    }
}

fn write_joined(
    f: &mut fmt::Formatter<'_>,
    open: &str,
    close: &str,
    values: &[Value],
) -> fmt::Result {
    f.write_str(open)?;
    for (index, value) in values.iter().enumerate() {
        if index > 0 {
            f.write_str(" ")?;
        }
        write!(f, "{value}")?;
    }
    f.write_str(close)
}
