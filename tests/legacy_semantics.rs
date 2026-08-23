use yin::{Engine, ErrorCode, Host, Type, check_program, parse};

fn evaluate(source: &str) -> Result<(String, Vec<String>), yin::YinError> {
    Engine::new(Host::default())
        .run_source("legacy-v019.yin", source)
        .map(|result| (result.value.to_string(), result.output))
}

fn value(source: &str) -> String {
    evaluate(source).unwrap().0
}

fn type_of(source: &str) -> Result<Type, yin::YinError> {
    check_program(&parse("legacy-v019.yin", source)?)
}

fn error(source: &str) -> String {
    evaluate(source).unwrap_err().to_string()
}

fn assert_error(source: &str, fragments: &[&str]) {
    let actual = error(source);
    assert!(
        fragments.iter().any(|fragment| actual.contains(fragment)),
        "expected one of {fragments:?}, got: {actual}"
    );
}

#[test]
fn maintained_program_values_match_v019() {
    for (path, expected) in [
        ("tests/array.yin", "[1 2 3 4 5]"),
        ("tests/empty-vector.yin", "[]"),
        ("tests/arithmetic.yin", "5"),
        ("tests/function1.yin", "-1"),
        ("tests/recursion-direct.yin", "120"),
        ("tests/recursion-mutual.yin", "void"),
        ("tests/record-field-access.yin", "42"),
        ("tests/vector-operations.yin", "44"),
        ("tests/program-usability.yin", "[30 \"42\"]"),
    ] {
        let source = std::fs::read_to_string(path).unwrap();
        assert_eq!(value(&source), expected, "{path}");
    }
}

#[test]
fn print_accepts_multiple_arguments_and_quotes_strings() {
    let source = std::fs::read_to_string("tests/expr.yin").unwrap();
    let (result, output) = evaluate(&source).unwrap();
    assert_eq!(result, "void");
    assert!(output.iter().any(|line| line.contains("\"世界\"")));
    assert!(output.iter().any(|line| line.contains("42, \"ok\", true")));
}

#[test]
fn command_arguments_are_injected() {
    let source = std::fs::read_to_string("examples/cli/parse-values.yin").unwrap();
    let result = Engine::new(Host::standard(
        "",
        vec!["10".into(), "bad".into(), "32".into()],
    ))
    .run_source("parse-values.yin", &source)
    .unwrap();
    assert_eq!(result.value.to_string(), "42");
}

#[test]
fn keyword_arguments_are_evaluated_in_the_caller_scope() {
    assert_eq!(
        value(
            "(define identity (fun ([x Int] [-> Int]) x))\n(define call-identity (fun ([value Int] [-> Int]) (identity :x value)))\n(call-identity 7)"
        ),
        "7"
    );
}

#[test]
fn record_keywords_and_defaults_are_initialized() {
    assert_eq!(
        value("(record Point [x Int] [y Int :default 2])\n(Point :x 1)"),
        "(record Point [x 1] [y 2])"
    );
}

#[test]
fn wrong_function_arity_is_a_language_error() {
    assert_error(
        "(define identity (fun (x) x))\n(identity 1 2)",
        &["argument", "arity", "expects"],
    );
}

#[test]
fn empty_program_is_void() {
    assert_eq!(value(""), "void");
}

#[test]
fn non_boolean_condition_is_rejected() {
    assert_error("(if 1 2 3)", &["Bool", "boolean"]);
}

#[test]
fn integer_division_by_zero_is_rejected() {
    assert_error("(/ 1 0)", &["division by zero"]);
}

#[test]
fn unbound_names_are_rejected() {
    assert_error("missing", &["unbound name", "unbound variable"]);
}

#[test]
fn same_scope_redefinition_is_rejected() {
    assert_error(
        "(define answer 1)\n(define answer 2)",
        &["duplicate definition", "redefine"],
    );
}

#[test]
fn assignment_updates_nearest_lexical_binding() {
    assert_eq!(
        value(
            "(define counter 1)\n(define increment (fun () (set! counter (+ counter 1))))\n(increment)\ncounter"
        ),
        "2"
    );
}

#[test]
fn assignment_to_undefined_name_is_rejected() {
    assert_error("(set! missing 1)", &["unbound name", "not defined"]);
}

#[test]
fn assignment_cannot_change_static_type() {
    assert_error(
        "(define answer 1)\n(set! answer true)",
        &["assignment", "expected Int"],
    );
}

#[test]
fn closures_capture_lexical_environment() {
    assert_eq!(
        value(
            "(define make-adder (fun (x) (fun (y) (+ x y))))\n(define add-two (make-adder 2))\n(add-two 5)"
        ),
        "7"
    );
}

#[test]
fn inner_definitions_shadow_outer_bindings() {
    assert_eq!(
        value("(define x 1)\n(define local (fun () (define x 2) x))\n(local)\nx"),
        "1"
    );
}

#[test]
fn function_defaults_are_definition_time_snapshots() {
    assert_eq!(
        value(
            "(define initial 1)\n(define choose (fun ([value Int :default initial] [-> Int]) value))\n(set! initial 2)\n(choose)"
        ),
        "1"
    );
}

#[test]
fn missing_and_extra_function_keywords_are_rejected() {
    assert_error(
        "(define pair (fun (left right) left))\n(pair :left 1)",
        &["right", "argument"],
    );
    assert_error(
        "(define identity (fun (value) value))\n(identity :value 1 :other 2)",
        &["other", "argument"],
    );
}

#[test]
fn duplicate_and_mixed_function_arguments_are_rejected() {
    assert_error(
        "(define identity (fun (value) value))\n(identity :value 1 :value 2)",
        &["duplicate", "value"],
    );
    assert_error(
        "(define pair (fun (left right) left))\n(pair 1 :right 2)",
        &["positional", "keyword", "argument"],
    );
}

#[test]
fn duplicate_descriptor_properties_are_rejected() {
    assert_error(
        "(record Point [x Int :default 1 :default 2])",
        &["duplicate", "default"],
    );
}

#[test]
fn record_inheritance_includes_parent_fields() {
    assert_eq!(
        value(
            "(record Position [x Int :default 1])\n(record NamedPosition (Position) [name String])\n(NamedPosition :name \"origin\")"
        ),
        "(record NamedPosition [name \"origin\"] [x 1])"
    );
}

#[test]
fn inherited_field_conflicts_are_rejected() {
    assert_error(
        "(record Parent [value Int])\n(record Child (Parent) [value Int])",
        &["conflict", "value", "duplicate"],
    );
}

#[test]
fn non_record_parents_are_rejected() {
    assert_error(
        "(define NotARecord 1)\n(record Child (NotARecord))",
        &["parent", "record"],
    );
}

#[test]
fn record_defaults_are_definition_time_snapshots() {
    assert_eq!(
        value(
            "(define initial 1)\n(record Box [value Int :default initial])\n(set! initial 2)\n(Box)"
        ),
        "(record Box [value 1])"
    );
}

#[test]
fn vector_destructuring_works_for_definition_and_assignment() {
    assert_eq!(value("(define [left right] [1 2])\n(+ left right)"), "3");
    assert_eq!(
        value("(define left 0)\n(define right 0)\n(set! [left right] [3 4])\n(+ left right)"),
        "7"
    );
}

#[test]
fn destructuring_size_mismatch_is_rejected() {
    assert_error("(define [left right] [1])", &["size", "destructur"]);
}

#[test]
fn conditional_type_is_a_union() {
    assert_eq!(
        type_of("(if true 1 \"one\")").unwrap(),
        Type::Union(vec![Type::Int, Type::String])
    );
}

#[test]
fn boolean_primitives_require_booleans() {
    assert_eq!(value("(and true (not false))"), "true");
    assert_error("(or true 1)", &["Bool", "boolean"]);
}

#[test]
fn primitive_arity_is_checked() {
    assert_error("(+ 1)", &["expects 2", "arity", "arguments"]);
}

#[test]
fn parser_diagnostics_have_code_and_span() {
    let actual = parse("legacy-v019.yin", "\n(+ 1 2").unwrap_err();
    assert_eq!(actual.diagnostic().code, ErrorCode::Syntax);
    let span = actual.diagnostic().span.as_ref().unwrap();
    assert_eq!((span.line, span.column), (2, 1));
}

#[test]
fn runaway_strings_and_malformed_numbers_are_rejected() {
    assert_error("\"unterminated", &["unterminated string", "runaway string"]);
    assert_error("12abc", &["number", "invalid token"]);
}

#[test]
fn float_literals_and_mixed_arithmetic_typecheck() {
    assert_eq!(type_of("(+ 1.5 2)").unwrap(), Type::Float);
}

#[test]
fn non_numeric_arithmetic_is_rejected() {
    assert_error("(+ 1.5 true)", &["numeric", "Float", "argument"]);
}

#[test]
fn keyword_calls_keep_declared_result_type() {
    assert_eq!(
        type_of("(define identity (fun ([x Int] [-> Int]) x))\n(define call-identity (fun ([value Int] [-> Int]) (identity :x value)))\n(call-identity 7)").unwrap(),
        Type::Int
    );
}
