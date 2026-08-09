package org.yinwang.yin.type;

/** Precise type of a failed {@code Result} value. */
public final class ErrType extends YinType {
    private final YinType error;

    public ErrType(YinType error) {
        this.error = error;
    }

    public YinType error() {
        return error;
    }

    @Override
    public String toString() {
        return "(Err " + error + ")";
    }
}
