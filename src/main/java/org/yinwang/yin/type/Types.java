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

    public static boolean subtype(YinType actual, YinType expected, boolean returnPosition) {
        if (actual == null || expected == null) {
            return false;
        }
        if (!returnPosition && expected instanceof AnyType) {
            return true;
        }
        if (actual instanceof UnionType union) {
            for (YinType member : union.members()) {
                if (!subtype(member, expected, false)) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof UnionType union) {
            return union.members().contains(actual);
        }
        return actual.equals(expected);
    }
}
