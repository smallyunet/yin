package org.yinwang.yin.type;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.Util;

import java.util.List;

public final class PrimitiveFunctionType extends YinType {
    @FunctionalInterface
    public interface Signature {
        YinType apply(List<YinType> arguments, Node location);
    }

    public final String name;
    public final int arity;
    private final Signature signature;

    public PrimitiveFunctionType(String name, int arity, Signature signature) {
        this.name = name;
        this.arity = arity;
        this.signature = signature;
    }

    public YinType apply(List<YinType> arguments, Node location) {
        return signature.apply(arguments, location);
    }

    public static PrimitiveFunctionType arithmetic(String name) {
        return new PrimitiveFunctionType(name, 2, (arguments, location) -> {
            YinType result = Types.arithmetic(arguments.get(0), arguments.get(1));
            if (result == null) {
                Util.abort(location, "incorrect argument types for " + name + ": " +
                        arguments.get(0) + ", " + arguments.get(1));
            }
            return result;
        });
    }

    public static PrimitiveFunctionType numericComparison(String name) {
        return new PrimitiveFunctionType(name, 2, (arguments, location) -> {
            if (!Types.numeric(arguments.get(0)) || !Types.numeric(arguments.get(1))) {
                Util.abort(location, "incorrect argument types for " + name + ": " +
                        arguments.get(0) + ", " + arguments.get(1));
            }
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType booleanBinary(String name) {
        return new PrimitiveFunctionType(name, 2, (arguments, location) -> {
            if (!(arguments.get(0) instanceof BoolType) || !(arguments.get(1) instanceof BoolType)) {
                Util.abort(location, "incorrect argument types for " + name + ": " +
                        arguments.get(0) + ", " + arguments.get(1));
            }
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType booleanUnary(String name) {
        return new PrimitiveFunctionType(name, 1, (arguments, location) -> {
            if (!(arguments.get(0) instanceof BoolType)) {
                Util.abort(location, "incorrect argument types for " + name + ": " + arguments.get(0));
            }
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType print() {
        return new PrimitiveFunctionType("print", -1, (arguments, location) -> Types.VOID);
    }

    public static PrimitiveFunctionType union() {
        return new PrimitiveFunctionType("U", -1, (arguments, location) -> {
            if (arguments.isEmpty()) {
                Util.abort(location, "U expects at least one type");
            }
            return UnionType.union(arguments);
        });
    }

    @Override
    public String toString() {
        return name;
    }
}
