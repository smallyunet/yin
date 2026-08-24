use crate::contract::compile_bytecode_unchecked;
use crate::{
    Effect, EffectReport, ParsedProgram, SourceSpan, Type, YinError, check_mir_program,
    check_program, infer_effects, parse,
};
use num_bigint::BigInt;
use num_traits::ToPrimitive;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum ProfileStatus {
    Designed,
    Prototype,
    Experimental,
    Supported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub struct TargetProfile {
    pub name: &'static str,
    pub status: ProfileStatus,
    pub validator_available: bool,
    pub artifact_backend_available: bool,
    pub description: &'static str,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct TargetViolation {
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub effect: Option<Effect>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub operation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub span: Option<SourceSpan>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct TargetCheck {
    pub profile: TargetProfile,
    pub valid: bool,
    pub effects: EffectReport,
    pub violations: Vec<TargetViolation>,
}

const PROFILES: &[TargetProfile] = &[
    TargetProfile {
        name: "hosted-v1",
        status: ProfileStatus::Supported,
        validator_available: true,
        artifact_backend_available: true,
        description: "Rust hosted evaluator with explicitly supplied host capabilities",
    },
    TargetProfile {
        name: "portable-bytecode-v1",
        status: ProfileStatus::Experimental,
        validator_available: true,
        artifact_backend_available: true,
        description: "Narrow fuel-metered deterministic decision bytecode",
    },
    TargetProfile {
        name: "mir-pure-v1",
        status: ProfileStatus::Prototype,
        validator_available: true,
        artifact_backend_available: false,
        description: "Pure data-returning subset executable by the experimental MIR evaluator",
    },
    TargetProfile {
        name: "evm-contract-v1",
        status: ProfileStatus::Designed,
        validator_available: false,
        artifact_backend_available: false,
        description: "Planned EVM contract profile",
    },
    TargetProfile {
        name: "svm-program-v1",
        status: ProfileStatus::Designed,
        validator_available: false,
        artifact_backend_available: false,
        description: "Planned Solana sBPF program profile",
    },
    TargetProfile {
        name: "riscv64-v1",
        status: ProfileStatus::Designed,
        validator_available: false,
        artifact_backend_available: false,
        description: "Planned RISC-V runtime profile with ABI still to be selected",
    },
    TargetProfile {
        name: "bitcoin-tapscript-v1",
        status: ProfileStatus::Designed,
        validator_available: false,
        artifact_backend_available: false,
        description: "Planned Bitcoin Tapscript spending-condition profile",
    },
];

pub fn target_profiles() -> &'static [TargetProfile] {
    PROFILES
}

pub fn target_profile(name: &str) -> Option<&'static TargetProfile> {
    PROFILES.iter().find(|profile| profile.name == name)
}

pub fn check_target_source(
    file: &str,
    source: &str,
    profile_name: &str,
) -> Result<TargetCheck, YinError> {
    let profile = *target_profile(profile_name).ok_or_else(|| {
        YinError::language(format!(
            "unknown target profile {profile_name}; expected one of: {}",
            PROFILES
                .iter()
                .map(|profile| profile.name)
                .collect::<Vec<_>>()
                .join(", ")
        ))
    })?;
    let program = parse(file, source)?;
    check_program(&program)?;
    let effects = infer_effects(&program);
    let mut violations = Vec::new();

    if !profile.validator_available {
        violations.push(TargetViolation {
            message: format!(
                "{} is designed only; its validator and artifact backend are not implemented",
                profile.name
            ),
            effect: None,
            operation: None,
            span: None,
        });
    } else {
        match profile.name {
            "hosted-v1" => {}
            "mir-pure-v1" => {
                effect_violations(&effects, &[Effect::Allocation], &[], &mut violations);
                match check_mir_program(&program) {
                    Ok(mir) if matches!(mir.result_type, Type::Function(..) | Type::Tool(..)) => {
                        violations.push(TargetViolation {
                            message: "mir-pure-v1 requires a data-returning program".into(),
                            effect: None,
                            operation: None,
                            span: program
                                .expressions
                                .last()
                                .map(|expression| expression.span().clone()),
                        });
                    }
                    Ok(_) => {}
                    Err(error) => diagnostic_violation(error, &mut violations),
                }
            }
            "portable-bytecode-v1" => {
                effect_violations(
                    &effects,
                    &[Effect::Allocation, Effect::HostIo],
                    &["read-all"],
                    &mut violations,
                );
                portable_syntax_violations(&program, &mut violations);
                for function in &effects.functions {
                    if function.name != "<entry>" && !function.calls.is_empty() {
                        violations.push(TargetViolation {
                            message: "policy-to-policy calls are outside portable-bytecode-v1"
                                .into(),
                            effect: None,
                            operation: Some(function.calls.join(",")),
                            span: Some(function.span.clone()),
                        });
                    }
                }
                if let Err(error) = compile_bytecode_unchecked(file, source) {
                    diagnostic_violation(error, &mut violations);
                }
            }
            _ => unreachable!("validator availability and profile registry disagree"),
        }
    }

    deduplicate(&mut violations);
    Ok(TargetCheck {
        profile,
        valid: violations.is_empty(),
        effects,
        violations,
    })
}

fn effect_violations(
    report: &EffectReport,
    allowed: &[Effect],
    allowed_host_operations: &[&str],
    violations: &mut Vec<TargetViolation>,
) {
    for origin in &report.origins {
        let allowed_effect = allowed.contains(&origin.effect);
        let allowed_operation = origin.effect != Effect::HostIo
            || allowed_host_operations.contains(&origin.operation.as_str());
        if allowed_effect && allowed_operation {
            continue;
        }
        violations.push(TargetViolation {
            message: format!(
                "effect {:?} from {} is not admitted by this target profile",
                origin.effect, origin.operation
            ),
            effect: Some(origin.effect),
            operation: Some(origin.operation.clone()),
            span: Some(origin.span.clone()),
        });
    }
}

fn portable_syntax_violations(program: &ParsedProgram, violations: &mut Vec<TargetViolation>) {
    for expression in &program.expressions {
        portable_expression(expression, violations);
    }
}

fn portable_expression(expression: &crate::Expr, violations: &mut Vec<TargetViolation>) {
    match expression {
        crate::Expr::String(_, _) => {}
        crate::Expr::Atom(value, span) => {
            let forbidden = match value.as_str() {
                "Float" => Some("Float is outside portable-bytecode-v1"),
                "Any" => Some("Any is outside portable-bytecode-v1"),
                "args" => Some("args is outside portable-bytecode-v1"),
                "parse-float" => Some("parse-float is outside portable-bytecode-v1"),
                "Dict" | "Set" => Some("Dict and Set are outside portable-bytecode-v1"),
                _ if value.starts_with("dict/") || value.starts_with("set/") => {
                    Some("Dict and Set operations are outside portable-bytecode-v1")
                }
                "dict" | "set" => Some("Dict and Set are outside portable-bytecode-v1"),
                _ if float_literal(value) => {
                    Some("float literals are outside portable-bytecode-v1")
                }
                _ => None,
            };
            if let Some(message) = forbidden {
                violations.push(TargetViolation {
                    message: message.into(),
                    effect: None,
                    operation: Some(value.clone()),
                    span: Some(span.clone()),
                });
            }
            if let Some(integer) = parse_integer(value) {
                if integer.to_i64().is_some() {
                    return;
                }
                violations.push(TargetViolation {
                    message: "integer literal is outside portable-bytecode-v1 signed 64-bit range"
                        .into(),
                    effect: None,
                    operation: Some(value.clone()),
                    span: Some(span.clone()),
                });
            }
        }
        crate::Expr::Form(_, values, _) => {
            for value in values {
                portable_expression(value, violations);
            }
        }
    }
}

fn diagnostic_violation(error: YinError, violations: &mut Vec<TargetViolation>) {
    let diagnostic = error.diagnostic();
    violations.push(TargetViolation {
        message: diagnostic.message.clone(),
        effect: None,
        operation: None,
        span: diagnostic.span.clone(),
    });
}

fn deduplicate(violations: &mut Vec<TargetViolation>) {
    let mut unique = Vec::new();
    for violation in violations.drain(..) {
        if !unique.iter().any(|existing: &TargetViolation| {
            existing.message == violation.message && existing.span == violation.span
        }) {
            unique.push(violation);
        }
    }
    *violations = unique;
}

fn float_literal(value: &str) -> bool {
    value.contains('.') && value.parse::<f64>().is_ok()
}

fn parse_integer(value: &str) -> Option<BigInt> {
    if let Some(value) = value.strip_prefix("0x") {
        BigInt::parse_bytes(value.as_bytes(), 16)
    } else if let Some(value) = value.strip_prefix("0b") {
        BigInt::parse_bytes(value.as_bytes(), 2)
    } else {
        value.parse().ok()
    }
}
