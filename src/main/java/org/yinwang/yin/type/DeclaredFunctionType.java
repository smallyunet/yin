package org.yinwang.yin.type;

import java.util.List;

/** Source-expressible callable signature used by higher-order functions. */
public final class DeclaredFunctionType extends YinType {
    private final List<YinType> parameters;
    private final YinType result;

    public DeclaredFunctionType(List<YinType> parameters, YinType result) {
        this.parameters = List.copyOf(parameters);
        this.result = result;
    }

    public List<YinType> parameters() {
        return parameters;
    }

    public YinType result() {
        return result;
    }

    @Override
    public String toString() {
        return "(Fn [" + String.join(" ", parameters.stream().map(Object::toString).toList())
                + "] " + result + ")";
    }
}
