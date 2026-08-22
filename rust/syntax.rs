use crate::{ErrorCode, SourceSpan, YinError};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Form {
    Tuple,
    Vector,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Expr {
    Atom(String, SourceSpan),
    String(String, SourceSpan),
    Form(Form, Vec<Expr>, SourceSpan),
}

impl Expr {
    pub fn span(&self) -> &SourceSpan {
        match self {
            Self::Atom(_, span) | Self::String(_, span) | Self::Form(_, _, span) => span,
        }
    }

    pub fn atom(&self) -> Option<&str> {
        match self {
            Self::Atom(value, _) => Some(value),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct ParsedProgram {
    pub file: String,
    pub expressions: Vec<Expr>,
}

#[derive(Clone, Debug)]
enum TokenKind {
    Open(Form),
    Close(Form),
    Atom(String),
    String(String),
}

#[derive(Clone, Debug)]
struct Token {
    kind: TokenKind,
    span: SourceSpan,
}

pub fn parse(file: impl Into<String>, source: &str) -> Result<ParsedProgram, YinError> {
    let file = file.into();
    let tokens = lex(&file, source)?;
    let mut stack: Vec<(Form, Vec<Expr>, SourceSpan)> = Vec::new();
    let mut root = Vec::new();
    for token in tokens {
        match token.kind {
            TokenKind::Open(form) => stack.push((form, Vec::new(), token.span)),
            TokenKind::Close(form) => {
                let Some((open, items, mut span)) = stack.pop() else {
                    return Err(YinError::new(
                        ErrorCode::Syntax,
                        "unexpected closing delimiter",
                        Some(token.span),
                    ));
                };
                if open != form {
                    return Err(YinError::new(
                        ErrorCode::Syntax,
                        "mismatched closing delimiter",
                        Some(token.span),
                    ));
                }
                span.end = token.span.end;
                let expr = Expr::Form(form, items, span);
                if let Some((_, parent, _)) = stack.last_mut() {
                    parent.push(expr)
                } else {
                    root.push(expr)
                }
            }
            TokenKind::Atom(value) => {
                push_expr(&mut stack, &mut root, Expr::Atom(value, token.span))
            }
            TokenKind::String(value) => {
                push_expr(&mut stack, &mut root, Expr::String(value, token.span))
            }
        }
    }
    if let Some((_, _, span)) = stack.pop() {
        return Err(YinError::new(
            ErrorCode::Syntax,
            "unclosed delimiter",
            Some(span),
        ));
    }
    Ok(ParsedProgram {
        file,
        expressions: root,
    })
}

fn push_expr(stack: &mut [(Form, Vec<Expr>, SourceSpan)], root: &mut Vec<Expr>, expr: Expr) {
    if let Some((_, items, _)) = stack.last_mut() {
        items.push(expr)
    } else {
        root.push(expr)
    }
}

fn lex(file: &str, source: &str) -> Result<Vec<Token>, YinError> {
    let bytes = source.as_bytes();
    let mut out = Vec::new();
    let (mut index, mut line, mut column) = (0, 1, 1);
    while index < bytes.len() {
        let byte = bytes[index];
        if byte.is_ascii_whitespace() {
            advance(byte, &mut line, &mut column);
            index += 1;
            continue;
        }
        if byte == b'-' && bytes.get(index + 1) == Some(&b'-') {
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
                column += 1;
            }
            continue;
        }
        let start = index;
        let start_line = line;
        let start_column = column;
        let span = |end| SourceSpan {
            file: file.to_owned(),
            start,
            end,
            line: start_line,
            column: start_column,
        };
        match byte {
            b'(' | b'[' => {
                out.push(Token {
                    kind: TokenKind::Open(if byte == b'(' {
                        Form::Tuple
                    } else {
                        Form::Vector
                    }),
                    span: span(index + 1),
                });
                index += 1;
                column += 1;
            }
            b')' | b']' => {
                out.push(Token {
                    kind: TokenKind::Close(if byte == b')' {
                        Form::Tuple
                    } else {
                        Form::Vector
                    }),
                    span: span(index + 1),
                });
                index += 1;
                column += 1;
            }
            b'"' => {
                index += 1;
                column += 1;
                let mut value = String::new();
                let mut closed = false;
                while index < bytes.len() {
                    let current = bytes[index];
                    if current == b'"' {
                        index += 1;
                        column += 1;
                        closed = true;
                        break;
                    }
                    if current == b'\n' {
                        return Err(YinError::new(
                            ErrorCode::Syntax,
                            "string cannot span lines",
                            Some(span(index + 1)),
                        ));
                    }
                    if current == b'\\' {
                        let Some(next) = bytes.get(index + 1).copied() else {
                            break;
                        };
                        match next {
                            b'n' => value.push('\n'),
                            b'r' => value.push('\r'),
                            b't' => value.push('\t'),
                            b'"' => value.push('"'),
                            b'\\' => value.push('\\'),
                            _ => {
                                value.push('\\');
                                value.push(next as char);
                            }
                        }
                        index += 2;
                        column += 2;
                    } else {
                        let tail = &source[index..];
                        let ch = tail.chars().next().expect("valid UTF-8 source");
                        value.push(ch);
                        index += ch.len_utf8();
                        column += 1;
                    }
                }
                if !closed {
                    return Err(YinError::new(
                        ErrorCode::Syntax,
                        "unterminated string",
                        Some(span(index)),
                    ));
                }
                out.push(Token {
                    kind: TokenKind::String(value),
                    span: span(index),
                });
            }
            _ => {
                while index < bytes.len() {
                    let current = bytes[index];
                    if current.is_ascii_whitespace()
                        || b"()[]\"".contains(&current)
                        || (current == b'-' && bytes.get(index + 1) == Some(&b'-'))
                    {
                        break;
                    }
                    index += 1;
                    column += 1;
                }
                let atom = source[start..index].to_owned();
                if atom.is_empty() {
                    return Err(YinError::new(
                        ErrorCode::Syntax,
                        "invalid token",
                        Some(span(index + 1)),
                    ));
                }
                out.push(Token {
                    kind: TokenKind::Atom(atom),
                    span: span(index),
                });
            }
        }
    }
    Ok(out)
}

fn advance(byte: u8, line: &mut usize, column: &mut usize) {
    if byte == b'\n' {
        *line += 1;
        *column = 1
    } else {
        *column += 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_comments_strings_and_forms() {
        let program = parse("test.yin", "-- x\n(define xs [1 \"two\"])").unwrap();
        assert_eq!(program.expressions.len(), 1);
    }

    #[test]
    fn rejects_mismatched_delimiters() {
        assert!(parse("test.yin", "([)]").is_err());
    }
}
