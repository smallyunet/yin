package org.yinwang.yin;

import org.yinwang.yin.ast.Node;

import java.util.Objects;

/** Immutable source range using zero-based offsets and line/column values. */
public record SourceSpan(String file, int start, int end, int line, int column) {

    public SourceSpan {
        Objects.requireNonNull(file, "file");
        if (start < 0 || end < start || line < 0 || column < 0) {
            throw new IllegalArgumentException("invalid source span");
        }
    }

    public static SourceSpan from(Node node) {
        String file = node.file == null ? "<generated>" : node.file;
        return new SourceSpan(file, node.start, node.end, node.line, node.col);
    }

    public String displayLocation() {
        return file + ":" + (line + 1) + ":" + (column + 1);
    }
}
