package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.type.RecordType;
import org.yinwang.yin.type.VariantType;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.RecordConstructor;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.VariantDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Defines a closed tagged union and one constructor for every case. */
public final class VariantDef extends Node {
    public final Name name;
    public final Map<String, Scope<Object>> cases;

    public VariantDef(Name name, Map<String, Scope<Object>> cases,
                      String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.name = name;
        this.cases = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cases));
    }

    @Override public Value interp(Scope<Value> scope) {
        for (Map.Entry<String, Scope<Object>> entry : cases.entrySet()) {
            Scope<Value> fields = Declare.evalProperties(entry.getValue(), scope);
            scope.putValue(entry.getKey(), new RecordConstructor(
                    entry.getKey(), this, fields, Set.of(entry.getKey()), name.id));
        }
        VariantDescriptor descriptor = new VariantDescriptor(this);
        scope.putValue(name.id, descriptor);
        return descriptor;
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        Map<String, RecordType> alternatives = new LinkedHashMap<>();
        for (Map.Entry<String, Scope<Object>> entry : cases.entrySet()) {
            Scope<YinType> fields = Declare.typecheckProperties(entry.getValue(), scope);
            RecordType type = new RecordType(entry.getKey(), this, fields,
                    Set.of(entry.getKey()), name.id);
            alternatives.put(entry.getKey(), type);
            scope.putValue(entry.getKey(), type);
        }
        VariantType type = new VariantType(name.id, alternatives);
        scope.putValue(name.id, type);
        return type;
    }

    @Override public String toString() {
        StringBuilder text = new StringBuilder("(").append(Constants.VARIANT_KEYWORD)
                .append(" ").append(name);
        for (Map.Entry<String, Scope<Object>> entry : cases.entrySet()) {
            text.append(" [").append(entry.getKey());
            for (String field : entry.getValue().keySet()) {
                text.append(" [").append(field).append(" ")
                        .append(entry.getValue().lookupPropertyLocal(field, "type")).append("]");
            }
            text.append("]");
        }
        return text.append(")").toString();
    }
}
