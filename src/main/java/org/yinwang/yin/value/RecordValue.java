package org.yinwang.yin.value;


import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;

import java.util.LinkedHashSet;
import java.util.Set;


public class RecordValue extends Value {

    public String name;
    public Scope<Value> properties;
    private final Set<String> nominalTypes;
    private final String variantName;
    private final String identity;


    public RecordValue(String name, Scope<Value> properties) {
        this(name, properties, name == null ? Set.of() : Set.of(name));
    }

    public RecordValue(String name, Scope<Value> properties, Set<String> nominalTypes) {
        this(name, properties, nominalTypes, null);
    }

    public RecordValue(String name, Scope<Value> properties, Set<String> nominalTypes,
                       String variantName) {
        this(name, properties, nominalTypes, variantName, name);
    }

    public RecordValue(String name, Scope<Value> properties, Set<String> nominalTypes,
                       String variantName, String identity) {
        this.name = name;
        this.properties = properties;
        this.nominalTypes = Set.copyOf(new LinkedHashSet<>(nominalTypes));
        this.variantName = variantName;
        this.identity = identity;
    }

    public Set<String> nominalTypes() {
        return nominalTypes;
    }

    public String variantName() { return variantName; }
    public String identity() { return identity; }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Constants.PAREN_BEGIN);
        sb.append(Constants.RECORD_KEYWORD).append(" ");
        sb.append(name == null ? "_" : name);

        for (String field : properties.keySet()) {
            sb.append(" ").append(Constants.SQUARE_BEGIN);
            sb.append(field).append(" ");
            sb.append(properties.lookupLocal(field));
            sb.append(Constants.SQUARE_END);
        }

        sb.append(Constants.PAREN_END);
        return sb.toString();
    }

}
