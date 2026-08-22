package org.yinwang.yin.value;

import java.util.ArrayList;
import java.util.List;

/** Immutable insertion-ordered set using Yin structural equality. */
public final class SetValue extends Value {
    private final List<Value> values;

    public SetValue(List<Value> values) {
        List<Value> unique = new ArrayList<>();
        for (Value value : values) {
            if (unique.stream().noneMatch(existing -> ValueEquality.equal(existing, value))) {
                unique.add(value);
            }
        }
        this.values = List.copyOf(unique);
    }

    public List<Value> values() {
        return values;
    }

    public int size() {
        return values.size();
    }

    public boolean contains(Value value) {
        return values.stream().anyMatch(existing -> ValueEquality.equal(existing, value));
    }

    public SetValue add(Value value) {
        if (contains(value)) {
            return this;
        }
        List<Value> updated = new ArrayList<>(values);
        updated.add(value);
        return new SetValue(updated);
    }

    public SetValue remove(Value value) {
        List<Value> updated = new ArrayList<>(values);
        for (int i = 0; i < updated.size(); i++) {
            if (ValueEquality.equal(updated.get(i), value)) {
                updated.remove(i);
                break;
            }
        }
        return new SetValue(updated);
    }

    @Override
    public String toString() {
        StringBuilder output = new StringBuilder("(set");
        for (Value value : values) {
            output.append(' ').append(value);
        }
        return output.append(')').toString();
    }
}
