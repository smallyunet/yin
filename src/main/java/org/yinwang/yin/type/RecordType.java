package org.yinwang.yin.type;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.NominalIdentity;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RecordType extends YinType {
    public final String name;
    public final Node definition;
    public final Scope<YinType> properties;
    private final Set<String> nominalTypes;
    private final String variantName;
    private final String identity;

    public RecordType(String name, Node definition, Scope<YinType> properties,
                      Set<String> nominalTypes) {
        this(name, definition, properties, nominalTypes, null);
    }

    public RecordType(String name, Node definition, Scope<YinType> properties,
                      Set<String> nominalTypes, String variantName) {
        this.name = name;
        this.definition = definition;
        this.properties = properties.copy();
        this.nominalTypes = Set.copyOf(new LinkedHashSet<>(nominalTypes));
        this.variantName = variantName;
        this.identity = NominalIdentity.of(definition, name);
    }

    public Set<String> nominalTypes() {
        return nominalTypes;
    }

    public String variantName() { return variantName; }
    public String identity() { return identity; }

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
