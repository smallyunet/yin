use serde_json::json;
use std::rc::Rc;
use yin::{Engine, Host, Type, YinError, check_program, parse};

fn run(source: &str) -> String {
    Engine::new(Host::default())
        .run_source("profile.yin", source)
        .unwrap()
        .value
        .to_string()
}

fn check_error(source: &str, expected: &str) {
    let error = check_program(&parse("profile.yin", source).unwrap()).unwrap_err();
    assert!(error.to_string().contains(expected), "{error}");
}

#[test]
fn policy_uses_first_matching_rule_and_dotted_fields() {
    assert_eq!(
        run(
            "(record Request [risk String] [amount Int])\n(policy decide ([request Request] [-> String]) (when (= request.risk \"blocked\") \"block\") (when (> request.amount 10) \"review\") (otherwise \"allow\"))\n(decide (Request :risk \"blocked\" :amount 100))"
        ),
        "\"block\""
    );
}

#[test]
fn policy_falls_through_to_otherwise() {
    assert_eq!(
        run(
            "(policy decide ([n Int] [-> String]) (when (> n 10) \"large\") (otherwise \"small\"))\n(decide 3)"
        ),
        "\"small\""
    );
}

#[test]
fn policy_requires_boolean_conditions_and_otherwise() {
    check_error(
        "(policy decide ([n Int] [-> String]) (when n \"x\") (otherwise \"y\"))",
        "if condition",
    );
    check_error(
        "(policy decide ([n Int] [-> String]) (when (> n 0) \"x\"))",
        "policy expects rules",
    );
}

#[test]
fn policy_return_contract_is_checked() {
    check_error(
        "(policy decide ([n Int] [-> String]) (when (> n 0) 1) (otherwise \"x\"))",
        "function return",
    );
}

fn tool_program() -> &'static str {
    "(record Request [value Int])\n(record Reply [value Int])\n(variant Failure [Rejected [message String]])\n(tool double Request Reply Failure :capability \"math.read\" :effect :read :approval false :idempotent true :open-world false)\n(match (invoke double (Request :value 21)) [(Ok reply) reply.value] [(Err _) 0])"
}

#[test]
fn tool_invocation_decodes_typed_host_output() {
    let host = Host {
        tool_executor: Some(Rc::new(|name, input| {
            assert_eq!(name, "double");
            Ok(json!({"value": input["value"].as_i64().unwrap() * 2}))
        })),
        ..Host::default()
    };
    assert_eq!(
        Engine::new(host)
            .run_source("tool.yin", tool_program())
            .unwrap()
            .value
            .to_string(),
        "42"
    );
}

#[test]
fn tool_without_gateway_returns_structured_error() {
    let source = "(record Request [value Int])\n(record Reply [value Int])\n(variant Failure [Rejected [message String]])\n(tool double Request Reply Failure :capability \"math.read\" :effect :read :approval false :idempotent true :open-world false)\n(match (invoke double (Request :value 1)) [(Ok _) \"ok\"] [(Err error) error.code])";
    assert_eq!(run(source), "\"unavailable\"");
}

#[test]
fn invalid_tool_output_is_a_structured_failure() {
    let host = Host {
        tool_executor: Some(Rc::new(|_, _| Ok(json!({"wrong": 1})))),
        ..Host::default()
    };
    let source = tool_program().replace("[(Err _) 0]", "[(Err error) error.code]");
    assert_eq!(
        Engine::new(host)
            .run_source("tool.yin", &source)
            .unwrap()
            .value
            .to_string(),
        "\"invalid-output\""
    );
}

#[test]
fn host_tool_errors_are_normalized() {
    let host = Host {
        tool_executor: Some(Rc::new(|_, _| Err(YinError::language("offline")))),
        ..Host::default()
    };
    let source = tool_program().replace("[(Err _) 0]", "[(Err error) error.code]");
    assert_eq!(
        Engine::new(host)
            .run_source("tool.yin", &source)
            .unwrap()
            .value
            .to_string(),
        "\"remote-error\""
    );
}

#[test]
fn maintained_policy_profiles_parse_and_typecheck() {
    for path in [
        "examples/agents/agent-review/main.yin",
        "examples/web3/transaction-guard/main.yin",
        "examples/agents/action-gateway/main.yin",
    ] {
        let source = std::fs::read_to_string(path).unwrap();
        let program = parse(path, &source).unwrap();
        let kind = check_program(&program).unwrap_or_else(|error| panic!("{path}: {error}"));
        assert!(!matches!(kind, Type::Never));
    }
}
