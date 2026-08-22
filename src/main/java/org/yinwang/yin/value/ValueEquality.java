package org.yinwang.yin.value;

import java.util.List;

/** Language-level structural equality for immutable Yin values. */
public final class ValueEquality {
    private ValueEquality() {
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
