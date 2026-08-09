package org.yinwang.yin.value;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Node;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Runtime record schema and callable constructor. */
public final class RecordConstructor extends Value {
    public final String name;
    public final Node definition;
    public final Scope<Value> properties;
    private final Set<String> nominalTypes;

    public RecordConstructor(String name, Node definition, Scope<Value> properties,
                             Set<String> nominalTypes) {
        this.name = name;
        this.definition = definition;
        this.properties = properties.copy();
        this.nominalTypes = Set.copyOf(new LinkedHashSet<>(nominalTypes));
    }

    public Set<String> nominalTypes() {
        return nominalTypes;
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
