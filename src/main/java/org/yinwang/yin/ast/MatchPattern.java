package org.yinwang.yin.ast;

import java.util.List;

/** Parsed patterns accepted by a {@link Match} expression. */
public sealed interface MatchPattern {
    Node location();

    record Wildcard(Node location) implements MatchPattern {
    }

    record Binding(Name name) implements MatchPattern {
        @Override
        public Node location() {
            return name;
        }
    }

    record Literal(Node value) implements MatchPattern {
        @Override
        public Node location() {
            return value;
        }
    }

    record VectorPattern(List<MatchPattern> elements, Node location) implements MatchPattern {
        public VectorPattern {
            elements = List.copyOf(elements);
        }
    }

    record RecordPattern(Name type, List<MatchPattern> fields, Node location) implements MatchPattern {
        public RecordPattern {
            fields = List.copyOf(fields);
        }
    }
}
