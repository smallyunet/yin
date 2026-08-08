package org.yinwang.yin.type;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.Call;
import org.yinwang.yin.ast.IntNum;
import org.yinwang.yin.Util;

import java.util.ArrayList;
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

    public static PrimitiveFunctionType vectorLength() {
        return new PrimitiveFunctionType("length", 1, (arguments, location) -> {
            requireVectorLike(arguments.get(0), location, "length");
            return Types.INT;
        });
    }

    public static PrimitiveFunctionType vectorAt() {
        return new PrimitiveFunctionType("at", 2, (arguments, location) -> {
            YinType target = arguments.get(0);
            YinType index = arguments.get(1);
            if (!(index instanceof IntType) && !(index instanceof AnyType)) {
                Util.abort(location, "at index must be Int, got: " + index);
            }
            if (target instanceof AnyType || index instanceof AnyType) {
                return Types.ANY;
            }

            List<VectorType> vectors = vectorAlternatives(target, location, "at");
            Integer literalIndex = literalIndex(location);
            List<YinType> possible = new ArrayList<>();
            for (VectorType vector : vectors) {
                if (literalIndex != null) {
                    if (literalIndex < 0 || literalIndex >= vector.elements().size()) {
                        Util.abort(location, "vector index out of bounds: " + literalIndex +
                                " for length " + vector.elements().size());
                    }
                    possible.add(vector.elements().get(literalIndex));
                } else {
                    if (vector.elements().isEmpty()) {
                        Util.abort(location, "at cannot index an empty vector");
                    }
                    possible.addAll(vector.elements());
                }
            }
            return UnionType.union(possible);
        });
    }

    public static PrimitiveFunctionType vectorAppend() {
        return new PrimitiveFunctionType("append", 2, (arguments, location) -> {
            YinType left = arguments.get(0);
            YinType right = arguments.get(1);
            if (left instanceof AnyType || right instanceof AnyType) {
                return Types.ANY;
            }
            List<VectorType> leftVectors = vectorAlternatives(left, location, "append");
            List<VectorType> rightVectors = vectorAlternatives(right, location, "append");
            List<YinType> results = new ArrayList<>();
            for (VectorType leftVector : leftVectors) {
                for (VectorType rightVector : rightVectors) {
                    List<YinType> elements = new ArrayList<>(leftVector.elements());
                    elements.addAll(rightVector.elements());
                    results.add(new VectorType(elements));
                }
            }
            return UnionType.union(results);
        });
    }

    private static void requireVectorLike(YinType type, Node location, String operation) {
        if (type instanceof AnyType || type instanceof VectorType) {
            return;
        }
        if (type instanceof UnionType union) {
            for (YinType member : union.members()) {
                requireVectorLike(member, location, operation);
            }
            return;
        }
        Util.abort(location, operation + " requires a vector, got: " + type);
    }

    private static List<VectorType> vectorAlternatives(YinType type, Node location, String operation) {
        List<VectorType> vectors = new ArrayList<>();
        collectVectorAlternatives(type, vectors, location, operation);
        return vectors;
    }

    private static void collectVectorAlternatives(
            YinType type, List<VectorType> vectors, Node location, String operation) {
        if (type instanceof VectorType vector) {
            vectors.add(vector);
        } else if (type instanceof UnionType union) {
            for (YinType member : union.members()) {
                collectVectorAlternatives(member, vectors, location, operation);
            }
        } else {
            Util.abort(location, operation + " requires a vector, got: " + type);
        }
    }

    private static Integer literalIndex(Node location) {
        if (location instanceof Call call && call.args.positional.size() == 2
                && call.args.positional.get(1) instanceof IntNum index) {
            return index.value;
        }
        return null;
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
