package org.yinwang.yin.type;

/** Static type of an immutable dictionary. */
public final class DictType extends YinType {
    private final YinType key;
    private final YinType value;

    public DictType(YinType key, YinType value) {
        this.key = key;
        this.value = value;
    }

    public YinType key() {
        return key;
    }

    public YinType value() {
        return value;
    }

    @Override
    public String toString() {
        return "(Dict " + key + " " + value + ")";
    }
}
