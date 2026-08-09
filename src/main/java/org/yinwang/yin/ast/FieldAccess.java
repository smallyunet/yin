package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.AnyType;
import org.yinwang.yin.type.RecordValueType;
import org.yinwang.yin.type.RecordType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.UnionType;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.RecordValue;
import org.yinwang.yin.value.Value;

import java.util.ArrayList;
import java.util.List;

/** Reads one immutable field from a record value. */
public final class FieldAccess extends Node {
    public final Node target;
    public final Keyword field;

    public FieldAccess(Node target, Keyword field,
                       String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.target = target;
        this.field = field;
    }

    @Override
    public Value interp(Scope<Value> scope) {
        Value value = target.interp(scope);
        if (!(value instanceof RecordValue)) {
            Util.abort(target, "field access requires a record value, got: " + value);
        }
        RecordValue record = (RecordValue) value;
        Value result = record.properties.lookupLocal(field.id);
        if (result == null) {
            Util.abort(field, "record has no field: " + field.id);
        }
        return result;
    }

    @Override
    public YinType typecheck(Scope<YinType> scope) {
        return fieldType(target.typecheck(scope));
    }

    private YinType fieldType(YinType targetType) {
        if (targetType instanceof AnyType) {
            return Types.ANY;
        }
        if (targetType instanceof RecordValueType record) {
            YinType result = record.fields.lookupLocal(field.id);
            if (result == null) {
                Util.abort(field, "record type has no field: " + field.id);
            }
            return result;
        }
        if (targetType instanceof RecordType record) {
            YinType result = record.properties.lookupLocalType(field.id);
            if (result == null) Util.abort(field, "record type has no field: " + field.id);
            return result;
        }
        if (targetType instanceof UnionType union) {
            List<YinType> fieldTypes = new ArrayList<>();
            for (YinType member : union.members()) {
                fieldTypes.add(fieldType(member));
            }
            return UnionType.union(fieldTypes);
        }
        Util.abort(target, "field access requires a record type, got: " + targetType);
        return Types.VOID;
    }

    @Override
    public String toString() {
        return Constants.PAREN_BEGIN + Constants.FIELD_KEYWORD + " " + target + " " + field
                + Constants.PAREN_END;
    }
}
