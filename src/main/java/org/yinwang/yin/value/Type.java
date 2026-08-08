package org.yinwang.yin.value;

public class Type {

    public static boolean numeric(Value value) {
        return value instanceof IntType || value instanceof FloatType;
    }

    public static Value arithmetic(Value left, Value right) {
        if (!numeric(left) || !numeric(right)) {
            return null;
        }
        return left instanceof FloatType || right instanceof FloatType ? FLOAT : INT;
    }

    public static boolean subtype(Value type1, Value type2, boolean ret) {
        if (type1 == null || type2 == null) {
            return false;
        }
        if (!ret && type2 instanceof AnyType) {
            return true;
        }

        if (type1 instanceof UnionType) {
            for (Value t : ((UnionType) type1).values) {
                if (!subtype(t, type2, false)) {
                    return false;
                }
            }
            return true;
        } else if (type2 instanceof UnionType) {
            return ((UnionType) type2).values.contains(type1);
        } else {
            return type1.equals(type2);
        }
    }


    public static final Value BOOL = new BoolType();
    public static final Value INT = new IntType();
    public static final Value FLOAT = new FloatType();
    public static final Value STRING = new StringType();

}
