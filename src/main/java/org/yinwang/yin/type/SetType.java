package org.yinwang.yin.type;

/** Static type of an immutable set. */
public final class SetType extends YinType {
    private final YinType element;

    public SetType(YinType element) {
        this.element = element;
    }

    public YinType element() {
        return element;
    }

    @Override
    public String toString() {
        return "(Set " + element + ")";
    }
}
