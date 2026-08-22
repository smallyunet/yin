package org.yinwang.yin.type;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;

import java.util.ArrayList;
import java.util.List;

/** Static signatures for immutable Dict and Set operations. */
public final class CollectionPrimitiveTypes {
    private CollectionPrimitiveTypes() { }

    public static PrimitiveFunctionType dict() {
        return new PrimitiveFunctionType("dict", -1, (arguments, location) -> {
            if (arguments.size() % 2 != 0) {
                Util.abort(location, "dict expects key/value pairs, got " + arguments.size() + " arguments");
            }
            List<YinType> keys = new ArrayList<>();
            List<YinType> values = new ArrayList<>();
            for (int i = 0; i < arguments.size(); i += 2) {
                comparable(arguments.get(i), location, "dict key");
                keys.add(arguments.get(i));
                values.add(arguments.get(i + 1));
            }
            return new DictType(unionOrNever(keys), unionOrNever(values));
        });
    }

    public static PrimitiveFunctionType dictGet() {
        return new PrimitiveFunctionType("dict/get", 2, (arguments, location) -> {
            DictType dictionary = dictionary(arguments.get(0), location, "dict/get");
            comparable(arguments.get(1), location, "dict/get key");
            compatible(arguments.get(1), dictionary.key(), location, "dict/get key");
            return new OptionType(dictionary.value());
        });
    }

    public static PrimitiveFunctionType dictPut() {
        return new PrimitiveFunctionType("dict/put", 3, (arguments, location) -> {
            DictType dictionary = dictionary(arguments.get(0), location, "dict/put");
            comparable(arguments.get(1), location, "dict/put key");
            return new DictType(UnionType.union(dictionary.key(), arguments.get(1)),
                    UnionType.union(dictionary.value(), arguments.get(2)));
        });
    }

    public static PrimitiveFunctionType dictRemove() {
        return new PrimitiveFunctionType("dict/remove", 2, (arguments, location) -> {
            DictType dictionary = dictionary(arguments.get(0), location, "dict/remove");
            comparable(arguments.get(1), location, "dict/remove key");
            compatible(arguments.get(1), dictionary.key(), location, "dict/remove key");
            return dictionary;
        });
    }

    public static PrimitiveFunctionType dictKeys() {
        return new PrimitiveFunctionType("dict/keys", 1, (arguments, location) ->
                new HomogeneousVectorType(dictionary(arguments.get(0), location, "dict/keys").key()));
    }

    public static PrimitiveFunctionType dictValues() {
        return new PrimitiveFunctionType("dict/values", 1, (arguments, location) ->
                new HomogeneousVectorType(dictionary(arguments.get(0), location, "dict/values").value()));
    }

    public static PrimitiveFunctionType dictContainsKey() {
        return new PrimitiveFunctionType("dict/contains-key", 2, (arguments, location) -> {
            DictType dictionary = dictionary(arguments.get(0), location, "dict/contains-key");
            comparable(arguments.get(1), location, "dict/contains-key key");
            compatible(arguments.get(1), dictionary.key(), location, "dict/contains-key key");
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType dictSize() {
        return new PrimitiveFunctionType("dict/size", 1, (arguments, location) -> {
            dictionary(arguments.get(0), location, "dict/size");
            return Types.INT;
        });
    }

    public static PrimitiveFunctionType set() {
        return new PrimitiveFunctionType("set", -1, (arguments, location) -> {
            arguments.forEach(argument -> comparable(argument, location, "set"));
            return new SetType(unionOrNever(arguments));
        });
    }

    public static PrimitiveFunctionType setAdd() {
        return new PrimitiveFunctionType("set/add", 2, (arguments, location) -> {
            SetType set = set(arguments.get(0), location, "set/add");
            comparable(arguments.get(1), location, "set/add value");
            return new SetType(UnionType.union(set.element(), arguments.get(1)));
        });
    }

    public static PrimitiveFunctionType setRemove() {
        return new PrimitiveFunctionType("set/remove", 2, (arguments, location) -> {
            SetType set = set(arguments.get(0), location, "set/remove");
            comparable(arguments.get(1), location, "set/remove value");
            compatible(arguments.get(1), set.element(), location, "set/remove value");
            return set;
        });
    }

    public static PrimitiveFunctionType setContains() {
        return new PrimitiveFunctionType("set/contains", 2, (arguments, location) -> {
            SetType set = set(arguments.get(0), location, "set/contains");
            comparable(arguments.get(1), location, "set/contains value");
            compatible(arguments.get(1), set.element(), location, "set/contains value");
            return Types.BOOL;
        });
    }

    public static PrimitiveFunctionType setValues() {
        return new PrimitiveFunctionType("set/values", 1, (arguments, location) ->
                new HomogeneousVectorType(set(arguments.get(0), location, "set/values").element()));
    }

    public static PrimitiveFunctionType setSize() {
        return new PrimitiveFunctionType("set/size", 1, (arguments, location) -> {
            set(arguments.get(0), location, "set/size");
            return Types.INT;
        });
    }

    public static PrimitiveFunctionType setUnion() {
        return setBinary("set/union", false);
    }

    public static PrimitiveFunctionType setIntersection() {
        return setBinary("set/intersection", false);
    }

    public static PrimitiveFunctionType setDifference() {
        return setBinary("set/difference", true);
    }

    private static PrimitiveFunctionType setBinary(String name, boolean preserveLeft) {
        return new PrimitiveFunctionType(name, 2, (arguments, location) -> {
            SetType left = set(arguments.get(0), location, name);
            SetType right = set(arguments.get(1), location, name);
            return preserveLeft ? left : new SetType(UnionType.union(left.element(), right.element()));
        });
    }

    private static DictType dictionary(YinType type, Node location, String operation) {
        if (type instanceof DictType dictionary) return dictionary;
        if (type instanceof UnionType union) {
            List<YinType> keys = new ArrayList<>();
            List<YinType> values = new ArrayList<>();
            for (YinType member : union.members()) {
                DictType dictionary = dictionary(member, location, operation);
                keys.add(dictionary.key());
                values.add(dictionary.value());
            }
            return new DictType(unionOrNever(keys), unionOrNever(values));
        }
        if (type instanceof AnyType) return new DictType(Types.ANY, Types.ANY);
        Util.abort(location, operation + " requires a Dict, got: " + type);
        return new DictType(Types.ANY, Types.ANY);
    }

    private static SetType set(YinType type, Node location, String operation) {
        if (type instanceof SetType set) return set;
        if (type instanceof UnionType union) {
            return new SetType(unionOrNever(union.members().stream()
                    .map(member -> set(member, location, operation).element()).toList()));
        }
        if (type instanceof AnyType) return new SetType(Types.ANY);
        Util.abort(location, operation + " requires a Set, got: " + type);
        return new SetType(Types.ANY);
    }

    private static void compatible(YinType actual, YinType expected, Node location, String operation) {
        if (!(expected instanceof NeverType) && !Types.overlaps(actual, expected)) {
            Util.abort(location, operation + " is incompatible with " + expected + ", got: " + actual);
        }
    }

    private static void comparable(YinType type, Node location, String operation) {
        if (!Types.structurallyComparable(type)) {
            Util.abort(location, operation + " requires a structurally comparable type, got: " + type);
        }
    }

    private static YinType unionOrNever(java.util.Collection<YinType> types) {
        return types.isEmpty() ? Types.NEVER : UnionType.union(types);
    }
}
