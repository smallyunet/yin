use std::env;
use std::fs;
use std::io::{self, Read};
use yin::{
    Engine, Host, VERSION, Value, approval_request, compile_bytecode, contract_run, format_source,
    gateway_run, guard_run, replay_trace, run_language_server,
};

fn main() {
    if let Err((status, message)) = run() {
        eprintln!("{message}");
        std::process::exit(status);
    }
}

fn run() -> Result<(), (i32, String)> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    match arguments.first().map(String::as_str) {
        Some("--version") if arguments.len() == 1 => {
            println!("Yin {VERSION}");
            Ok(())
        }
        Some("--lsp") if arguments.len() == 1 => run_language_server(io::stdin(), io::stdout())
            .map_err(|e| (1, format!("language server failed: {e}"))),
        Some("--format") => format_command(&arguments[1..]),
        Some("--emit-hir") => emit_hir_command(&arguments[1..]),
        Some("--emit-mir") => emit_mir_command(&arguments[1..]),
        Some("--run-mir") => run_mir_command(&arguments[1..]),
        Some("--json") => json_command(&arguments[1..]),
        Some("--capabilities") => capabilities_command(&arguments[1..]),
        Some("--contract-check") => contract_check_command(&arguments[1..]),
        Some("--contract-run") => contract_run_command(&arguments[1..]),
        Some("--contract-compile") => contract_compile_command(&arguments[1..]),
        Some("--approval-request") => approval_request_command(&arguments[1..]),
        Some("--gateway") => gateway_command(&arguments[1..]),
        Some("--guard") => guard_command(&arguments[1..]),
        Some("--replay") => replay_command(&arguments[1..]),
        Some("--repl") | None => repl(),
        Some(option) if option.starts_with("--") => Err((
            2,
            format!("unsupported command in Rust v{VERSION}: {option}"),
        )),
        Some(file) => execute(file, &arguments[1..], false),
    }
}

fn emit_mir_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let program = mir_command_program(arguments, "--emit-mir")?;
    print!("{}", yin::render_mir(&program));
    Ok(())
}

fn run_mir_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let program = mir_command_program(arguments, "--run-mir")?;
    let value = yin::evaluate_mir(&program).map_err(|error| (1, error.to_string()))?;
    println!("{value}");
    Ok(())
}

fn mir_command_program(
    arguments: &[String],
    command: &str,
) -> Result<yin::MirProgram, (i32, String)> {
    if arguments.len() != 1 {
        return Err((2, format!("usage: {command} <program.yin>")));
    }
    let file = &arguments[0];
    let source =
        fs::read_to_string(file).map_err(|error| (1, format!("failed to read {file}: {error}")))?;
    let program = yin::parse(file, &source).map_err(|error| (1, error.to_string()))?;
    yin::check_mir_program(&program).map_err(|error| (1, error.to_string()))
}

fn emit_hir_command(arguments: &[String]) -> Result<(), (i32, String)> {
    if arguments.len() != 1 {
        return Err((2, "usage: --emit-hir <program.yin>".into()));
    }
    let file = &arguments[0];
    let source =
        fs::read_to_string(file).map_err(|error| (1, format!("failed to read {file}: {error}")))?;
    let program = yin::parse(file, &source).map_err(|error| (1, error.to_string()))?;
    let checked = yin::check_hir_program(&program).map_err(|error| (1, error.to_string()))?;
    print!("{}", yin::render_hir(&checked.hir));
    Ok(())
}

fn capabilities_command(arguments: &[String]) -> Result<(), (i32, String)> {
    if arguments.len() != 1 {
        return Err((2, "usage: --capabilities <program.yin>".into()));
    }
    let source = fs::read_to_string(&arguments[0])
        .map_err(|e| (1, format!("failed to read {}: {e}", arguments[0])))?;
    let program = yin::parse(&arguments[0], &source).map_err(|e| (1, e.to_string()))?;
    let mut tools = Vec::new();
    for expression in &program.expressions {
        collect_tools(expression, &mut tools);
    }
    println!("{}", serde_json::json!({"version":1,"tools":tools}));
    Ok(())
}

fn collect_tools(expression: &yin::Expr, tools: &mut Vec<serde_json::Value>) {
    if let yin::Expr::Form(form, values, _) = expression {
        if *form == yin::Form::Tuple && values.first().and_then(yin::Expr::atom) == Some("tool") {
            let property = |name: &str| {
                values
                    .iter()
                    .position(|value| value.atom() == Some(name))
                    .and_then(|index| values.get(index + 1))
                    .and_then(|value| match value {
                        yin::Expr::Atom(value, _) | yin::Expr::String(value, _) => {
                            Some(value.clone())
                        }
                        _ => None,
                    })
            };
            tools.push(serde_json::json!({
                "name": values.get(1).and_then(yin::Expr::atom).unwrap_or(""),
                "capability": property(":capability").unwrap_or_default(),
                "effect": property(":effect").unwrap_or_default().trim_start_matches(':'),
                "approvalRequired": property(":approval").as_deref() == Some("true")
            }));
        }
        for value in values {
            collect_tools(value, tools);
        }
    }
}

fn guard_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let Some(program) = arguments.first() else {
        return Err((2, "usage: --guard <program.yin> --input <input.json> --host <host.json> --trace <trace.jsonl> [--approve <capability>]".into()));
    };
    let result = guard_run(
        std::path::Path::new(program),
        std::path::Path::new(option(arguments, "--input")?),
        std::path::Path::new(option(arguments, "--host")?),
        std::path::Path::new(option(arguments, "--trace")?),
        option_optional(arguments, "--approve"),
    )
    .map_err(|e| (1, e.to_string()))?;
    println!("{result}");
    Ok(())
}

fn approval_request_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let Some(program) = arguments.first() else {
        return Err((2, "usage: --approval-request <program.yin> --intent <intent.json> --host <host.json> --out <approval.json> --approved-by <identity> --expires-in-seconds <seconds>".into()));
    };
    let intent = option(arguments, "--intent")?;
    let host = option(arguments, "--host")?;
    let out = option(arguments, "--out")?;
    let approved_by = option(arguments, "--approved-by")?;
    let expires = option(arguments, "--expires-in-seconds")?
        .parse::<u64>()
        .map_err(|_| (2, "invalid expiry".into()))?;
    approval_request(
        std::path::Path::new(program),
        std::path::Path::new(intent),
        std::path::Path::new(host),
        std::path::Path::new(out),
        approved_by,
        expires,
    )
    .map_err(|e| (1, e.to_string()))
}

fn gateway_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let Some(program) = arguments.first() else {
        return Err((2, "usage: --gateway <program.yin> --intent <intent.json> --host <host.json> --trace <trace.jsonl> [--approval <approval.json> --nonce-store <used.jsonl>]".into()));
    };
    let intent = option(arguments, "--intent")?;
    let host = option(arguments, "--host")?;
    let trace = option(arguments, "--trace")?;
    let approval = option_optional(arguments, "--approval");
    let nonce = option_optional(arguments, "--nonce-store");
    if approval.is_some() != nonce.is_some() {
        return Err((
            2,
            "--approval and --nonce-store must be supplied together".into(),
        ));
    }
    let result = gateway_run(
        std::path::Path::new(program),
        std::path::Path::new(intent),
        std::path::Path::new(host),
        std::path::Path::new(trace),
        approval.map(std::path::Path::new),
        nonce.map(std::path::Path::new),
    )
    .map_err(|e| (1, e.to_string()))?;
    println!("{result}");
    Ok(())
}

fn replay_command(arguments: &[String]) -> Result<(), (i32, String)> {
    if arguments.len() != 1 {
        return Err((2, "usage: --replay <trace.jsonl>".into()));
    }
    let result =
        replay_trace(std::path::Path::new(&arguments[0])).map_err(|e| (1, e.to_string()))?;
    println!("{result}");
    Ok(())
}

fn option<'a>(arguments: &'a [String], name: &str) -> Result<&'a str, (i32, String)> {
    option_optional(arguments, name).ok_or_else(|| (2, format!("{name} is required")))
}
fn option_optional<'a>(arguments: &'a [String], name: &str) -> Option<&'a str> {
    arguments
        .iter()
        .position(|value| value == name)
        .and_then(|index| arguments.get(index + 1))
        .map(String::as_str)
}

fn contract_check_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let Some(file) = arguments.first() else {
        return Err((2, "usage: --contract-check <program.yin>".into()));
    };
    let source =
        fs::read_to_string(file).map_err(|e| (1, format!("failed to read {file}: {e}")))?;
    compile_bytecode(file, &source).map_err(|e| (1, e.to_string()))?;
    println!(
        "{}",
        serde_json::json!({"profile":"deterministic-policy-v1","valid":true})
    );
    Ok(())
}

fn contract_run_command(arguments: &[String]) -> Result<(), (i32, String)> {
    if arguments.len() != 3 || arguments[1] != "--input" {
        return Err((
            2,
            "usage: --contract-run <program.yin> --input <input.json>".into(),
        ));
    }
    let result = contract_run(
        std::path::Path::new(&arguments[0]),
        std::path::Path::new(&arguments[2]),
    )
    .map_err(|e| (1, e.to_string()))?;
    println!("{result}");
    Ok(())
}

fn contract_compile_command(arguments: &[String]) -> Result<(), (i32, String)> {
    if arguments.len() != 3 || arguments[1] != "--output" {
        return Err((
            2,
            "usage: --contract-compile <program.yin> --output <program.ybc>".into(),
        ));
    }
    let source = fs::read_to_string(&arguments[0])
        .map_err(|e| (1, format!("failed to read {}: {e}", arguments[0])))?;
    let bytes = compile_bytecode(&arguments[0], &source).map_err(|e| (1, e.to_string()))?;
    fs::write(&arguments[2], bytes)
        .map_err(|e| (1, format!("failed to write {}: {e}", arguments[2])))
}

fn execute(file: &str, arguments: &[String], json: bool) -> Result<(), (i32, String)> {
    let mut input = String::new();
    io::stdin()
        .read_to_string(&mut input)
        .map_err(|e| (1, format!("failed to read stdin: {e}")))?;
    let mut engine = Engine::new(Host::standard(input, arguments.to_vec()));
    let result = engine.run_file(file).map_err(|e| (1, e.to_string()))?;
    for line in result.output {
        if json {
            eprintln!("{line}")
        } else {
            println!("{line}")
        }
    }
    if json {
        match result.value {
            Value::String(text) => println!("{text}"),
            Value::Result { ok: true, value } => match *value {
                Value::String(text) => println!("{text}"),
                other => return Err((1, format!("--json expects Result String, got: {other}"))),
            },
            Value::Result { ok: false, value } => {
                println!("{}", serde_json::json!({"error":value.to_string()}));
                return Err((1, String::new()));
            }
            other => {
                return Err((
                    1,
                    format!("--json expects String or Result String, got: {other}"),
                ));
            }
        }
    } else {
        println!("{}", result.value);
    }
    Ok(())
}

fn json_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let Some(file) = arguments.first() else {
        return Err((2, "usage: --json <program.yin> [arguments...]".into()));
    };
    execute(file, &arguments[1..], true)
}

fn format_command(arguments: &[String]) -> Result<(), (i32, String)> {
    let mut check = false;
    let mut write = false;
    let mut files = Vec::new();
    for arg in arguments {
        match arg.as_str() {
            "--check" => check = true,
            "--write" => write = true,
            _ => files.push(arg),
        }
    }
    if files.is_empty() {
        return Err((
            2,
            "usage: --format [--check|--write] <program.yin>...".into(),
        ));
    }
    let mut changed = false;
    for file in files {
        let source =
            fs::read_to_string(file).map_err(|e| (1, format!("failed to read {file}: {e}")))?;
        let formatted = format_source(file, &source).map_err(|e| (1, e.to_string()))?;
        if check {
            if source != formatted {
                eprintln!("would reformat {file}");
                changed = true
            }
        } else if write {
            if source != formatted {
                fs::write(file, formatted)
                    .map_err(|e| (1, format!("failed to write {file}: {e}")))?
            }
        } else {
            print!("{formatted}")
        }
    }
    if changed {
        Err((1, "format check failed".into()))
    } else {
        Ok(())
    }
}

fn repl() -> Result<(), (i32, String)> {
    let mut session = yin::ReplSession::new(Host::default());
    let mut source = String::new();
    let mut line = String::new();
    loop {
        line.clear();
        if io::stdin()
            .read_line(&mut line)
            .map_err(|e| (1, e.to_string()))?
            == 0
        {
            if !source.trim().is_empty() {
                if let Err(error) = yin::parse("<repl>", &source) {
                    eprintln!("{error}");
                }
            }
            break;
        }
        if source.is_empty() && line.trim() == ":quit" {
            break;
        }
        source.push_str(&line);
        match yin::parse("<repl>", &source) {
            Ok(_) => {
                match session.evaluate("<repl>", &source) {
                    Ok((result, _)) => {
                        for line in result.output {
                            println!("{line}");
                        }
                        println!("{}", result.value)
                    }
                    Err(error) => eprintln!("{error}"),
                }
                source.clear()
            }
            Err(error) if error.to_string().contains("unclosed delimiter") => {}
            Err(error) => {
                eprintln!("{error}");
                source.clear()
            }
        }
    }
    Ok(())
}
