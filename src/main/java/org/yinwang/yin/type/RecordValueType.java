package org.yinwang.yin.type;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;

import java.util.LinkedHashSet;
import java.util.Set;

/** Static type of an instantiated record value. */
public final class RecordValueType extends YinType {
    public final String name;
    public final Scope<YinType> fields;
    private final Set<String> nominalTypes;

    public RecordValueType(String name, Scope<YinType> fields, Set<String> nominalTypes) {
        this.name = name;
        this.fields = fields.copy();
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
        for (String field : fields.keySet()) {
            result.append(" ").append(Constants.SQUARE_BEGIN)
                    .append(field).append(" ").append(fields.lookupLocal(field))
                    .append(Constants.SQUARE_END);
        }
        return result.append(Constants.PAREN_END).toString();
    }
}
