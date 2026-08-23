use yin::{Engine, Host, Type, check_program, parse};

fn run(source: &str) -> Result<String, yin::YinError> {
    Engine::new(Host::default())
        .run_source("legacy-spec.yin", source)
        .map(|result| result.value.to_string())
}
fn value(source: &str) -> String {
    run(source).unwrap()
}
fn type_of(source: &str) -> Result<Type, yin::YinError> {
    check_program(&parse("legacy-spec.yin", source)?)
}
fn assert_error(source: &str, fragments: &[&str]) {
    let actual = run(source).unwrap_err().to_string();
    assert!(
        fragments.iter().any(|fragment| actual.contains(fragment)),
        "expected one of {fragments:?}, got: {actual}"
    );
}

#[test]
fn positional_arguments_evaluate_left_to_right() {
    assert_eq!(
        value(
            "(define counter 0)\n(define next (fun () (set! counter (+ counter 1)) counter))\n(define encode (fun (left right) (+ (* left 10) right)))\n(encode (next) (next))"
        ),
        "12"
    );
}

#[test]
fn keyword_arguments_evaluate_in_source_order() {
    assert_eq!(
        value(
            "(define counter 0)\n(define next (fun () (set! counter (+ counter 1)) counter))\n(define encode (fun (left right) (+ (* left 10) right)))\n(encode :right (next) :left (next))"
        ),
        "21"
    );
}

#[test]
fn record_arguments_evaluate_in_source_order_but_store_in_declaration_order() {
    assert_eq!(
        value(
            "(define counter 0)\n(define next (fun () (set! counter (+ counter 1)) counter))\n(record Pair [left Int] [right Int])\n(Pair :right (next) :left (next))"
        ),
        "(record Pair [left 2] [right 1])"
    );
}

#[test]
fn call_operator_evaluates_before_arguments() {
    assert_eq!(
        value(
            "(define state 0)\n(define make-operator (fun () (set! state 1) (fun (value) (+ (* state 10) value))))\n(define argument (fun () (set! state 2) 3))\n((make-operator) (argument))"
        ),
        "23"
    );
}

#[test]
fn conditionals_only_evaluate_selected_branch() {
    assert_eq!(value("(if true 1 (/ 1 0))"), "1");
}

#[test]
fn required_keyword_arguments_cannot_be_omitted() {
    assert_error(
        "(define identity (fun ([value Int] [-> Int]) value))\n(identity)",
        &["expected 1", "missing argument", "value"],
    );
}

#[test]
fn any_is_the_top_type_at_return_positions() {
    assert_eq!(
        type_of("(define widen (fun ([value Int] [-> Any]) value))\n(widen 1)").unwrap(),
        Type::Any
    );
}

#[test]
fn union_annotations_accept_only_members() {
    assert_eq!(
        type_of("(define numeric (fun ([value (U Int Float)]) value))\n(numeric 1)").unwrap(),
        Type::Union(vec![Type::Int, Type::Float])
    );
    assert_error(
        "(define numeric (fun ([value (U Int Float)]) value))\n(numeric true)",
        &["Union", "Bool", "call argument"],
    );
}

#[test]
fn empty_unions_are_rejected() {
    assert_error(
        "(define impossible (fun ([value (U)]) value))\nimpossible",
        &["union type", "member", "empty"],
    );
}

#[test]
fn computed_type_expressions_are_rejected() {
    assert_error(
        "(define invalid (fun ([value (+ 1 2)]) value))",
        &["unsupported type", "invalid type"],
    );
}

#[test]
fn standalone_declare_is_rejected() {
    assert_error("(declare [value Int])", &["declare", "unbound"]);
}

#[test]
fn unknown_descriptor_properties_are_rejected() {
    assert_error(
        "(define invalid (fun ([value Int :mutable true]) value))",
        &["mutable", "descriptor"],
    );
}

#[test]
fn return_descriptor_must_be_last() {
    assert_error(
        "(define invalid (fun ([-> Int] [value Int]) value))",
        &["return descriptor", "last"],
    );
}

#[test]
fn exact_vector_types_are_structural() {
    assert_eq!(
        type_of("(define pair [1 2])\n(set! pair [3 4])\npair").unwrap(),
        Type::ExactVector(vec![Type::Int, Type::Int])
    );
    assert_error(
        "(define pair [1 2])\n(set! pair [3 true])",
        &["assignment", "Bool"],
    );
}

#[test]
fn append_preserves_exact_immutable_vector_type() {
    let source = "(define left [1 2])\n(define combined (append left [\"three\" true]))\ncombined";
    assert_eq!(value(source), "[1 2 \"three\" true]");
    assert_eq!(
        type_of(source).unwrap(),
        Type::ExactVector(vec![Type::Int, Type::Int, Type::String, Type::Bool])
    );
}

#[test]
fn literal_vector_indices_have_precise_types() {
    assert_eq!(value("(at [1 \"two\" true] 1)"), "\"two\"");
    assert_eq!(type_of("(at [1 \"two\" true] 1)").unwrap(), Type::String);
}

#[test]
fn dynamic_vector_indices_produce_normalized_unions() {
    let source = "(define select (fun ([items Any] [index Int]) (at [1 \"two\" 3] index)))\n(select [false] 1)";
    assert_eq!(value(source), "\"two\"");
    assert_eq!(
        type_of(source).unwrap(),
        Type::Union(vec![Type::Int, Type::String])
    );
}

#[test]
fn vector_operations_distribute_across_unions() {
    let access =
        "(define choose (fun ([flag Bool]) (if flag [1] [\"one\" true])))\n(at (choose true) 0)";
    assert_eq!(value(access), "1");
    assert_eq!(
        type_of(access).unwrap(),
        Type::Union(vec![Type::Int, Type::String])
    );
}

#[test]
fn vector_operations_use_any_as_runtime_boundary() {
    let valid =
        "(define read (fun ([items Any] [index Any] [-> Any]) (at items index)))\n(read [40 42] 1)";
    assert_eq!(value(valid), "42");
    assert_eq!(type_of(valid).unwrap(), Type::Any);
    assert_error(
        "(define read (fun ([items Any] [index Any] [-> Any]) (at items index)))\n(read 42 0)",
        &["vector", "Vector"],
    );
    assert_error(
        "(define read (fun ([items Any] [index Any] [-> Any]) (at items index)))\n(read [42] \"zero\")",
        &["Int", "index"],
    );
}

#[test]
fn invalid_vector_indices_are_rejected() {
    assert_error("(at [1 2] 2)", &["out of bounds"]);
    assert_error("(at [1 2] -1)", &["out of bounds", "non-negative"]);
    assert_error(
        "(define read (fun ([index Int]) (at [] index)))\n(read 0)",
        &["empty vector", "index"],
    );
}

#[test]
fn vector_operations_reject_invalid_operands() {
    assert_error("(length 42)", &["vector"]);
    assert_error("(at [1] \"zero\")", &["Int", "index"]);
    assert_error("(append [1] 2)", &["vector"]);
}

#[test]
fn record_inheritance_defines_nominal_subtyping() {
    let source = "(record Parent [value Int])\n(record Child (Parent) [name String])\n(define as-parent (fun ([item Parent] [-> Parent]) item))\n(as-parent (Child :value 1 :name \"child\"))";
    assert_eq!(value(source), "(record Child [name \"child\"] [value 1])");
}

#[test]
fn field_access_is_precise_for_local_and_inherited_fields() {
    let source = "(record Position [x Int])\n(record NamedPosition (Position) [name String])\n(define point (NamedPosition :name \"origin\" :x 42))\n(field point :x)";
    assert_eq!(value(source), "42");
    assert_eq!(type_of(source).unwrap(), Type::Int);
}

#[test]
fn field_access_evaluates_target_once() {
    assert_eq!(
        value(
            "(define count 0)\n(record Box [value Int])\n(define make-box (fun () (set! count (+ count 1)) (Box :value 41)))\n(+ (field (make-box) :value) count)"
        ),
        "42"
    );
}

#[test]
fn field_access_distributes_across_unions() {
    let source = "(record NumberBox [value Int])\n(record LabelBox [value String])\n(define choose (fun ([flag Bool]) (if flag (NumberBox :value 42) (LabelBox :value \"forty-two\"))))\n(field (choose true) :value)";
    assert_eq!(value(source), "42");
    assert_eq!(
        type_of(source).unwrap(),
        Type::Union(vec![Type::Int, Type::String])
    );
}

#[test]
fn field_access_on_any_is_runtime_checked() {
    let valid = "(record Box [value Int])\n(define read-value (fun ([item Any] [-> Any]) (field item :value)))\n(read-value (Box :value 42))";
    assert_eq!(value(valid), "42");
    assert_eq!(type_of(valid).unwrap(), Type::Any);
    assert_error(
        "(define read-value (fun ([item Any] [-> Any]) (field item :value)))\n(read-value 42)",
        &["record"],
    );
}

#[test]
fn invalid_field_access_is_rejected() {
    assert_error(
        "(record Box [value Int])\n(field (Box :value 42) :missing)",
        &["missing", "unknown field"],
    );
    assert_error("(field 42 :value)", &["record"]);
    assert_error("(field 42 value)", &["keyword"]);
}

#[test]
fn unrelated_record_types_are_not_subtypes() {
    assert_error(
        "(record Expected [value Int])\n(record Other [value Int])\n(define accept (fun ([item Expected]) item))\n(accept (Other :value 1))",
        &["Expected", "Other", "call argument"],
    );
}

#[test]
fn type_equivalence_is_structural_and_union_order_independent() {
    assert_eq!(
        Type::ExactVector(vec![Type::Int, Type::String]),
        Type::ExactVector(vec![Type::Int, Type::String])
    );
    assert_ne!(
        Type::ExactVector(vec![Type::Int, Type::String]),
        Type::ExactVector(vec![Type::String, Type::Int])
    );
}
