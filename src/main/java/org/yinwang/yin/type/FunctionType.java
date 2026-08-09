package org.yinwang.yin.type;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Constants;
import org.yinwang.yin.ast.Declare;
import org.yinwang.yin.ast.Fun;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;

import java.util.ArrayList;
import java.util.List;

public final class FunctionType extends YinType {
    public final Fun function;
    public final Scope<YinType> properties;
    public final Scope<YinType> environment;

    public FunctionType(Fun function, Scope<YinType> properties, Scope<YinType> environment) {
        this.function = function;
        this.properties = properties;
        this.environment = environment;
    }

    public DeclaredFunctionType declaredSignature() {
        if (properties == null) {
            return null;
        }
        Scope<YinType> scope = new Scope<>(environment);
        Declare.mergeType(properties, scope);
        List<YinType> parameters = new ArrayList<>();
        for (Name parameter : function.params) {
            YinType type = scope.lookup(parameter.id);
            if (type == null) {
                return null;
            }
            parameters.add(type);
        }
        Object resultNode = properties.lookupPropertyLocal(Constants.RETURN_ARROW, "type");
        if (!(resultNode instanceof Node node)) {
            return null;
        }
        return new DeclaredFunctionType(parameters, node.typecheck(scope));
    }

    @Override
    public String toString() {
        return properties == null ? "(fun)" : properties.toString();
    }
}
