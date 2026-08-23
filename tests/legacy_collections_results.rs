use yin::{Engine, Host, Type, check_program, parse};

fn run(source: &str) -> Result<String, yin::YinError> {
    Engine::new(Host::default())
        .run_source("legacy-collections.yin", source)
        .map(|result| result.value.to_string())
}

fn value(source: &str) -> String {
    run(source).unwrap()
}

fn type_of(source: &str) -> Result<Type, yin::YinError> {
    check_program(&parse("legacy-collections.yin", source)?)
}

fn assert_error(source: &str, fragments: &[&str]) {
    let error = run(source).unwrap_err().to_string();
    assert!(
        fragments.iter().any(|fragment| error.contains(fragment)),
        "expected one of {fragments:?}, got: {error}"
    );
}

#[test]
fn dictionaries_are_immutable_ordered_and_safely_accessible() {
    assert_eq!(
        value(
            r#"(define original (dict "name" "yin" "version" "0.19"))
               (define updated (dict/put original "status" "experimental"))
               [(dict/get original "status")
                (dict/get updated "status")
                (dict/keys updated)
                (dict/values updated)
                (dict/size original)
                (dict/size updated)]"#
        ),
        "[none (some \"experimental\") [\"name\" \"version\" \"status\"] [\"yin\" \"0.19\" \"experimental\"] 2 3]"
    );
}

#[test]
fn dictionary_update_preserves_position_and_remove_is_persistent() {
    assert_eq!(
        value(
            r#"(define original (dict "a" 1 "b" 2))
               (define updated (dict/put original "a" 10))
               (define removed (dict/remove updated "b"))
               [original updated removed (dict/keys updated)]"#
        ),
        "[(dict \"a\" 1 \"b\" 2) (dict \"a\" 10 \"b\" 2) (dict \"a\" 10) [\"a\" \"b\"]]"
    );
}

#[test]
fn empty_collections_use_bottom_types() {
    let source = r#"(define empty-dict (fun ([-> (Dict String Int)]) (dict)))
                    (define empty-set (fun ([-> (Set String)]) (set)))
                    [(empty-dict) (empty-set)]"#;
    assert_eq!(value(source), "[(dict) (set)]");
    assert_eq!(
        type_of(source).unwrap(),
        Type::ExactVector(vec![
            Type::Dict(Box::new(Type::String), Box::new(Type::Int)),
            Type::Set(Box::new(Type::String)),
        ])
    );
}

#[test]
fn dictionary_lookup_requires_compatible_key_type() {
    assert_error(
        "(dict/get (dict \"answer\" 42) true)",
        &["key", "String", "argument"],
    );
}

#[test]
fn dictionary_constructor_rejects_unpaired_arguments() {
    assert_error("(dict \"answer\")", &["key/value pairs"]);
}

#[test]
fn keys_and_set_members_require_structural_equality() {
    assert_error("(dict (fun (value) value) 1)", &["comparable", "dict key"]);
    assert_error("(set (fun (value) value))", &["comparable", "set member"]);
}

#[test]
fn sets_deduplicate_and_provide_persistent_algebra() {
    assert_eq!(
        value(
            "(define left (set 1 2 2 3))\n(define right (set 3 4))\n[left (set/add left 4) (set/remove left 2) (set/union left right) (set/intersection left right) (set/difference left right) (set/contains left 2) (set/size left) (set/values left)]"
        ),
        "[(set 1 2 3) (set 1 2 3 4) (set 1 3) (set 1 2 3 4) (set 3) (set 1 2) true 3 [1 2 3]]"
    );
}

#[test]
fn dictionary_and_set_equality_ignore_insertion_order() {
    assert_eq!(
        value("[(= (dict \"a\" 1 \"b\" 2) (dict \"b\" 2 \"a\" 1)) (= (set 1 2 3) (set 3 2 1))]"),
        "[true true]"
    );
}

#[test]
fn json_round_trips_dictionaries_and_sets_deterministically() {
    let actual = value(
        r#"[(decode-json (Dict String Int) "{\"one\":1,\"two\":2}")
             (decode-json (Set String) "[\"a\",\"b\",\"a\"]")
             (encode-json (dict "one" 1 "two" 2))
             (encode-json (set "a" "b"))
             (json-schema (Dict String Int))
             (json-schema (Set String))]"#,
    );
    assert!(
        actual.starts_with("[(ok (dict \"one\" 1 \"two\" 2)) (ok (set \"a\" \"b\"))"),
        "{actual}"
    );
    assert!(actual.contains("\\\"uniqueItems\\\":true"), "{actual}");
}

#[test]
fn json_encoding_rejects_non_string_dictionary_keys() {
    let actual = value("(encode-json (dict 1 \"one\"))");
    assert!(actual.starts_with("(err (record EncodeError"), "{actual}");
    assert!(actual.contains("non-string-key"), "{actual}");
}

#[test]
fn json_schema_rejects_non_string_dictionary_keys() {
    assert_error(
        "(json-schema (Dict Int String))",
        &["String", "non-string-key", "dictionary key"],
    );
}

#[test]
fn result_constructors_are_tagged_and_typed() {
    assert_eq!(value("(ok 42)"), "(ok 42)");
    assert_eq!(value("(err \"unavailable\")"), "(err \"unavailable\")");
    assert_eq!(type_of("(ok 42)").unwrap(), Type::Ok(Box::new(Type::Int)));
}

#[test]
fn result_annotations_accept_compatible_variants() {
    let source = r#"(define choose
                      (fun ([available Bool] [-> (Result Int String)])
                        (if available (ok 42) (err "unavailable"))))
                    (define widen
                      (fun ([outcome (Result Any Any)] [-> (Result Any Any)]) outcome))
                    [(choose true) (choose false) (widen (choose true))]"#;
    assert_eq!(value(source), "[(ok 42) (err \"unavailable\") (ok 42)]");
}

#[test]
fn result_annotations_reject_incompatible_payloads() {
    assert_error(
        "(define invalid (fun ([-> (Result Int String)]) (ok true)))\n(invalid)",
        &["function return", "Result", "Bool"],
    );
    assert_error(
        "(define invalid (fun ([-> (Result Int String)]) (err 42)))\n(invalid)",
        &["function return", "Result", "Int"],
    );
}

#[test]
fn match_narrows_results_and_requires_both_variants() {
    let source = r#"(define describe
                      (fun ([outcome (Result Int String)] [-> String])
                        (match outcome
                          [(Ok value) (concat "value=" (to-string value))]
                          [(Err message) (concat "error=" message)])))
                    [(describe (ok 42)) (describe (err "offline"))]"#;
    assert_eq!(value(source), "[\"value=42\" \"error=offline\"]");
    assert_error(
        "(define unwrap (fun ([outcome (Result Int String)]) (match outcome [(Ok value) value])))\nunwrap",
        &["non-exhaustive", "Err"],
    );
}

#[test]
fn malformed_result_patterns_are_rejected() {
    assert_error(
        "(match (ok 42) [(Ok left right) left] [_ 0])",
        &["Ok pattern", "payload", "pattern"],
    );
}

#[test]
fn result_equality_includes_tag_and_payload() {
    assert_eq!(
        value("[(= (ok [1 2]) (ok [1 2])) (= (err \"x\") (err \"x\")) (= (ok 1) (err 1))]"),
        "[true true false]"
    );
}

#[test]
fn result_patterns_remain_runtime_checked_across_any() {
    let source = r#"(define describe
                      (fun ([outcome Any] [-> String])
                        (match outcome [(Ok _) "ok"] [(Err _) "err"] [_ "other"])))
                    [(describe (ok 1)) (describe (err "x")) (describe 42)]"#;
    assert_eq!(value(source), "[\"ok\" \"err\" \"other\"]");
}

#[test]
fn homogeneous_vectors_and_higher_order_functions_work() {
    let source = "(define sum (fun ([items (Vector Int)] [-> Int]) (fold items 0 (fun ([total Int] [value Int] [-> Int]) (+ total value)))))\n(sum [10 20 12])";
    assert_eq!(value(source), "42");
    assert_error(
        "(define sum (fun ([items (Vector Int)] [-> Int]) 0))\n(sum [1 \"two\"])",
        &["Vector", "Int", "call argument"],
    );
    assert_eq!(
        value(
            "(define apply (fun ([function (Fn [Int] Int)] [value Int] [-> Int]) (function value)))\n(apply (fun ([value Int] [-> Int]) (+ value 1)) 41)"
        ),
        "42"
    );
}

#[test]
fn immutable_collection_functions_are_precise() {
    let source = "(define source [1 2 3 4])\n(define doubled (map source (fun ([value Int] [-> Int]) (* value 2))))\n(define selected (filter doubled (fun ([value Int] [-> Bool]) (> value 4))))\n[(fold selected 0 (fun ([total Int] [value Int] [-> Int]) (+ total value))) source (reverse (slice doubled 1 3))]";
    assert_eq!(value(source), "[14 [1 2 3 4] [6 4]]");
}

#[test]
fn range_contains_and_string_operations_work() {
    assert_eq!(
        value("[(range 1 5) (contains (range 1 5) 3)]"),
        "[[1 2 3 4] true]"
    );
    assert_eq!(
        value(
            "(define words (split (trim \"  yin makes text useful  \") \" \"))\n[(join \"-\" words) (substring \"language\" 0 4) (string-length \"语言\") (= words [\"yin\" \"makes\" \"text\" \"useful\"])]"
        ),
        "[\"yin-makes-text-useful\" \"lang\" 2 true]"
    );
}

#[test]
fn match_is_exhaustive_and_rejects_duplicate_bindings() {
    assert_eq!(
        value("(match (parse-int \"41\") [(Int value) (+ value 1)] [(Bool _) 0])"),
        "42"
    );
    assert_error(
        "(define classify (fun ([value (U Int String)]) (match value [(Int number) number])))\nclassify",
        &["non-exhaustive", "String"],
    );
    assert_error(
        "(match [1 2] [[value value] value])",
        &["duplicate binding", "value"],
    );
}
