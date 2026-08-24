mod check;
mod contract;
mod diagnostic;
mod eval;
mod format;
#[cfg(not(target_arch = "wasm32"))]
mod gateway;
mod hir;
mod lsp;
mod syntax;
mod value;
#[cfg(any(target_arch = "wasm32", test))]
mod wallet;

pub use check::{CheckSession, check_program};
pub use contract::{compile_bytecode, contract_run};
pub use diagnostic::{Diagnostic, ErrorCode, SourceSpan, YinError};
pub use eval::{Engine, Host, ProgramResult, ReplSession};
pub use format::format_source;
#[cfg(not(target_arch = "wasm32"))]
pub use gateway::{approval_request, gateway_run, guard_run, replay_trace};
pub use hir::{
    CheckedProgram, HirArgument, HirExpr, HirKind, HirLiteral, HirParameter, HirProgram,
    HirRecordField, HirSymbol, HirSymbolKind, SymbolId, check_hir_program, render_hir,
};
pub use lsp::run_language_server;
pub use syntax::{Expr, Form, ParsedProgram, parse};
pub use value::{Type, Value};

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(target_arch = "wasm32")]
mod wasm {
    use std::cell::RefCell;
    use std::rc::Rc;

    use wasm_bindgen::prelude::*;

    thread_local! {
        static BROWSER_SECRET: RefCell<Option<String>> = const { RefCell::new(None) };
    }

    fn browser_host(input: &str, arguments: Vec<String>) -> crate::Host {
        let mut host = crate::Host::browser(input, arguments);
        host.tool_executor = Some(Rc::new(|name, input| {
            if name != "generate-eth-wallet" {
                return Err(crate::YinError::language(format!(
                    "browser host does not provide tool: {name}"
                )));
            }
            if input
                .get("acknowledged")
                .and_then(serde_json::Value::as_bool)
                != Some(true)
            {
                return Err(crate::YinError::language(
                    "wallet demo risk acknowledgement is required",
                ));
            }
            let wallet = loop {
                let mut bytes = [0_u8; 32];
                getrandom::fill(&mut bytes).map_err(|error| {
                    crate::YinError::language(format!("secure randomness unavailable: {error}"))
                })?;
                if let Ok(wallet) = crate::wallet::eth_wallet_from_private_key(&bytes) {
                    break wallet;
                }
            };
            BROWSER_SECRET.with(|secret| {
                *secret.borrow_mut() = Some(wallet.private_key);
            });
            Ok(serde_json::json!({
                "address": wallet.address,
                "publicKey": wallet.public_key,
            }))
        }));
        host
    }

    #[wasm_bindgen]
    pub fn evaluate(source: &str, input: &str, arguments_json: &str) -> String {
        BROWSER_SECRET.with(|secret| secret.borrow_mut().take());
        let arguments = serde_json::from_str::<Vec<String>>(arguments_json).unwrap_or_default();
        match crate::Engine::new(browser_host(input, arguments))
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
    pub fn take_browser_secret() -> String {
        BROWSER_SECRET.with(|secret| secret.borrow_mut().take().unwrap_or_default())
    }

    #[wasm_bindgen]
    pub fn format(source: &str) -> String {
        match crate::format_source("playground.yin", source) {
            Ok(formatted) => serde_json::json!({"ok": true, "source": formatted}).to_string(),
            Err(error) => serde_json::json!({"ok": false, "error": error.diagnostic()}).to_string(),
        }
    }
}
