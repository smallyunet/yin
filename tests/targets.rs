use std::io::Write;
use std::process::Command;
use yin::{Effect, ProfileStatus, check_target_source, infer_effects, parse, target_profiles};

#[test]
fn pure_programs_have_no_effects() {
    let program = parse(
        "pure.yin",
        "(define add (fun ([x Int] [-> Int]) (+ x 1)))\n(add 41)",
    )
    .unwrap();
    let report = infer_effects(&program);
    assert!(report.effects.is_empty());
    assert!(report.entry_effects.is_empty());
    assert!(report.origins.is_empty());
}

#[test]
fn effects_propagate_through_the_named_call_graph() {
    let source = "(define emit (fun ([value String]) (print value)))\n(define wrapper (fun ([value String]) (emit value)))\n(wrapper \"hello\")";
    let report = infer_effects(&parse("calls.yin", source).unwrap());
    assert_eq!(report.effects, vec![Effect::HostIo]);
    assert_eq!(report.entry_effects, vec![Effect::HostIo]);

    let entry = report
        .functions
        .iter()
        .find(|function| function.name == "<entry>")
        .unwrap();
    assert_eq!(entry.calls, vec!["wrapper"]);
    assert_eq!(entry.effects, vec![Effect::HostIo]);
    let wrapper = report
        .functions
        .iter()
        .find(|function| function.name == "wrapper")
        .unwrap();
    assert_eq!(wrapper.calls, vec!["emit"]);
    assert_eq!(wrapper.effects, vec![Effect::HostIo]);
    assert_eq!(report.origins[0].owner, "emit");
    assert_eq!(report.origins[0].operation, "print");

    let callback = "(define emit (fun ([value String]) (print value)))\n(map [\"hello\"] emit)";
    let callback_report = infer_effects(&parse("callback.yin", callback).unwrap());
    assert!(callback_report.effects.contains(&Effect::Allocation));
    assert!(callback_report.entry_effects.contains(&Effect::HostIo));
    assert!(
        callback_report
            .functions
            .iter()
            .find(|function| function.name == "<entry>")
            .unwrap()
            .calls
            .contains(&"emit".into())
    );

    let nested = "(define outer (fun ([-> Bool]) (define hidden (fun ([value String]) (print value))) true))\ntrue";
    let nested_report = infer_effects(&parse("nested.yin", nested).unwrap());
    assert!(nested_report.effects.contains(&Effect::HostIo));
    assert!(
        nested_report
            .functions
            .iter()
            .any(|function| function.name == "hidden" && function.effects == vec![Effect::HostIo])
    );
}

#[test]
fn tool_metadata_adds_external_state_and_authorization_effects() {
    let source = std::fs::read_to_string("examples/agents/tool-boundary/main.yin").unwrap();
    let report = infer_effects(&parse("tool-boundary.yin", &source).unwrap());
    for effect in [
        Effect::Allocation,
        Effect::HostIo,
        Effect::PersistentState,
        Effect::ExternalCall,
        Effect::Authorization,
    ] {
        assert!(report.effects.contains(&effect), "missing {effect:?}");
    }
    assert!(
        report
            .origins
            .iter()
            .any(|origin| origin.operation == "tool:write-note:write")
    );
    assert!(
        report
            .origins
            .iter()
            .any(|origin| origin.operation == "tool:write-note:approval")
    );
}

#[test]
fn hosted_and_mir_profiles_have_distinct_admission_boundaries() {
    let hosted = check_target_source("hosted.yin", "(print \"hello\")", "hosted-v1").unwrap();
    assert!(hosted.valid);
    assert_eq!(hosted.profile.status, ProfileStatus::Supported);

    let pure = check_target_source(
        "factorial.yin",
        "(define factorial (fun ([value Int] [-> Int]) (if (= value 0) 1 (* value (factorial (- value 1))))))\n(factorial 6)",
        "mir-pure-v1",
    )
    .unwrap();
    assert!(pure.valid, "{:?}", pure.violations);
    assert_eq!(pure.profile.status, ProfileStatus::Prototype);

    let rejected = check_target_source("mir-io.yin", "(print \"hello\")", "mir-pure-v1").unwrap();
    assert!(!rejected.valid);
    assert!(rejected.violations.iter().any(|violation| {
        violation.effect == Some(Effect::HostIo)
            && violation.operation.as_deref() == Some("print")
            && violation.span.is_some()
    }));

    let function_result = check_target_source(
        "mir-function.yin",
        "(fun ([value Int]) value)",
        "mir-pure-v1",
    )
    .unwrap();
    assert!(!function_result.valid);
    assert!(
        function_result
            .violations
            .iter()
            .any(|violation| violation.message.contains("data-returning"))
    );
}

#[test]
fn portable_profile_allows_only_its_input_boundary_and_narrow_syntax() {
    let valid = check_target_source(
        "portable.yin",
        "(encode-json (ok (read-all)))",
        "portable-bytecode-v1",
    )
    .unwrap();
    assert!(valid.valid, "{:?}", valid.violations);
    assert_eq!(valid.profile.status, ProfileStatus::Experimental);
    assert!(yin::compile_bytecode("portable.yin", "(encode-json (ok (read-all)))").is_ok());

    let rejected = check_target_source(
        "portable-invalid.yin",
        "[(read-text \"secret.txt\") 3.5]",
        "portable-bytecode-v1",
    )
    .unwrap();
    assert!(!rejected.valid);
    assert!(
        rejected
            .violations
            .iter()
            .any(|violation| violation.operation.as_deref() == Some("read-text"))
    );
    assert!(
        rejected
            .violations
            .iter()
            .any(|violation| violation.message.contains("float literals"))
    );

    let structural = check_target_source(
        "portable-structural.yin",
        "[(dict \"answer\" 42) 9223372036854775808]",
        "portable-bytecode-v1",
    )
    .unwrap();
    assert!(!structural.valid);
    assert!(
        structural
            .violations
            .iter()
            .any(|violation| violation.message.contains("Dict and Set"))
    );
    assert!(structural.violations.iter().any(|violation| {
        violation
            .message
            .contains("outside portable-bytecode-v1 signed 64-bit range")
    }));
    assert!(
        yin::compile_bytecode(
            "portable-structural.yin",
            "[(dict \"answer\" 42) 9223372036854775808]"
        )
        .unwrap_err()
        .to_string()
        .contains("portable-bytecode-v1 validation failed")
    );
}

#[test]
fn designed_profiles_fail_closed_without_implying_a_backend() {
    for profile in [
        "evm-contract-v1",
        "svm-program-v1",
        "riscv64-v1",
        "bitcoin-tapscript-v1",
    ] {
        let report = check_target_source("designed.yin", "true", profile).unwrap();
        assert!(!report.valid);
        assert_eq!(report.profile.status, ProfileStatus::Designed);
        assert!(!report.profile.validator_available);
        assert!(!report.profile.artifact_backend_available);
        assert!(report.violations[0].message.contains("designed only"));
    }
    assert!(
        check_target_source("unknown.yin", "true", "unknown-v1")
            .unwrap_err()
            .to_string()
            .contains("unknown target profile")
    );
}

#[test]
fn profile_registry_is_versioned_and_cli_reports_all_violations() {
    assert!(
        target_profiles()
            .iter()
            .all(|profile| profile.name.contains("-v1"))
    );

    let mut source = tempfile::NamedTempFile::new().unwrap();
    writeln!(source, "[(print \"hello\") (read-text \"secret.txt\")]").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("check")
        .arg("--target")
        .arg("mir-pure-v1")
        .arg(source.path())
        .output()
        .unwrap();
    assert!(!output.status.success());
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["profile"]["name"], "mir-pure-v1");
    assert_eq!(report["valid"], false);
    assert!(report["violations"].as_array().unwrap().len() >= 2);

    let profiles = Command::new(env!("CARGO_BIN_EXE_yin"))
        .arg("--target-profiles")
        .output()
        .unwrap();
    assert!(profiles.status.success());
    let listed: serde_json::Value = serde_json::from_slice(&profiles.stdout).unwrap();
    assert!(listed.as_array().unwrap().len() >= 7);
}
