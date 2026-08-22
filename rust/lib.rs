mod check;
mod contract;
mod diagnostic;
mod eval;
mod format;
#[cfg(not(target_arch = "wasm32"))]
mod gateway;
mod lsp;
mod syntax;
mod value;

pub use check::check_program;
pub use contract::{compile_bytecode, contract_run};
pub use diagnostic::{Diagnostic, ErrorCode, SourceSpan, YinError};
pub use eval::{Engine, Host, ProgramResult};
pub use format::format_source;
#[cfg(not(target_arch = "wasm32"))]
pub use gateway::{approval_request, gateway_run, guard_run, replay_trace};
pub use lsp::run_language_server;
pub use syntax::{Expr, Form, ParsedProgram, parse};
pub use value::{Type, Value};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(target_arch = "wasm32")]
mod wasm {
    use wasm_bindgen::prelude::*;

    #[wasm_bindgen]
    pub fn evaluate(source: &str, input: &str, arguments_json: &str) -> String {
        let arguments = serde_json::from_str::<Vec<String>>(arguments_json).unwrap_or_default();
        match crate::Engine::new(crate::Host::browser(input, arguments))
            .run_source("playground.yin", source)
        {
            Ok(result) => serde_json::json!({
                "ok": true,
                "value": result.value.to_string(),
                "output": result.output,
            })
            .to_string(),
            Err(error) => serde_json::json!({
                "ok": false,
                "error": error.diagnostic(),
            })
            .to_string(),
        }
    }

    #[wasm_bindgen]
    pub fn format(source: &str) -> String {
        match crate::format_source("playground.yin", source) {
            Ok(formatted) => serde_json::json!({"ok": true, "source": formatted}).to_string(),
            Err(error) => serde_json::json!({"ok": false, "error": error.diagnostic()}).to_string(),
        }
    }
}
