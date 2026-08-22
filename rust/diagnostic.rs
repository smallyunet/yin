use serde::Serialize;
use std::fmt;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub enum ErrorCode {
    #[serde(rename = "YIN0001")]
    Language,
    #[serde(rename = "YIN1001")]
    Syntax,
    #[serde(rename = "YIN1002")]
    Io,
}

impl fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Language => "YIN0001",
            Self::Syntax => "YIN1001",
            Self::Io => "YIN1002",
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct SourceSpan {
    pub file: String,
    pub start: usize,
    pub end: usize,
    pub line: usize,
    pub column: usize,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct Diagnostic {
    pub code: ErrorCode,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub span: Option<SourceSpan>,
}

#[derive(Clone, Debug, thiserror::Error)]
#[error("{diagnostic}")]
pub struct YinError {
    diagnostic: Diagnostic,
}

impl Diagnostic {
    pub fn new(code: ErrorCode, message: impl Into<String>, span: Option<SourceSpan>) -> Self {
        Self {
            code,
            message: message.into(),
            span,
        }
    }
}

impl fmt::Display for Diagnostic {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(span) = &self.span {
            write!(
                f,
                "{}: {} at {}:{}:{}",
                self.code, self.message, span.file, span.line, span.column
            )
        } else {
            write!(f, "{}: {}", self.code, self.message)
        }
    }
}

impl YinError {
    pub fn new(code: ErrorCode, message: impl Into<String>, span: Option<SourceSpan>) -> Self {
        Self {
            diagnostic: Diagnostic::new(code, message, span),
        }
    }

    pub fn language(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Language, message, None)
    }

    pub fn io(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Io, message, None)
    }

    pub fn diagnostic(&self) -> &Diagnostic {
        &self.diagnostic
    }
}
