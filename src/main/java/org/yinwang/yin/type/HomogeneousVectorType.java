package org.yinwang.yin.type;

/** Static type of an immutable vector whose elements share one type. */
public final class HomogeneousVectorType extends YinType {
    private final YinType element;

    public HomogeneousVectorType(YinType element) {
        this.element = element;
    }

    public YinType element() {
        return element;
    }

    @Override
    public String toString() {
        return "(Vector " + element + ")";
    }
}
