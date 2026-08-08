package org.yinwang.yin.type;

import org.yinwang.yin.Constants;

import java.util.List;

public final class VectorType extends YinType {
    private final List<YinType> elements;

    public VectorType(List<YinType> elements) {
        this.elements = List.copyOf(elements);
    }

    public List<YinType> elements() {
        return elements;
    }

    @Override
    public String toString() {
        return Constants.SQUARE_BEGIN + String.join(" ",
                elements.stream().map(Object::toString).toList()) + Constants.SQUARE_END;
    }
}
