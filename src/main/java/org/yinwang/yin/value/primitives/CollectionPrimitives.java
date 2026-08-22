package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.DictValue;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.SetValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.ValueEquality;
import org.yinwang.yin.value.Vector;

import java.util.ArrayList;
import java.util.List;

/** Runtime operations for insertion-ordered immutable dictionaries and sets. */
public final class CollectionPrimitives {
    private CollectionPrimitives() { }

    private static DictValue dictionary(Value value, Node location, String operation) {
        if (value instanceof DictValue dictionary) {
            return dictionary;
        }
        Util.abort(location, operation + " requires a Dict, got: " + value);
        return new DictValue(List.of());
    }

    private static SetValue set(Value value, Node location, String operation) {
        if (value instanceof SetValue set) {
            return set;
        }
        Util.abort(location, operation + " requires a Set, got: " + value);
        return new SetValue(List.of());
    }

    private static void comparable(Value value, Node location, String operation) {
        if (!ValueEquality.comparable(value)) {
            Util.abort(location, operation + " requires structurally comparable values, got: " + value);
        }
    }

    public static final class Dict extends PrimFun {
        public Dict() { super("dict", -1); }

        @Override public Value apply(List<Value> arguments, Node location) {
            if (arguments.size() % 2 != 0) {
                Util.abort(location, "dict expects key/value pairs, got " + arguments.size() + " arguments");
            }
            List<DictValue.Entry> entries = new ArrayList<>();
            for (int i = 0; i < arguments.size(); i += 2) {
                comparable(arguments.get(i), location, "dict key");
                entries.add(new DictValue.Entry(arguments.get(i), arguments.get(i + 1)));
            }
            return new DictValue(entries);
        }
    }

    public static final class DictGet extends PrimFun {
        public DictGet() { super("dict/get", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "dict/get key");
            return dictionary(arguments.get(0), location, name).get(arguments.get(1));
        }
    }

    public static final class DictPut extends PrimFun {
        public DictPut() { super("dict/put", 3); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "dict/put key");
            return dictionary(arguments.get(0), location, name).put(arguments.get(1), arguments.get(2));
        }
    }

    public static final class DictRemove extends PrimFun {
        public DictRemove() { super("dict/remove", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "dict/remove key");
            return dictionary(arguments.get(0), location, name).remove(arguments.get(1));
        }
    }

    public static final class DictKeys extends PrimFun {
        public DictKeys() { super("dict/keys", 1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            return new Vector(dictionary(arguments.get(0), location, name).entries().stream()
                    .map(DictValue.Entry::key).toList());
        }
    }

    public static final class DictValues extends PrimFun {
        public DictValues() { super("dict/values", 1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            return new Vector(dictionary(arguments.get(0), location, name).entries().stream()
                    .map(DictValue.Entry::value).toList());
        }
    }

    public static final class DictContainsKey extends PrimFun {
        public DictContainsKey() { super("dict/contains-key", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "dict/contains-key key");
            return new BoolValue(dictionary(arguments.get(0), location, name).containsKey(arguments.get(1)));
        }
    }

    public static final class DictSize extends PrimFun {
        public DictSize() { super("dict/size", 1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            return new IntValue(dictionary(arguments.get(0), location, name).size());
        }
    }

    public static final class Set extends PrimFun {
        public Set() { super("set", -1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            arguments.forEach(value -> comparable(value, location, "set"));
            return new SetValue(arguments);
        }
    }

    public static final class SetAdd extends PrimFun {
        public SetAdd() { super("set/add", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "set/add value");
            return set(arguments.get(0), location, name).add(arguments.get(1));
        }
    }

    public static final class SetRemove extends PrimFun {
        public SetRemove() { super("set/remove", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "set/remove value");
            return set(arguments.get(0), location, name).remove(arguments.get(1));
        }
    }

    public static final class SetContains extends PrimFun {
        public SetContains() { super("set/contains", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            comparable(arguments.get(1), location, "set/contains value");
            return new BoolValue(set(arguments.get(0), location, name).contains(arguments.get(1)));
        }
    }

    public static final class SetValues extends PrimFun {
        public SetValues() { super("set/values", 1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            return new Vector(set(arguments.get(0), location, name).values());
        }
    }

    public static final class SetSize extends PrimFun {
        public SetSize() { super("set/size", 1); }
        @Override public Value apply(List<Value> arguments, Node location) {
            return new IntValue(set(arguments.get(0), location, name).size());
        }
    }

    public static final class SetUnion extends PrimFun {
        public SetUnion() { super("set/union", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            SetValue result = set(arguments.get(0), location, name);
            for (Value value : set(arguments.get(1), location, name).values()) result = result.add(value);
            return result;
        }
    }

    public static final class SetIntersection extends PrimFun {
        public SetIntersection() { super("set/intersection", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            SetValue left = set(arguments.get(0), location, name);
            SetValue right = set(arguments.get(1), location, name);
            return new SetValue(left.values().stream().filter(right::contains).toList());
        }
    }

    public static final class SetDifference extends PrimFun {
        public SetDifference() { super("set/difference", 2); }
        @Override public Value apply(List<Value> arguments, Node location) {
            SetValue left = set(arguments.get(0), location, name);
            SetValue right = set(arguments.get(1), location, name);
            return new SetValue(left.values().stream().filter(value -> !right.contains(value)).toList());
        }
    }
}
