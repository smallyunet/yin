package org.yinwang.yin.type;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.Call;
import org.yinwang.yin.ast.IntNum;
import org.yinwang.yin.Util;
import org.yinwang.yin.CallableSupport;

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

    public static PrimitiveFunctionType equality() {
        return new PrimitiveFunctionType("=", 2, (arguments, location) -> {
            if (!Types.overlaps(arguments.get(0), arguments.get(1))) {
                Util.abort(location, "values cannot be compared for equality: "
                        + arguments.get(0) + ", " + arguments.get(1));
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

            if (containsHomogeneousVector(target)) {
                return Types.vectorElement(target);
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
            if (containsHomogeneousVector(left) || containsHomogeneousVector(right)) {
                YinType leftElement = Types.vectorElement(left);
                YinType rightElement = Types.vectorElement(right);
                if (leftElement == null || rightElement == null) {
                    Util.abort(location, "append requires vectors, got: " + left + ", " + right);
                }
                return new HomogeneousVectorType(UnionType.union(leftElement, rightElement));
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
        if (type instanceof AnyType || type instanceof VectorType
                || type instanceof HomogeneousVectorType) {
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

    private static boolean containsHomogeneousVector(YinType type) {
        if (type instanceof HomogeneousVectorType) {
            return true;
        }
        return type instanceof UnionType union
                && union.members().stream().anyMatch(PrimitiveFunctionType::containsHomogeneousVector);
    }

    public static PrimitiveFunctionType vectorType() {
        return new PrimitiveFunctionType("Vector", 1,
                (arguments, location) -> new HomogeneousVectorType(arguments.get(0)));
    }

    public static PrimitiveFunctionType functionType() {
        return new PrimitiveFunctionType("Fn", 2, (arguments, location) -> {
            if (!(arguments.get(0) instanceof VectorType parameters)) {
                Util.abort(location, "Fn parameter types must be written as a vector, got: "
                        + arguments.get(0));
                return Types.VOID;
            }
            return new DeclaredFunctionType(parameters.elements(), arguments.get(1));
        });
    }

    public static PrimitiveFunctionType vectorMap() {
        return new PrimitiveFunctionType("map", 2, (arguments, location) ->
                mapVector(arguments.get(0), arguments.get(1), location));
    }

    public static PrimitiveFunctionType vectorFilter() {
        return new PrimitiveFunctionType("filter", 2, (arguments, location) ->
                filterVector(arguments.get(0), arguments.get(1), location));
    }

    public static PrimitiveFunctionType vectorFold() {
        return new PrimitiveFunctionType("fold", 3, (arguments, location) -> {
            YinType vector = arguments.get(0);
            YinType initial = arguments.get(1);
            YinType accumulator = foldAccumulator(arguments.get(2), initial, location);
            requireVectorLike(vector, location, "fold");
            if (vector instanceof AnyType) {
                requireCallableArity(arguments.get(2), 2, location, "fold");
                return Types.ANY;
            }
            YinType element = Types.vectorElement(vector);
            if (element instanceof AnyType && vector instanceof VectorType exact
                    && exact.elements().isEmpty()) {
                return accumulator;
            }
            YinType result = CallableSupport.apply(
                    arguments.get(2), List.of(accumulator, element), location);
            if (!Types.subtype(result, accumulator)) {
                Util.abort(location, "fold function must preserve accumulator type. expected: "
                        + accumulator + ", actual: " + result);
            }
            return accumulator;
        });
    }

    public static PrimitiveFunctionType vectorRange() {
        return new PrimitiveFunctionType("range", 2, (arguments, location) -> {
            require(arguments.get(0), Types.INT, location, "range");
            require(arguments.get(1), Types.INT, location, "range");
            return new HomogeneousVectorType(Types.INT);
        });
    }

    public static PrimitiveFunctionType vectorSlice() {
        return new PrimitiveFunctionType("slice", 3, (arguments, location) -> {
            requireVectorLike(arguments.get(0), location, "slice");
            require(arguments.get(1), Types.INT, location, "slice");
            require(arguments.get(2), Types.INT, location, "slice");
            if (arguments.get(0) instanceof AnyType) {
                return Types.ANY;
            }
            if (arguments.get(0) instanceof VectorType exact && location instanceof Call call
                    && call.args.positional.get(1) instanceof IntNum start
                    && call.args.positional.get(2) instanceof IntNum end) {
                if (start.value < 0 || end.value < start.value || end.value > exact.elements().size()) {
                    Util.abort(location, "invalid slice bounds: " + start.value + ", " + end.value
                            + " for length " + exact.elements().size());
                }
                return new VectorType(exact.elements().subList(start.value, end.value));
            }
            return new HomogeneousVectorType(Types.vectorElement(arguments.get(0)));
        });
    }

    public static PrimitiveFunctionType vectorReverse() {
        return new PrimitiveFunctionType("reverse", 1, (arguments, location) -> {
            requireVectorLike(arguments.get(0), location, "reverse");
            if (arguments.get(0) instanceof VectorType exact) {
                List<YinType> reversed = new ArrayList<>(exact.elements());
                java.util.Collections.reverse(reversed);
                return new VectorType(reversed);
            }
            return arguments.get(0);
        });
    }

    public static PrimitiveFunctionType vectorContains() {
        return new PrimitiveFunctionType("contains", 2, (arguments, location) -> {
            requireVectorLike(arguments.get(0), location, "contains");
            if (arguments.get(0) instanceof AnyType) {
                return Types.BOOL;
            }
            YinType element = Types.vectorElement(arguments.get(0));
            if (!(element instanceof AnyType) && !Types.overlaps(element, arguments.get(1))) {
                Util.abort(location, "contains value is incompatible with vector element type: "
                        + arguments.get(1) + " versus " + element);
            }
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType stringUnary(String name, YinType result) {
        return new PrimitiveFunctionType(name, 1, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, name);
            return result;
        });
    }

    public static PrimitiveFunctionType stringBinary(String name, YinType result) {
        return new PrimitiveFunctionType(name, 2, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, name);
            require(arguments.get(1), Types.STRING, location, name);
            return result;
        });
    }

    public static PrimitiveFunctionType substring() {
        return new PrimitiveFunctionType("substring", 3, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, "substring");
            require(arguments.get(1), Types.INT, location, "substring");
            require(arguments.get(2), Types.INT, location, "substring");
            return Types.STRING;
        });
    }

    public static PrimitiveFunctionType split() {
        return new PrimitiveFunctionType("split", 2, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, "split");
            require(arguments.get(1), Types.STRING, location, "split");
            return new HomogeneousVectorType(Types.STRING);
        });
    }

    public static PrimitiveFunctionType join() {
        return new PrimitiveFunctionType("join", 2, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, "join");
            requireVectorLike(arguments.get(1), location, "join");
            if (arguments.get(1) instanceof AnyType) {
                return Types.STRING;
            }
            YinType element = Types.vectorElement(arguments.get(1));
            if (!(element instanceof AnyType) && !Types.subtype(element, Types.STRING)) {
                Util.abort(location, "join requires a vector of String, got: " + arguments.get(1));
            }
            return Types.STRING;
        });
    }

    public static PrimitiveFunctionType toStringType() {
        return new PrimitiveFunctionType("to-string", 1, (arguments, location) -> Types.STRING);
    }

    public static PrimitiveFunctionType parseNumber(String name, YinType number) {
        return new PrimitiveFunctionType(name, 1, (arguments, location) -> {
            require(arguments.get(0), Types.STRING, location, name);
            return UnionType.union(number, Types.BOOL);
        });
    }

    public static PrimitiveFunctionType readAll() {
        return new PrimitiveFunctionType("read-all", 0, (arguments, location) -> Types.STRING);
    }

    private static YinType mapVector(YinType vector, YinType function, Node location) {
        if (vector instanceof VectorType exact) {
            List<YinType> output = new ArrayList<>();
            if (exact.elements().isEmpty()) {
                requireCallableArity(function, 1, location, "map");
            }
            for (YinType element : exact.elements()) {
                output.add(CallableSupport.apply(function, List.of(element), location));
            }
            return new VectorType(output);
        }
        if (vector instanceof HomogeneousVectorType homogeneous) {
            return new HomogeneousVectorType(
                    CallableSupport.apply(function, List.of(homogeneous.element()), location));
        }
        if (vector instanceof UnionType union) {
            return UnionType.union(union.members().stream()
                    .map(member -> mapVector(member, function, location)).toList());
        }
        if (vector instanceof AnyType) {
            requireCallableArity(function, 1, location, "map");
            return Types.ANY;
        }
        Util.abort(location, "map requires a vector, got: " + vector);
        return Types.VOID;
    }

    private static YinType filterVector(YinType vector, YinType function, Node location) {
        requireVectorLike(vector, location, "filter");
        if (vector instanceof AnyType) {
            requireCallableArity(function, 1, location, "filter");
            return Types.ANY;
        }
        YinType element = Types.vectorElement(vector);
        if (vector instanceof VectorType exact && exact.elements().isEmpty()) {
            requireCallableArity(function, 1, location, "filter");
            return new VectorType(List.of());
        }
        YinType result = CallableSupport.apply(function, List.of(element), location);
        require(result, Types.BOOL, location, "filter predicate");
        return new HomogeneousVectorType(element);
    }

    private static void require(YinType actual, YinType expected, Node location, String operation) {
        if (!Types.subtype(actual, expected)) {
            Util.abort(location, operation + " expected " + expected + ", got: " + actual);
        }
    }

    private static void requireCallableArity(
            YinType function, int arity, Node location, String operation) {
        int actual;
        if (function instanceof DeclaredFunctionType declared) {
            actual = declared.parameters().size();
        } else if (function instanceof FunctionType source) {
            actual = source.function.params.size();
        } else if (function instanceof AnyType) {
            return;
        } else {
            Util.abort(location, operation + " requires a function, got: " + function);
            return;
        }
        if (actual != arity) {
            Util.abort(location, operation + " function must accept " + arity
                    + " arguments, got: " + actual);
        }
    }

    private static YinType foldAccumulator(
            YinType function, YinType initial, Node location) {
        DeclaredFunctionType signature = null;
        if (function instanceof DeclaredFunctionType declared) {
            signature = declared;
        } else if (function instanceof FunctionType source) {
            signature = source.declaredSignature();
        }
        if (signature == null || signature.parameters().isEmpty()) {
            return initial;
        }
        YinType expected = signature.parameters().get(0);
        if (!Types.subtype(initial, expected)) {
            Util.abort(location, "fold initial value is incompatible with accumulator type. expected: "
                    + expected + ", actual: " + initial);
        }
        return expected;
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
