package org.yinwang.yin.type;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Node;

import java.util.Map;

public final class RecordType extends YinType {
    public final String name;
    public final Node definition;
    public final Scope<YinType> properties;

    public RecordType(String name, Node definition, Scope<YinType> properties) {
        this.name = name;
        this.definition = definition;
        this.properties = properties.copy();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(Constants.PAREN_BEGIN)
                .append(Constants.RECORD_KEYWORD).append(" ")
                .append(name == null ? "_" : name);
        for (String field : properties.keySet()) {
            result.append(" ").append(Constants.SQUARE_BEGIN).append(field);
            for (Map.Entry<String, Object> property : properties.lookupAllProps(field).entrySet()) {
                if (property.getValue() != null) {
                    result.append(" :").append(property.getKey()).append(" ").append(property.getValue());
                }
            }
            result.append(Constants.SQUARE_END);
        }
        return result.append(Constants.PAREN_END).toString();
    }
}
