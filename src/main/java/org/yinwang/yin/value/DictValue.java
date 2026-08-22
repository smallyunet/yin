package org.yinwang.yin.value;

import java.util.ArrayList;
import java.util.List;

/** Immutable insertion-ordered dictionary using Yin structural equality for keys. */
public final class DictValue extends Value {
    public record Entry(Value key, Value value) { }

    private final List<Entry> entries;

    public DictValue(List<Entry> entries) {
        List<Entry> unique = new ArrayList<>();
        for (Entry entry : entries) {
            int existing = indexOf(unique, entry.key());
            if (existing >= 0) {
                unique.set(existing, entry);
            } else {
                unique.add(entry);
            }
        }
        this.entries = List.copyOf(unique);
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public OptionValue get(Value key) {
        int index = indexOf(entries, key);
        return index < 0 ? OptionValue.none() : OptionValue.some(entries.get(index).value());
    }

    public boolean containsKey(Value key) {
        return indexOf(entries, key) >= 0;
    }

    public DictValue put(Value key, Value value) {
        List<Entry> updated = new ArrayList<>(entries);
        int index = indexOf(updated, key);
        Entry replacement = new Entry(key, value);
        if (index < 0) {
            updated.add(replacement);
        } else {
            updated.set(index, replacement);
        }
        return new DictValue(updated);
    }

    public DictValue remove(Value key) {
        List<Entry> updated = new ArrayList<>(entries);
        int index = indexOf(updated, key);
        if (index >= 0) {
            updated.remove(index);
        }
        return new DictValue(updated);
    }

    private static int indexOf(List<Entry> entries, Value key) {
        for (int i = 0; i < entries.size(); i++) {
            if (ValueEquality.equal(entries.get(i).key(), key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        StringBuilder output = new StringBuilder("(dict");
        for (Entry entry : entries) {
            output.append(' ').append(entry.key()).append(' ').append(entry.value());
        }
        return output.append(')').toString();
    }
}
