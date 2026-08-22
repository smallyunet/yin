package org.yinwang.yin.value;

import java.util.List;

/** Language-level structural equality for immutable Yin values. */
public final class ValueEquality {
    private ValueEquality() {
    }

    /** Whether a value has total structural equality and can safely be a key or set member. */
    public static boolean comparable(Value value) {
        if (value instanceof IntValue || value instanceof FloatValue
                || value instanceof BoolValue || value instanceof StringValue
                || value instanceof VoidValue) return true;
        if (value instanceof Vector vector) return vector.values().stream().allMatch(ValueEquality::comparable);
        if (value instanceof OptionValue option) return !option.present() || comparable(option.value());
        if (value instanceof ResultValue result) return comparable(result.payload());
        if (value instanceof RecordValue record) {
            return record.properties.keySet().stream()
                    .allMatch(field -> comparable(record.properties.lookupLocal(field)));
        }
        if (value instanceof DictValue dictionary) {
            return dictionary.entries().stream()
                    .allMatch(entry -> comparable(entry.key()) && comparable(entry.value()));
        }
        if (value instanceof SetValue set) return set.values().stream().allMatch(ValueEquality::comparable);
        return false;
    }

    public static boolean equal(Value left, Value right) {
        if (left instanceof IntValue leftInt && right instanceof IntValue rightInt) {
            return leftInt.value == rightInt.value;
        }
        if (left instanceof FloatValue leftFloat && right instanceof FloatValue rightFloat) {
            return leftFloat.value == rightFloat.value;
        }
        if (left instanceof FloatValue leftFloat && right instanceof IntValue rightInt) {
            return leftFloat.value == rightInt.value;
        }
        if (left instanceof IntValue leftInt && right instanceof FloatValue rightFloat) {
            return leftInt.value == rightFloat.value;
        }
        if (left instanceof BoolValue leftBool && right instanceof BoolValue rightBool) {
            return leftBool.value == rightBool.value;
        }
        if (left instanceof StringValue leftString && right instanceof StringValue rightString) {
            return leftString.value.equals(rightString.value);
        }
        if (left instanceof VoidValue && right instanceof VoidValue) {
            return true;
        }
        if (left instanceof Vector leftVector && right instanceof Vector rightVector) {
            List<Value> leftValues = leftVector.values();
            List<Value> rightValues = rightVector.values();
            if (leftValues.size() != rightValues.size()) {
                return false;
            }
            for (int i = 0; i < leftValues.size(); i++) {
                if (!equal(leftValues.get(i), rightValues.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof ResultValue leftResult && right instanceof ResultValue rightResult) {
            return leftResult.tag() == rightResult.tag()
                    && equal(leftResult.payload(), rightResult.payload());
        }
        if (left instanceof OptionValue leftOption && right instanceof OptionValue rightOption) {
            return leftOption.present() == rightOption.present()
                    && (!leftOption.present() || equal(leftOption.value(), rightOption.value()));
        }
        if (left instanceof DictValue leftDict && right instanceof DictValue rightDict) {
            if (leftDict.size() != rightDict.size()) return false;
            for (DictValue.Entry entry : leftDict.entries()) {
                OptionValue rightValue = rightDict.get(entry.key());
                if (!rightValue.present() || !equal(entry.value(), rightValue.value())) return false;
            }
            return true;
        }
        if (left instanceof SetValue leftSet && right instanceof SetValue rightSet) {
            return leftSet.size() == rightSet.size()
                    && leftSet.values().stream().allMatch(rightSet::contains);
        }
        if (left instanceof RecordValue leftRecord && right instanceof RecordValue rightRecord) {
            if (!java.util.Objects.equals(leftRecord.identity(), rightRecord.identity())
                    || !leftRecord.properties.keySet().equals(rightRecord.properties.keySet())) {
                return false;
            }
            for (String field : leftRecord.properties.keySet()) {
                if (!equal(leftRecord.properties.lookupLocal(field), rightRecord.properties.lookupLocal(field))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
