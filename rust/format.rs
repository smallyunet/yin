use crate::YinError;
use crate::syntax::{Expr, Form, parse};

pub fn format_source(file: &str, source: &str) -> Result<String, YinError> {
    let program = parse(file, source)?;
    if source.lines().any(|line| line.contains("--")) {
        let mut preserved = source.to_owned();
        if !preserved.ends_with('\n') {
            preserved.push('\n');
        }
        return Ok(preserved);
    }
    let mut output = String::new();
    for (index, expression) in program.expressions.iter().enumerate() {
        if index > 0 {
            output.push('\n');
        }
        render(expression, 0, &mut output);
    }
    if !output.is_empty() {
        output.push('\n');
    }
    Ok(output)
}

fn render(expression: &Expr, indent: usize, output: &mut String) {
    match expression {
        Expr::Atom(value, _) => output.push_str(value),
        Expr::String(value, _) => {
            output.push('"');
            for ch in value.chars() {
                match ch {
                    '\n' => output.push_str("\\n"),
                    '\r' => output.push_str("\\r"),
                    '\t' => output.push_str("\\t"),
                    '"' => output.push_str("\\\""),
                    '\\' => output.push_str("\\\\"),
                    _ => output.push(ch),
                }
            }
            output.push('"');
        }
        Expr::Form(form, values, _) => {
            let (open, close) = if *form == Form::Tuple {
                ('(', ')')
            } else {
                ('[', ']')
            };
            output.push(open);
            let multiline = values.len() > 3
                || values
                    .iter()
                    .any(|item| matches!(item, Expr::Form(_, nested, _) if nested.len() > 3));
            for (index, value) in values.iter().enumerate() {
                if index > 0 {
                    if multiline && index > 1 {
                        output.push('\n');
                        output.push_str(&" ".repeat(indent + 2));
                    } else {
                        output.push(' ');
                    }
                }
                render(value, indent + 2, output);
            }
            output.push(close);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn formatting_is_idempotent() {
        let once = format_source("x.yin", "(define x [1 2 3])").unwrap();
        assert_eq!(format_source("x.yin", &once).unwrap(), once);
    }
}
