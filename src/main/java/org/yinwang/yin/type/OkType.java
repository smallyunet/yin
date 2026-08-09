package org.yinwang.yin.type;

/** Precise type of a successful {@code Result} value. */
public final class OkType extends YinType {
    private final YinType value;

    public OkType(YinType value) {
        this.value = value;
    }

    public YinType value() {
        return value;
    }

    @Override
    public String toString() {
        return "(Ok " + value + ")";
    }
}
