use std::io::Write;
use std::process::Command;
use yin::{HirKind, HirSymbolKind, Type, check_hir_program, parse, render_hir};

fn lower(source: &str) -> yin::CheckedProgram {
    let program = parse("hir-test.yin", source).unwrap();
    check_hir_program(&program).unwrap()
}

#[test]
fn resolved_typed_hir_has_a_stable_rendering() {
    let checked = lower(
        "(define choose\n  (fun ([flag Bool] [left Int] [right Int] [-> Int])\n    (if flag left right)))\n(define answer (choose true (+ 40 2) 0))\nanswer",
    );

    assert_eq!(checked.result_type(), &Type::Int);
    assert_eq!(
        render_hir(&checked.hir),
        r#"program -> Int
symbols
  %0 choose Binding
  %1 answer Binding
  %2 flag Parameter
  %3 left Parameter
  %4 right Parameter
  %5 + Builtin
body
  define %0 -> Function([Bool, Int, Int], 3, Int)
    function -> Function([Bool, Int, Int], 3, Int)
      parameter %2 Bool required=true
      parameter %3 Int required=true
      parameter %4 Int required=true
      if -> Int
        reference %2 -> Bool
        reference %3 -> Int
        reference %4 -> Int
  define %1 -> Int
    call -> Int
      reference %0 -> Function([Bool, Int, Int], 3, Int)
      argument
        literal Bool(true) -> Bool
      argument
        call -> Int
          reference %5 -> Function([Any, Any], 2, Any)
          argument
            literal Int("40") -> Int
          argument
            literal Int("2") -> Int
      argument
        literal Int("0") -> Int
  reference %1 -> Int
"#
    );
}

#[test]
fn bindings_and_parameters_have_distinct_symbol_ids() {
    let checked = lower(
        "(define value 41)\n(define increment (fun ([value Int] [-> Int]) (+ value 1)))\n(increment value)",
    );
    let value_symbols = checked
        .hir
        .symbols
        .iter()
        .filter(|symbol| symbol.name == "value")
        .collect::<Vec<_>>();
    assert_eq!(value_symbols.len(), 2);
    assert_eq!(value_symbols[0].kind, HirSymbolKind::Binding);
    assert_eq!(value_symbols[1].kind, HirSymbolKind::Parameter);
    assert_ne!(value_symbols[0].id, value_symbols[1].id);
}

#[test]
fn phase_one_lowers_the_maintained_pure_core_slice() {
    for source in [
        "42",
        "3.5",
        "true",
        "\"yin\"",
        "[1 true \"three\"]",
        "(+ 40 2)",
        "(if true 1 2)",
        "(seq (define left 20) (define right 22) (+ left right))",
        "(define identity (fun ([value Int] [-> Int]) value))\n(identity 42)",
        "(define choose (fun ([flag Bool] [-> Int]) (if flag 1 2)))\n(choose true)",
        "(define apply (fun ([callback (Fn [Int] Int)] [-> Int]) (callback 42)))\n(apply (fun ([value Int] [-> Int]) value))",
        "(define pair [40 2])\n(at pair 1)",
        "(record Box [value Int])\n(field (Box :value 42) :value)",
        "(record Parent [value Int])\n(record Child (Parent) [name String])\n(field (Child :value 42 :name \"yin\") :value)",
    ] {
        let checked = lower(source);
        assert!(!checked.hir.expressions.is_empty(), "{source}");
        assert_all_typed(&checked.hir.expressions);
    }
}

#[test]
fn records_keep_nominal_and_field_types() {
    let checked = lower(
        "(record Parent [value Int])\n(record Child (Parent) [name String])\n(field (Child :value 42 :name \"yin\") :value)",
    );
    let records = checked
        .hir
        .expressions
        .iter()
        .filter_map(|expression| match &expression.kind {
            HirKind::Record {
                symbol,
                parents,
                fields,
            } => Some((*symbol, parents, fields)),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(records.len(), 2);
    assert_eq!(records[1].1, &[records[0].0]);
    assert_eq!(records[1].2.len(), 1);
    assert_eq!(records[1].2[0].name, "name");
    assert_eq!(records[1].2[0].ty, Type::String);
    assert_eq!(checked.result_type(), &Type::Int);
}

#[test]
fn variants_and_exhaustive_match_lower_to_typed_patterns() {
    let checked = lower(
        "(variant Decision [Approve [value Int]] [Reject [reason String]])\n(define decide (fun ([allow Bool] [-> Decision]) (if allow (Approve :value 42) (Reject :reason \"blocked\"))))\n(match (decide true) [(Approve value) value] [(Reject reason) 0])",
    );
    let HirKind::Variant { cases, .. } = &checked.hir.expressions[0].kind else {
        panic!("expected variant HIR");
    };
    assert_eq!(cases.len(), 2);
    assert_eq!(cases[0].name, "Approve");
    assert_eq!(cases[0].fields[0].ty, Type::Int);

    let HirKind::Match { arms, .. } = &checked.hir.expressions[2].kind else {
        panic!("expected match HIR");
    };
    assert_eq!(arms.len(), 2);
    let yin::HirPatternKind::Constructor {
        constructor: yin::HirPatternConstructor::VariantCase(approve),
        payloads,
    } = &arms[0].pattern.kind
    else {
        panic!("expected variant-case pattern");
    };
    assert_eq!(*approve, cases[0].symbol);
    assert_eq!(payloads[0].ty, Type::Int);
    assert!(matches!(payloads[0].kind, yin::HirPatternKind::Binding(_)));
    assert_eq!(checked.result_type(), &Type::Int);
}

#[test]
fn option_and_result_patterns_preserve_payload_types_and_scopes() {
    let checked = lower(
        "(define result-value (fun ([ok-value Bool] [-> (Result Int String)]) (if ok-value (ok 42) (err \"failed\"))))\n(define option-value (fun ([present Bool] [-> (Option Int)]) (if present (some 7) none)))\n(define from-result (match (result-value true) [(Ok value) value] [(Err value) 0]))\n(match (option-value true) [(Some value) (+ from-result value)] [(None) from-result])",
    );
    let matches = checked
        .hir
        .expressions
        .iter()
        .filter_map(|expression| match &expression.kind {
            HirKind::Define { value, .. } => match &value.kind {
                HirKind::Match { arms, .. } => Some(arms),
                _ => None,
            },
            HirKind::Match { arms, .. } => Some(arms),
            _ => None,
        })
        .collect::<Vec<_>>();
    assert_eq!(matches.len(), 2);
    assert_eq!(matches[0][0].pattern.ty, Type::Ok(Box::new(Type::Int)));
    assert_eq!(matches[0][1].pattern.ty, Type::Err(Box::new(Type::String)));
    assert_eq!(matches[1][0].pattern.ty, Type::Some(Box::new(Type::Int)));
    assert_eq!(matches[1][1].pattern.ty, Type::None);

    let binding_symbols = checked
        .hir
        .symbols
        .iter()
        .filter(|symbol| symbol.kind == HirSymbolKind::PatternBinding)
        .collect::<Vec<_>>();
    assert_eq!(binding_symbols.len(), 3);
    assert!(
        binding_symbols
            .windows(2)
            .all(|pair| pair[0].id != pair[1].id)
    );
    assert_eq!(checked.result_type(), &Type::Int);
}

#[test]
fn vector_and_primitive_patterns_are_fully_typed() {
    let vector = lower("(match [42 \"yin\"] [[number name] name])");
    let HirKind::Match { arms, .. } = &vector.hir.expressions[0].kind else {
        panic!("expected vector match");
    };
    let yin::HirPatternKind::Vector(patterns) = &arms[0].pattern.kind else {
        panic!("expected vector pattern");
    };
    assert_eq!(patterns[0].ty, Type::Int);
    assert_eq!(patterns[1].ty, Type::String);

    let primitive = lower(
        "(define describe (fun ([value (U Int String)] [-> String]) (match value [(Int number) \"integer\"] [(String text) text])))\n(describe \"yin\")",
    );
    let HirKind::Define { value, .. } = &primitive.hir.expressions[0].kind else {
        panic!("expected function definition");
    };
    let HirKind::Function { body, .. } = &value.kind else {
        panic!("expected function");
    };
    let HirKind::Match { arms, .. } = &body[0].kind else {
        panic!("expected primitive match");
    };
    assert_eq!(arms[0].pattern.ty, Type::Int);
    assert_eq!(arms[1].pattern.ty, Type::String);
}

#[test]
fn maintained_capability_decision_program_emits_structured_hir() {
    let path = "examples/agents/capability-decision/main.yin";
    let source = std::fs::read_to_string(path).unwrap();
    let program = parse(path, &source).unwrap();
    let checked = check_hir_program(&program).unwrap();
    let rendered = render_hir(&checked.hir);

    assert!(rendered.contains("variant"));
    assert!(rendered.contains("constructor VariantCase"));
    assert!(rendered.contains("decode-json"));
    assert!(rendered.contains("encode-json"));
    assert!(rendered.contains("pattern constructor Ok"));
    assert!(rendered.contains("pattern constructor Err"));
    assert!(rendered.contains("authorize-swap Binding"));
    assert!(rendered.contains(".amount -> Int"));
    assert!(rendered.contains(".simulationSucceeded -> Bool"));
    assert!(rendered.contains(".code -> String"));
}

#[test]
fn normative_type_errors_precede_hir_admission() {
    let program = parse("hir-invalid.yin", "(match (+ 1 true) [_ false])").unwrap();
    let error = check_hir_program(&program).unwrap_err();
    assert!(error.to_string().contains("numeric argument"), "{error}");
    assert!(!error.to_string().contains("outside HIR phase 1"));
}

#[test]
fn normative_match_rejections_precede_hir_lowering() {
    for (source, expected) in [
        (
            "(variant Decision [Allow] [Deny])\n(define choose (fun ([-> Decision]) (Allow)))\n(match (choose) [(Allow) true])",
            "non-exhaustive match",
        ),
        ("(match [1 2] [[value value] value])", "duplicate binding"),
        (
            "(match (some 1) [(Some left right) left] [(None) 0])",
            "Some pattern expects exactly 1 payloads",
        ),
    ] {
        let program = parse("hir-match-reject.yin", source).unwrap();
        let error = check_hir_program(&program).unwrap_err();
        assert!(error.to_string().contains(expected), "{error}");
    }
}

#[test]
fn phase_two_rejects_unlowered_forms_after_normal_type_checking() {
    for (source, expected) in [
        (
            "(define value (fun ([input Int :default 1]) input))\n(value)",
            "default parameters are outside HIR phase 2",
        ),
        (
            "(define [left right] [1 2])\nleft",
            "destructuring define is outside HIR phase 2",
        ),
        (
            "(record Box [value Int :default 1])\n(Box)",
            "record defaults are outside HIR phase 2",
        ),
    ] {
        let program = parse("hir-reject.yin", source).unwrap();
        let error = check_hir_program(&program).unwrap_err();
        assert!(error.to_string().contains(expected), "{error}");
        assert!(error.diagnostic().span.is_some());
    }
}

#[test]
fn cli_emits_the_experimental_hir() {
    let mut source = tempfile::NamedTempFile::new().unwrap();
    writeln!(source, "(define answer (+ 40 2))\nanswer").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("--emit-hir")
        .arg(source.path())
        .output()
        .unwrap();
    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(stdout.starts_with("program -> Int\nsymbols\n"));
    assert!(stdout.contains("answer Binding"));
    assert!(stdout.contains("+ Builtin"));
}

fn assert_all_typed(expressions: &[yin::HirExpr]) {
    for expression in expressions {
        match &expression.kind {
            HirKind::Vector(values) | HirKind::Sequence(values) => assert_all_typed(values),
            HirKind::Define { value, .. } => assert_all_typed(std::slice::from_ref(value)),
            HirKind::Function { body, .. } => assert_all_typed(body),
            HirKind::Record { .. } | HirKind::Variant { .. } => {}
            HirKind::Constructor { arguments, .. } => {
                for argument in arguments {
                    assert_all_typed(std::slice::from_ref(&argument.value));
                }
            }
            HirKind::Match { target, arms } => {
                assert_all_typed(std::slice::from_ref(target));
                for arm in arms {
                    assert_all_typed(std::slice::from_ref(&arm.body));
                }
            }
            HirKind::DecodeJson { input, .. } => {
                assert_all_typed(std::slice::from_ref(input));
            }
            HirKind::EncodeJson(value) => assert_all_typed(std::slice::from_ref(value)),
            HirKind::Call { callee, arguments } => {
                assert_all_typed(std::slice::from_ref(callee));
                for argument in arguments {
                    assert_all_typed(std::slice::from_ref(&argument.value));
                }
            }
            HirKind::If {
                condition,
                then_branch,
                else_branch,
            } => {
                assert_all_typed(std::slice::from_ref(condition));
                assert_all_typed(std::slice::from_ref(then_branch));
                assert_all_typed(std::slice::from_ref(else_branch));
            }
            HirKind::Field { target, .. } => assert_all_typed(std::slice::from_ref(target)),
            HirKind::Literal(_) | HirKind::Reference(_) | HirKind::FieldPath { .. } => {}
        }
    }
}
