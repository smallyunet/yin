use crate::eval::ToolExecutor;
use crate::{Engine, Host, Value, YinError};
use fs2::FileExt;
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Intent {
    request_id: String,
    actor: String,
    agent: String,
    server: String,
    tool: String,
    capability: String,
    effect: String,
    resource: String,
    arguments: Json,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HostConfig {
    version: u32,
    timeout_millis: u64,
    servers: Vec<ServerConfig>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ServerConfig {
    name: String,
    command: Vec<String>,
    cwd: PathBuf,
    tools: Vec<ToolConfig>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ToolConfig {
    name: String,
    remote_name: String,
    capability: String,
    effect: String,
    approval_required: bool,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReferenceHost {
    version: u32,
    root: PathBuf,
    tools: Vec<ReferenceTool>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ReferenceTool {
    name: String,
    kind: String,
    capability: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Approval {
    version: u32,
    program_hash: String,
    host_hash: String,
    intent_hash: String,
    arguments_hash: String,
    request_id: String,
    actor: String,
    agent: String,
    server: String,
    tool: String,
    capability: String,
    effect: String,
    resource: String,
    expires_at: u64,
    approved_by: String,
    nonce: String,
}

pub fn approval_request(
    program: &Path,
    intent_path: &Path,
    host_path: &Path,
    output: &Path,
    approved_by: &str,
    expires_in: u64,
) -> Result<(), YinError> {
    if !(1..=86_400).contains(&expires_in) {
        return Err(YinError::language(
            "expiry must be between 1 and 86400 seconds",
        ));
    }
    let program_bytes = fs::read(program).map_err(io_error(program))?;
    let host_bytes = fs::read(host_path).map_err(io_error(host_path))?;
    let intent_bytes = fs::read(intent_path).map_err(io_error(intent_path))?;
    let intent: Intent = serde_json::from_slice(&intent_bytes)
        .map_err(|e| YinError::language(format!("invalid intent: {e}")))?;
    let now = unix_time()?;
    let nonce = hash(
        format!(
            "{}:{}:{}:{}",
            intent.request_id,
            approved_by,
            now,
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|e| YinError::language(e.to_string()))?
                .as_nanos()
        )
        .as_bytes(),
    );
    let approval = Approval {
        version: 1,
        program_hash: hash(&program_bytes),
        host_hash: hash(&host_bytes),
        intent_hash: hash_canonical(
            &serde_json::from_slice(&intent_bytes)
                .map_err(|e| YinError::language(e.to_string()))?,
        )?,
        arguments_hash: hash_canonical(&intent.arguments)?,
        request_id: intent.request_id,
        actor: intent.actor,
        agent: intent.agent,
        server: intent.server,
        tool: intent.tool,
        capability: intent.capability,
        effect: intent.effect,
        resource: intent.resource,
        expires_at: now + expires_in,
        approved_by: approved_by.into(),
        nonce,
    };
    let bytes =
        serde_json::to_vec_pretty(&approval).map_err(|e| YinError::language(e.to_string()))?;
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    let mut file = options.open(output).map_err(io_error(output))?;
    file.write_all(&bytes).map_err(io_error(output))?;
    file.sync_all().map_err(io_error(output))
}

pub fn gateway_run(
    program: &Path,
    intent_path: &Path,
    host_path: &Path,
    trace: &Path,
    approval_path: Option<&Path>,
    nonce_store: Option<&Path>,
) -> Result<String, YinError> {
    let program_bytes = fs::read(program).map_err(io_error(program))?;
    let source =
        String::from_utf8(program_bytes.clone()).map_err(|e| YinError::language(e.to_string()))?;
    let intent_bytes = fs::read(intent_path).map_err(io_error(intent_path))?;
    let intent_json: Json = serde_json::from_slice(&intent_bytes)
        .map_err(|e| YinError::language(format!("invalid intent: {e}")))?;
    let intent: Intent = serde_json::from_value(intent_json.clone())
        .map_err(|e| YinError::language(format!("invalid intent: {e}")))?;
    let host_bytes = fs::read(host_path).map_err(io_error(host_path))?;
    let host: HostConfig = serde_json::from_slice(&host_bytes)
        .map_err(|e| YinError::language(format!("invalid host: {e}")))?;
    if host.version != 1 {
        return Err(YinError::language("unsupported host version"));
    }
    let server = host
        .servers
        .iter()
        .find(|s| s.name == intent.server)
        .cloned()
        .ok_or_else(|| YinError::language("intent server is not configured"))?;
    let tool = server
        .tools
        .iter()
        .find(|t| t.remote_name == intent.tool)
        .cloned()
        .ok_or_else(|| YinError::language("intent tool is not configured"))?;
    if tool.capability != intent.capability || tool.effect != intent.effect {
        return Err(YinError::language(
            "host and intent capability/effect mismatch",
        ));
    }
    let approval = if tool.approval_required {
        let path = approval_path.ok_or_else(|| YinError::language("approval is required"))?;
        let value: Approval = serde_json::from_slice(&fs::read(path).map_err(io_error(path))?)
            .map_err(|e| YinError::language(format!("invalid approval: {e}")))?;
        validate_approval(&value, &program_bytes, &host_bytes, &intent_json, &intent)?;
        Some(value)
    } else {
        None
    };
    let consumed = Arc::new(Mutex::new(false));
    let host_dir = host_path.parent().unwrap_or(Path::new(".")).to_path_buf();
    let intent_arguments = intent.arguments.clone();
    let timeout = host.timeout_millis;
    let nonce_path = nonce_store.map(Path::to_path_buf);
    let approval_for_call = approval.clone();
    let server_for_call = server.clone();
    let tool_for_call = tool.clone();
    let consumed_for_call = Arc::clone(&consumed);
    let executor: ToolExecutor = Rc::new(move |local_name, arguments| {
        if local_name != tool_for_call.name || arguments != &intent_arguments {
            return Err(YinError::language(
                "source tool arguments do not match action intent",
            ));
        }
        if let Some(approval) = &approval_for_call {
            let mut done = consumed_for_call
                .lock()
                .map_err(|_| YinError::language("approval state lock poisoned"))?;
            if *done {
                return Err(YinError::language(
                    "approval nonce already consumed in this execution",
                ));
            }
            consume_nonce(
                nonce_path
                    .as_deref()
                    .ok_or_else(|| YinError::language("nonce store is required"))?,
                &approval.nonce,
            )?;
            *done = true;
        }
        call_mcp(
            &host_dir,
            &server_for_call,
            &tool_for_call,
            arguments,
            timeout,
        )
    });
    let mut runtime_host = Host::browser(
        String::from_utf8(intent_bytes).map_err(|e| YinError::language(e.to_string()))?,
        Vec::new(),
    );
    runtime_host.tool_executor = Some(executor);
    let mut engine = Engine::new(runtime_host);
    let result = engine.run_source(program.to_string_lossy(), &source)?;
    let output = match result.value {
        Value::String(text) => text,
        Value::Result { ok: true, value } => match *value {
            Value::String(text) => text,
            other => return Err(YinError::language(format!("gateway returned {other}"))),
        },
        other => return Err(YinError::language(format!("gateway returned {other}"))),
    };
    let event = json!({"version":1,"kind":"gateway-complete","programHash":hash(&program_bytes),"hostHash":hash(&host_bytes),"intentHash":hash_canonical(&intent_json)?,"result":serde_json::from_str::<Json>(&output).unwrap_or(Json::String(output.clone())),"resultHash":hash(output.as_bytes())});
    append_trace(trace, &event)?;
    Ok(output)
}

pub fn guard_run(
    program: &Path,
    input_path: &Path,
    host_path: &Path,
    trace: &Path,
    approved_capability: Option<&str>,
) -> Result<String, YinError> {
    let source = fs::read_to_string(program).map_err(io_error(program))?;
    let input = fs::read_to_string(input_path).map_err(io_error(input_path))?;
    let host_bytes = fs::read(host_path).map_err(io_error(host_path))?;
    let host: ReferenceHost = serde_json::from_slice(&host_bytes)
        .map_err(|e| YinError::language(format!("invalid reference host: {e}")))?;
    if host.version != 1 {
        return Err(YinError::language("unsupported reference host version"));
    }
    let root = host_path
        .parent()
        .unwrap_or(Path::new("."))
        .join(&host.root);
    fs::create_dir_all(&root).map_err(io_error(&root))?;
    let canonical_root = root.canonicalize().map_err(io_error(&root))?;
    let tools = host.tools.clone();
    let approval = approved_capability.map(str::to_owned);
    let executor: ToolExecutor = Rc::new(move |name, arguments| {
        let tool = tools
            .iter()
            .find(|tool| tool.name == name)
            .ok_or_else(|| YinError::language("tool is not configured"))?;
        if tool.kind == "write-text" && approval.as_deref() != Some(&tool.capability) {
            return Err(YinError::language(
                "write capability requires explicit approval",
            ));
        }
        let relative = arguments
            .get("path")
            .and_then(Json::as_str)
            .ok_or_else(|| YinError::language("file tool requires path"))?;
        let joined = canonical_root.join(relative);
        let parent = joined
            .parent()
            .ok_or_else(|| YinError::language("invalid path"))?;
        fs::create_dir_all(parent).map_err(io_error(parent))?;
        let canonical_parent = parent.canonicalize().map_err(io_error(parent))?;
        if !canonical_parent.starts_with(&canonical_root) {
            return Err(YinError::language("path escapes configured root"));
        }
        let target = canonical_parent.join(
            joined
                .file_name()
                .ok_or_else(|| YinError::language("invalid path"))?,
        );
        match tool.kind.as_str() {
            "read-text" => {
                let content = fs::read_to_string(&target).map_err(io_error(&target))?;
                Ok(json!({"path":relative,"content":content}))
            }
            "write-text" => {
                let content = arguments
                    .get("content")
                    .and_then(Json::as_str)
                    .ok_or_else(|| YinError::language("write tool requires content"))?;
                fs::write(&target, content).map_err(io_error(&target))?;
                Ok(json!({"path":relative,"bytes":content.len()}))
            }
            _ => Err(YinError::language("unsupported reference tool kind")),
        }
    });
    let mut runtime_host = Host::browser(input, Vec::new());
    runtime_host.tool_executor = Some(executor);
    let mut engine = Engine::new(runtime_host);
    let result = engine.run_source(program.to_string_lossy(), &source)?;
    let output = match result.value {
        Value::String(text) => text,
        Value::Result { ok: true, value } => match *value {
            Value::String(text) => text,
            other => return Err(YinError::language(format!("guard returned {other}"))),
        },
        other => return Err(YinError::language(format!("guard returned {other}"))),
    };
    let event = json!({"version":1,"kind":"guard-complete","programHash":hash(source.as_bytes()),"hostHash":hash(&host_bytes),"result":serde_json::from_str::<Json>(&output).unwrap_or(Json::String(output.clone())),"resultHash":hash(output.as_bytes())});
    append_trace(trace, &event)?;
    Ok(output)
}

pub fn replay_trace(trace: &Path) -> Result<String, YinError> {
    let file = File::open(trace).map_err(io_error(trace))?;
    let line = BufReader::new(file)
        .lines()
        .last()
        .ok_or_else(|| YinError::language("trace is empty"))?
        .map_err(|e| YinError::io(e.to_string()))?;
    let value: Json = serde_json::from_str(&line)
        .map_err(|e| YinError::language(format!("invalid trace: {e}")))?;
    let result = value
        .get("result")
        .ok_or_else(|| YinError::language("trace has no result"))?;
    serde_json::to_string(result).map_err(|e| YinError::language(e.to_string()))
}

fn call_mcp(
    host_dir: &Path,
    server: &ServerConfig,
    tool: &ToolConfig,
    arguments: &Json,
    timeout: u64,
) -> Result<Json, YinError> {
    let Some(command) = server.command.first() else {
        return Err(YinError::language("MCP command is empty"));
    };
    let mut child = Command::new(command)
        .args(&server.command[1..])
        .current_dir(host_dir.join(&server.cwd))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| YinError::language(format!("failed to start MCP server: {e}")))?;
    let mut input = child
        .stdin
        .take()
        .ok_or_else(|| YinError::language("MCP stdin unavailable"))?;
    let output = child
        .stdout
        .take()
        .ok_or_else(|| YinError::language("MCP stdout unavailable"))?;
    let (rx, _thread) = reader_channel(output);
    let wait = Duration::from_millis(timeout);
    request(
        &mut input,
        &rx,
        1,
        "initialize",
        json!({"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"yin","version":crate::VERSION}}),
        wait,
    )?;
    send(
        &mut input,
        &json!({"jsonrpc":"2.0","method":"notifications/initialized"}),
    )?;
    let listed = request(&mut input, &rx, 2, "tools/list", json!({}), wait)?;
    if !listed
        .pointer("/tools")
        .and_then(Json::as_array)
        .is_some_and(|tools| {
            tools
                .iter()
                .any(|entry| entry.get("name").and_then(Json::as_str) == Some(&tool.remote_name))
        })
    {
        terminate(&mut child);
        return Err(YinError::language(
            "remote MCP server did not advertise configured tool",
        ));
    }
    let result = request(
        &mut input,
        &rx,
        3,
        "tools/call",
        json!({"name":tool.remote_name,"arguments":arguments}),
        wait,
    )?;
    terminate(&mut child);
    if result.get("isError").and_then(Json::as_bool) == Some(true) {
        return Err(YinError::language("remote MCP tool returned isError"));
    }
    result
        .get("structuredContent")
        .cloned()
        .ok_or_else(|| YinError::language("MCP response has no structuredContent"))
}

fn reader_channel(
    output: impl std::io::Read + Send + 'static,
) -> (Receiver<String>, std::thread::JoinHandle<()>) {
    let (tx, rx) = mpsc::channel();
    let thread = std::thread::spawn(move || {
        for line in BufReader::new(output).lines() {
            match line {
                Ok(line) => {
                    if tx.send(line).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });
    (rx, thread)
}
fn send(input: &mut ChildStdin, message: &Json) -> Result<(), YinError> {
    writeln!(input, "{message}")
        .map_err(|e| YinError::language(format!("failed to write MCP request: {e}")))?;
    input.flush().map_err(|e| YinError::language(e.to_string()))
}
fn request(
    input: &mut ChildStdin,
    rx: &Receiver<String>,
    id: u64,
    method: &str,
    params: Json,
    timeout: Duration,
) -> Result<Json, YinError> {
    send(
        input,
        &json!({"jsonrpc":"2.0","id":id,"method":method,"params":params}),
    )?;
    loop {
        let line = rx
            .recv_timeout(timeout)
            .map_err(|_| YinError::language(format!("MCP {method} timed out")))?;
        let message: Json = serde_json::from_str(&line)
            .map_err(|e| YinError::language(format!("malformed MCP response: {e}")))?;
        if message.get("id").and_then(Json::as_u64) == Some(id) {
            if let Some(error) = message.get("error") {
                return Err(YinError::language(format!("MCP JSON-RPC error: {error}")));
            }
            return message
                .get("result")
                .cloned()
                .ok_or_else(|| YinError::language("MCP response has no result"));
        }
    }
}
fn terminate(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}
fn validate_approval(
    a: &Approval,
    program: &[u8],
    host: &[u8],
    intent_json: &Json,
    intent: &Intent,
) -> Result<(), YinError> {
    if a.version != 1
        || a.program_hash != hash(program)
        || a.host_hash != hash(host)
        || a.intent_hash != hash_canonical(intent_json)?
        || a.arguments_hash != hash_canonical(&intent.arguments)?
        || a.request_id != intent.request_id
        || a.actor != intent.actor
        || a.agent != intent.agent
        || a.server != intent.server
        || a.tool != intent.tool
        || a.capability != intent.capability
        || a.effect != intent.effect
        || a.resource != intent.resource
    {
        return Err(YinError::language("approval does not match request"));
    }
    if a.expires_at < unix_time()? {
        return Err(YinError::language("approval expired"));
    }
    Ok(())
}
fn consume_nonce(path: &Path, nonce: &str) -> Result<(), YinError> {
    let mut file = OpenOptions::new()
        .read(true)
        .append(true)
        .create(true)
        .open(path)
        .map_err(io_error(path))?;
    file.lock_exclusive()
        .map_err(|e| YinError::io(e.to_string()))?;
    let mut existing = String::new();
    std::io::Read::read_to_string(&mut file, &mut existing)
        .map_err(|e| YinError::io(e.to_string()))?;
    if existing.lines().any(|line| line == nonce) {
        return Err(YinError::language("approval nonce was already consumed"));
    }
    writeln!(file, "{nonce}").map_err(|e| YinError::io(e.to_string()))?;
    file.sync_all().map_err(|e| YinError::io(e.to_string()))
}
fn append_trace(path: &Path, event: &Json) -> Result<(), YinError> {
    let mut file = OpenOptions::new()
        .append(true)
        .create(true)
        .open(path)
        .map_err(io_error(path))?;
    writeln!(file, "{event}").map_err(|e| YinError::io(e.to_string()))?;
    file.sync_all().map_err(|e| YinError::io(e.to_string()))
}
fn hash_canonical(value: &Json) -> Result<String, YinError> {
    Ok(hash(
        serde_json::to_string(value)
            .map_err(|e| YinError::language(e.to_string()))?
            .as_bytes(),
    ))
}
fn hash(value: &[u8]) -> String {
    format!("sha256:{:x}", Sha256::digest(value))
}
fn unix_time() -> Result<u64, YinError> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| YinError::language(e.to_string()))?
        .as_secs())
}
fn io_error(path: &Path) -> impl FnOnce(std::io::Error) -> YinError + '_ {
    move |e| YinError::io(format!("{}: {e}", path.display()))
}
