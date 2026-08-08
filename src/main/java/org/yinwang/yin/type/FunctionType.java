package org.yinwang.yin.type;

import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Fun;

public final class FunctionType extends YinType {
    public final Fun function;
    public final Scope<YinType> properties;
    public final Scope<YinType> environment;

    public FunctionType(Fun function, Scope<YinType> properties, Scope<YinType> environment) {
        this.function = function;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public String toString() {
        return properties == null ? "(fun)" : properties.toString();
    }
}
