package org.yinwang.yin.type;

public final class Types {
    public static final YinType ANY = new AnyType();
    public static final YinType BOOL = new BoolType();
    public static final YinType INT = new IntType();
    public static final YinType FLOAT = new FloatType();
    public static final YinType STRING = new StringType();
    public static final YinType VOID = new VoidType();

    private Types() {
    }

    public static boolean numeric(YinType type) {
        return type instanceof IntType || type instanceof FloatType;
    }

    public static YinType arithmetic(YinType left, YinType right) {
        if (!numeric(left) || !numeric(right)) {
            return null;
        }
        return left instanceof FloatType || right instanceof FloatType ? FLOAT : INT;
    }

    public static boolean subtype(YinType actual, YinType expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected instanceof AnyType) {
            return true;
        }
        if (actual instanceof UnionType union) {
            for (YinType member : union.members()) {
                if (!subtype(member, expected)) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof UnionType union) {
            return union.members().stream().anyMatch(member -> subtype(actual, member));
        }
        if (actual instanceof VectorType actualVector && expected instanceof VectorType expectedVector) {
            if (actualVector.elements().size() != expectedVector.elements().size()) {
                return false;
            }
            for (int i = 0; i < actualVector.elements().size(); i++) {
                if (!subtype(actualVector.elements().get(i), expectedVector.elements().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (actual instanceof RecordValueType actualRecord && expected instanceof RecordType expectedRecord) {
            return expectedRecord.name != null && actualRecord.nominalTypes().contains(expectedRecord.name);
        }
        if (actual instanceof RecordValueType actualRecord && expected instanceof RecordValueType expectedRecord) {
            if (actualRecord.name != null || expectedRecord.name != null) {
                return actualRecord.name != null && actualRecord.name.equals(expectedRecord.name);
            }
            return sameFields(actualRecord, expectedRecord);
        }
        if (actual instanceof RecordType actualRecord && expected instanceof RecordType expectedRecord) {
            return expectedRecord.name != null && actualRecord.nominalTypes().contains(expectedRecord.name);
        }
        return equivalent(actual, expected);
    }

    public static boolean equivalent(YinType left, YinType right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        if (left instanceof VectorType leftVector && right instanceof VectorType rightVector) {
            if (leftVector.elements().size() != rightVector.elements().size()) {
                return false;
            }
            for (int i = 0; i < leftVector.elements().size(); i++) {
                if (!equivalent(leftVector.elements().get(i), rightVector.elements().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof UnionType leftUnion && right instanceof UnionType rightUnion) {
            return leftUnion.members().size() == rightUnion.members().size()
                    && leftUnion.members().stream().allMatch(leftMember ->
                    rightUnion.members().stream().anyMatch(rightMember -> equivalent(leftMember, rightMember)));
        }
        if (left instanceof RecordType leftRecord && right instanceof RecordType rightRecord) {
            return leftRecord.name != null && leftRecord.name.equals(rightRecord.name);
        }
        if (left instanceof RecordValueType leftRecord && right instanceof RecordValueType rightRecord) {
            if (leftRecord.name != null || rightRecord.name != null) {
                return leftRecord.name != null && leftRecord.name.equals(rightRecord.name);
            }
            return sameFields(leftRecord, rightRecord);
        }
        return left instanceof AnyType
                || left instanceof BoolType
                || left instanceof FloatType
                || left instanceof IntType
                || left instanceof StringType
                || left instanceof VoidType;
    }

    private static boolean sameFields(RecordValueType left, RecordValueType right) {
        if (!left.fields.keySet().equals(right.fields.keySet())) {
            return false;
        }
        for (String field : left.fields.keySet()) {
            if (!equivalent(left.fields.lookupLocal(field), right.fields.lookupLocal(field))) {
                return false;
            }
        }
        return true;
    }
}
