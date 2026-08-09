package org.yinwang.yin.value;

/** Immutable success or failure value used at explicit outcome boundaries. */
public final class ResultValue extends Value {
    public enum Tag {
        OK,
        ERR
    }

    private final Tag tag;
    private final Value payload;

    public ResultValue(Tag tag, Value payload) {
        this.tag = tag;
        this.payload = payload;
    }

    public Tag tag() {
        return tag;
    }

    public Value payload() {
        return payload;
    }

    @Override
    public String toString() {
        return tag == Tag.OK ? "(ok " + payload + ")" : "(err " + payload + ")";
    }
}
