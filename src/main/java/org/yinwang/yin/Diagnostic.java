package org.yinwang.yin;

import java.util.Objects;
import java.util.Optional;

/** Structured language diagnostic suitable for CLI and embedding APIs. */
public record Diagnostic(Code code, String message, SourceSpan span) {

    public enum Code {
        LANGUAGE("YIN0001"),
        SYNTAX("YIN1001"),
        IO("YIN1002");

        private final String id;

        Code(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public Optional<SourceSpan> sourceSpan() {
        return Optional.ofNullable(span);
    }

    public String format() {
        String prefix = span == null ? "" : span.displayLocation() + " ";
        return prefix + "[" + code.id() + "] " + message;
    }
}
