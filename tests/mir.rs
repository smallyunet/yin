use std::io::Write;
use std::process::Command;
use yin::{
    Engine, Host, MirInstructionKind, MirTerminator, check_mir_program, evaluate_mir, parse,
};

fn mir(source: &str) -> yin::MirProgram {
    let program = parse("mir-test.yin", source).unwrap();
    check_mir_program(&program).unwrap()
}

fn differential(source: &str) -> yin::Value {
    let expected = Engine::new(Host::default())
        .run_source("mir-reference.yin", source)
        .unwrap()
        .value;
    let actual = evaluate_mir(&mir(source)).unwrap();
    assert_eq!(actual, expected);
    actual
}

#[test]
fn conditionals_lower_to_explicit_blocks_and_block_arguments() {
    let program = mir("(define choose (fun ([flag Bool] [-> Int]) (if flag 7 9)))\n(choose true)");
    let function = &program.functions[1];
    assert_eq!(function.blocks.len(), 4);
    assert!(matches!(
        function.blocks[0].terminator,
        MirTerminator::Branch { .. }
    ));
    assert_eq!(function.blocks[3].parameters.len(), 1);
    assert!(matches!(
        function.blocks[1].terminator,
        MirTerminator::Jump { .. }
    ));
    assert_eq!(
        differential("(if (> 4 2) (+ 40 2) 0)"),
        yin::Value::Int(42.into())
    );
}

#[test]
fn closures_and_recursion_match_the_reference_evaluator() {
    let closure = "(define make-adder (fun ([base Int]) (fun ([value Int] [-> Int]) (+ base value))))\n(define add-two (make-adder 2))\n(add-two 40)";
    assert_eq!(differential(closure), yin::Value::Int(42.into()));

    let recursion = "(define factorial (fun ([value Int] [-> Int]) (if (= value 0) 1 (* value (factorial (- value 1))))))\n(factorial 6)";
    assert_eq!(differential(recursion), yin::Value::Int(720.into()));

    let mutual = "(define even (fun ([value Int] [-> Bool]) (if (= value 0) true (odd (- value 1)))))\n(define odd (fun ([value Int] [-> Bool]) (if (= value 0) false (even (- value 1)))))\n[(even 10) (odd 11)]";
    assert_eq!(
        differential(mutual),
        yin::Value::Vector(vec![yin::Value::Bool(true), yin::Value::Bool(true)])
    );
}

#[test]
fn vectors_records_variants_and_matches_execute_through_mir() {
    let vectors = "(define values (append [40] [2]))\n(+ (at values 0) (at values 1))";
    assert_eq!(differential(vectors), yin::Value::Int(42.into()));

    let records = "(record Box [value Int])\n(field (Box :value 42) :value)";
    assert_eq!(differential(records), yin::Value::Int(42.into()));

    let inherited = "(record Parent [value Int])\n(record Child (Parent) [name String])\n(field (Child :value 42 :name \"yin\") :value)";
    assert_eq!(differential(inherited), yin::Value::Int(42.into()));

    let variants = "(variant Decision [Allow [value Int]] [Deny [reason String]])\n(match (Allow :value 41) [(Allow value) (+ value 1)] [(Deny reason) 0])";
    assert_eq!(differential(variants), yin::Value::Int(42.into()));

    let second_variant = "(variant Decision [Allow [value Int]] [Deny [reason String]])\n(match (Deny :reason \"blocked\") [(Allow value) value] [(Deny reason) 42])";
    assert_eq!(differential(second_variant), yin::Value::Int(42.into()));
}

#[test]
fn option_and_result_matches_execute_through_mir() {
    let source = "(define inspect (fun ([value (Result Int String)] [-> Int]) (match value [(Ok number) (+ number 1)] [(Err reason) 0])))\n(inspect (ok 41))";
    assert_eq!(differential(source), yin::Value::Int(42.into()));

    let option = "(match (some 42) [(Some value) value] [(None) 0])";
    assert_eq!(differential(option), yin::Value::Int(42.into()));
}

#[test]
fn rendering_is_deterministic_and_exposes_control_flow() {
    let program = mir("(if true 1 2)");
    let first = yin::render_mir(&program);
    let second = yin::render_mir(&program);
    assert_eq!(first, second);
    assert!(first.contains("branch"));
    assert!(first.contains("jump bb3"));
    assert!(first.contains("bb3(_"));
}

#[test]
fn unsupported_effect_boundaries_fail_closed_after_hir_checking() {
    let json = parse("mir-json.yin", "(encode-json 42)").unwrap();
    let error = check_mir_program(&json).unwrap_err();
    assert!(
        error
            .to_string()
            .contains("JSON boundaries are outside MIR phase 1"),
        "{error}"
    );

    let higher_order = parse(
        "mir-map.yin",
        "(map [1 2] (fun ([value Int] [-> Int]) (+ value 1)))",
    )
    .unwrap();
    let error = check_mir_program(&higher_order).unwrap_err();
    assert!(
        error
            .to_string()
            .contains("builtin map is outside MIR phase 1"),
        "{error}"
    );
}

#[test]
fn every_instruction_carries_a_type_and_source_span() {
    let program = mir("(define value (+ 40 2))\nvalue");
    for function in &program.functions {
        for block in &function.blocks {
            for instruction in &block.instructions {
                assert!(!instruction.span.file.is_empty());
                assert!(!matches!(instruction.kind, MirInstructionKind::Void));
            }
        }
    }
}

#[test]
fn cli_can_emit_and_execute_mir() {
    let mut source = tempfile::NamedTempFile::new().unwrap();
    writeln!(source, "(if true (+ 40 2) 0)").unwrap();

    let emitted = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("--emit-mir")
        .arg(source.path())
        .output()
        .unwrap();
    assert!(emitted.status.success());
    let stdout = String::from_utf8(emitted.stdout).unwrap();
    assert!(stdout.contains("mir program entry=@0 -> Int"));
    assert!(stdout.contains("branch"));

    let executed = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("--run-mir")
        .arg(source.path())
        .output()
        .unwrap();
    assert!(executed.status.success());
    assert_eq!(String::from_utf8(executed.stdout).unwrap(), "42\n");
}
