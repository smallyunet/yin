use yin::{Engine, Host, Type, check_program, parse};

fn run(source: &str) -> Result<String, yin::YinError> {
    Engine::new(Host::default())
        .run_source("legacy-structured.yin", source)
        .map(|result| result.value.to_string())
}

fn value(source: &str) -> String {
    run(source).unwrap()
}

fn type_of(source: &str) -> Result<Type, yin::YinError> {
    check_program(&parse("legacy-structured.yin", source)?)
}

fn assert_error(source: &str, fragments: &[&str]) {
    let error = run(source).unwrap_err().to_string();
    assert!(
        fragments.iter().any(|fragment| error.contains(fragment)),
        "expected one of {fragments:?}, got: {error}"
    );
}

fn assert_decode_error(source: &str, code: &str, path: &str) {
    let actual = value(source);
    assert!(actual.contains(&format!("[code \"{code}\"]")), "{actual}");
    assert!(actual.contains(&format!("[path \"{path}\"]")), "{actual}");
}

#[test]
fn variants_have_named_constructors_and_exhaustive_payload_narrowing() {
    let source = r#"(variant Decision
                      [Approve [reason String]]
                      [Reject [reason String]]
                      [NeedsInput [question String]])
                    (define explain
                      (fun ([decision Decision] [-> String])
                        (match decision
                          [(Approve reason) reason]
                          [(Reject reason) reason]
                          [(NeedsInput question) question])))
                    [(explain (Approve :reason "safe"))
                     (explain (NeedsInput :question "amount?"))]"#;
    assert_eq!(value(source), "[\"safe\" \"amount?\"]");
}

#[test]
fn variants_reject_missing_cases_and_invalid_fields() {
    assert_error(
        "(variant Choice [Yes] [No])\n(define choose (fun ([value Choice]) (match value [(Yes) 1])))\nchoose",
        &["non-exhaustive", "No"],
    );
    assert_error(
        "(variant Choice [Yes [reason String]] [No])\n(Yes :reason 42)",
        &["String", "reason", "record"],
    );
}

#[test]
fn option_is_covariant_and_exhaustive() {
    let source = r#"(define describe
                      (fun ([value (Option Int)] [-> String])
                        (match value [(Some number) (to-string number)] [(None) "missing"])))
                    [(describe (some 42)) (describe none) (= none none)]"#;
    assert_eq!(value(source), "[\"42\" \"missing\" true]");
}

#[test]
fn strict_json_round_trips_records_variants_options_and_results() {
    let source = r#"(record Request [name String] [limit Int :default 10] [note (Option String)])
                    (variant Decision [Approve [reason String]] [Reject [reason String]])
                    [(decode-json Request "{\"name\":\"yin\",\"note\":null}")
                     (encode-json (Request :name "yin" :note (some "ready")))
                     (encode-json (Approve :reason "safe"))
                     (encode-json (ok 42))]"#;
    assert_eq!(
        value(source),
        "[(ok (record Request [name \"yin\"] [limit 10] [note none])) (ok \"{\\\"name\\\":\\\"yin\\\",\\\"limit\\\":10,\\\"note\\\":\\\"ready\\\"}\") (ok \"{\\\"tag\\\":\\\"Approve\\\",\\\"reason\\\":\\\"safe\\\"}\") (ok \"{\\\"tag\\\":\\\"Ok\\\",\\\"value\\\":42}\")]"
    );
}

#[test]
fn decode_errors_are_structured_and_precise() {
    let source = r#"(record Request [name String] [count Int])
                    [(decode-json Request "{\"name\":\"yin\",\"count\":\"many\"}")
                     (decode-json Request "{\"name\":\"yin\",\"count\":1,\"extra\":true}")]"#;
    let actual = value(source);
    assert!(actual.contains("[path \"$.count\"]"), "{actual}");
    assert!(actual.contains("[path \"$.extra\"]"), "{actual}");
}

#[test]
fn schema_is_deterministic_draft_202012_and_closed() {
    let actual = value(
        "(variant Decision [Approve [reason String]] [Reject [reason String]])\n(json-schema Decision)",
    );
    for fragment in [
        "https://json-schema.org/draft/2020-12/schema",
        "\\\"oneOf\\\"",
        "\\\"const\\\":\\\"Approve\\\"",
        "\\\"additionalProperties\\\":false",
    ] {
        assert!(actual.contains(fragment), "{actual}");
    }
}

#[test]
fn option_annotations_accept_some_and_none() {
    let source = "(define keep (fun ([value (Option Any)] [-> (Option Any)]) value))\n[(keep (some 1)) (keep none)]";
    assert_eq!(value(source), "[(some 1) none]");
}

#[test]
fn option_match_must_cover_none() {
    assert_error(
        "(define get (fun ([value (Option Int)]) (match value [(Some x) x])))\nget",
        &["non-exhaustive", "None"],
    );
}

#[test]
fn option_patterns_do_not_exhaust_any() {
    assert_error(
        "(define get (fun ([value Any]) (match value [(Some x) x] [(None) 0])))\nget",
        &["non-exhaustive", "Any"],
    );
}

#[test]
fn duplicate_variant_cases_are_rejected() {
    assert_error("(variant Choice [Yes] [Yes])", &["duplicated variant case"]);
}

#[test]
fn zero_field_variants_construct_and_compare_structurally() {
    assert_eq!(
        value("(variant Choice [Yes] [No])\n[(= (Yes) (Yes)) (= (Yes) (No))]"),
        "[true false]"
    );
}

#[test]
fn decode_rejects_missing_required_fields() {
    assert_decode_error(
        "(record R [name String])\n(decode-json R \"{}\")",
        "missing-field",
        "$.name",
    );
}

#[test]
fn decode_rejects_unknown_fields() {
    assert_decode_error(
        r#"(record R [name String])
           (decode-json R "{\"name\":\"x\",\"other\":1}")"#,
        "unknown-field",
        "$.other",
    );
}

#[test]
fn decode_rejects_malformed_json() {
    assert_decode_error("(decode-json Int \"[\")", "invalid-json", "$");
}

#[test]
fn decode_rejects_unknown_variant_tags() {
    assert_decode_error(
        r#"(variant D [Yes] [No])
           (decode-json D "{\"tag\":\"Maybe\"}")"#,
        "unknown-tag",
        "$.tag",
    );
}

#[test]
fn decode_supports_nested_results() {
    assert_eq!(
        value(r#"(decode-json (Result Int String) "{\"tag\":\"Err\",\"error\":\"offline\"}")"#),
        "(ok (err \"offline\"))"
    );
}

#[test]
fn decode_supports_option_null_and_payload() {
    assert_eq!(
        value(
            r#"[(decode-json (Option String) "null") (decode-json (Option String) "\"ready\"")]"#
        ),
        "[(ok none) (ok (some \"ready\"))]"
    );
}

#[test]
fn encode_returns_structured_failure_for_unsupported_values() {
    let actual = value("(encode-json (fun (x) x))");
    assert!(actual.starts_with("(err (record EncodeError"), "{actual}");
    assert!(actual.contains("unsupported-value"), "{actual}");
}

#[test]
fn decode_requires_string_input() {
    assert_error("(decode-json Int 42)", &["String", "decode-json input"]);
}

#[test]
fn schema_models_options_results_and_integer_bounds() {
    let actual = value("(json-schema (Result (Option Int) String))");
    for fragment in [
        "\\\"anyOf\\\"",
        "\\\"minimum\\\":-2147483648",
        "\\\"const\\\":\\\"Err\\\"",
    ] {
        assert!(actual.contains(fragment), "{actual}");
    }
}

#[test]
fn json_encoding_escapes_control_characters_and_quotes() {
    assert_eq!(
        value("(encode-json \"a\\n\\\"b\")"),
        "(ok \"\\\"a\\\\n\\\\\\\"b\\\"\")"
    );
}

#[test]
fn inherited_record_contracts_decode_flattened_fields() {
    assert_eq!(
        value(
            r#"(record Base [id Int])
                  (record Child (Base) [name String])
                  (decode-json Child "{\"name\":\"yin\",\"id\":42}")"#
        ),
        "(ok (record Child [name \"yin\"] [id 42]))"
    );
}

#[test]
fn variant_json_round_trip_is_stable() {
    assert_eq!(
        value(
            r#"(variant D [Yes [reason String]] [No])
                  (match (decode-json D "{\"tag\":\"Yes\",\"reason\":\"ok\"}")
                    [(Ok value) (encode-json value)]
                    [(Err error) (err error)])"#
        ),
        "(ok \"{\\\"tag\\\":\\\"Yes\\\",\\\"reason\\\":\\\"ok\\\"}\")"
    );
}

#[test]
fn schema_generation_is_byte_for_byte_deterministic() {
    let actual = value(
        "(record R [name String] [count Int :default 1])\n[(= (json-schema R) (json-schema R)) (json-schema R)]",
    );
    assert!(actual.starts_with("[true \""), "{actual}");
    assert!(
        actual.contains("\\\"required\\\":[\\\"name\\\"]"),
        "{actual}"
    );
}

#[test]
fn maintained_structured_agent_is_runnable_and_typed() {
    let source = std::fs::read_to_string("examples/agents/structured-agent.yin").unwrap();
    let result = Engine::new(Host::standard(
        r#"{"task":"review","confidence":0.95}"#,
        vec![],
    ))
    .run_source("structured-agent.yin", &source)
    .unwrap();
    assert_eq!(
        result.value.to_string(),
        "(ok \"{\\\"tag\\\":\\\"Approve\\\",\\\"reason\\\":\\\"high confidence\\\"}\")"
    );
    assert!(matches!(type_of(&source).unwrap(), Type::Result(_, _)));
}
