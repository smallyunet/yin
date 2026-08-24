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
fn normative_type_errors_precede_phase_one_admission() {
    let program = parse("hir-invalid.yin", "(match (+ 1 true) [_ false])").unwrap();
    let error = check_hir_program(&program).unwrap_err();
    assert!(error.to_string().contains("numeric argument"), "{error}");
    assert!(!error.to_string().contains("outside HIR phase 1"));
}

#[test]
fn phase_one_rejects_unlowered_forms_after_normal_type_checking() {
    for (source, expected) in [
        (
            "(match 1 [1 true] [_ false])",
            "match is outside HIR phase 1",
        ),
        (
            "(define value (fun ([input Int :default 1]) input))\n(value)",
            "default parameters are outside HIR phase 1",
        ),
        (
            "(define [left right] [1 2])\nleft",
            "destructuring define is outside HIR phase 1",
        ),
        (
            "(record Box [value Int :default 1])\n(Box)",
            "record defaults are outside HIR phase 1",
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
            HirKind::Record { .. } => {}
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
