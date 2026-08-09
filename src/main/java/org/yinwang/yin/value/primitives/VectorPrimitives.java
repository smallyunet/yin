package org.yinwang.yin.value.primitives;

import org.yinwang.yin.CallableSupport;
import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.Closure;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.ValueEquality;
import org.yinwang.yin.value.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Higher-order operations over immutable Yin vectors. */
public final class VectorPrimitives {
    private VectorPrimitives() {
    }

    private static Vector vector(Value value, Node location, String operation) {
        if (!(value instanceof Vector vector)) {
            Util.abort(location, operation + " requires a vector, got: " + value);
            return new Vector(List.of());
        }
        return vector;
    }

    private static Closure closure(Value value, Node location, String operation) {
        if (!(value instanceof Closure closure)) {
            Util.abort(location, operation + " requires a function, got: " + value);
            return null;
        }
        return closure;
    }

    public static final class Map extends PrimFun {
        public Map() {
            super("map", 2);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            Vector input = vector(args.get(0), location, name);
            Closure function = closure(args.get(1), location, name);
            List<Value> output = new ArrayList<>();
            for (Value value : input.values()) {
                output.add(CallableSupport.apply(function, List.of(value), location));
            }
            return new Vector(output);
        }
    }

    public static final class Filter extends PrimFun {
        public Filter() {
            super("filter", 2);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            Vector input = vector(args.get(0), location, name);
            Closure function = closure(args.get(1), location, name);
            List<Value> output = new ArrayList<>();
            for (Value value : input.values()) {
                Value selected = CallableSupport.apply(function, List.of(value), location);
                if (!(selected instanceof BoolValue)) {
                    Util.abort(location, "filter predicate must return Bool, got: " + selected);
                }
                BoolValue condition = (BoolValue) selected;
                if (condition.value) {
                    output.add(value);
                }
            }
            return new Vector(output);
        }
    }

    public static final class Fold extends PrimFun {
        public Fold() {
            super("fold", 3);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            Vector input = vector(args.get(0), location, name);
            Value accumulator = args.get(1);
            Closure function = closure(args.get(2), location, name);
            for (Value value : input.values()) {
                accumulator = CallableSupport.apply(function, List.of(accumulator, value), location);
            }
            return accumulator;
        }
    }

    public static final class Range extends PrimFun {
        public Range() {
            super("range", 2);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            if (!(args.get(0) instanceof IntValue) || !(args.get(1) instanceof IntValue)) {
                Util.abort(location, "range bounds must be Int");
            }
            IntValue start = (IntValue) args.get(0);
            IntValue end = (IntValue) args.get(1);
            List<Value> output = new ArrayList<>();
            for (int value = start.value; value < end.value; value++) {
                output.add(new IntValue(value));
            }
            return new Vector(output);
        }
    }

    public static final class Slice extends PrimFun {
        public Slice() {
            super("slice", 3);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            Vector input = vector(args.get(0), location, name);
            if (!(args.get(1) instanceof IntValue) || !(args.get(2) instanceof IntValue)) {
                Util.abort(location, "slice bounds must be Int");
            }
            IntValue start = (IntValue) args.get(1);
            IntValue end = (IntValue) args.get(2);
            if (start.value < 0 || end.value < start.value || end.value > input.size()) {
                Util.abort(location, "invalid slice bounds: " + start.value + ", " + end.value
                        + " for length " + input.size());
            }
            return new Vector(input.values().subList(start.value, end.value));
        }
    }

    public static final class Reverse extends PrimFun {
        public Reverse() {
            super("reverse", 1);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            List<Value> values = new ArrayList<>(vector(args.get(0), location, name).values());
            Collections.reverse(values);
            return new Vector(values);
        }
    }

    public static final class Contains extends PrimFun {
        public Contains() {
            super("contains", 2);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            Vector input = vector(args.get(0), location, name);
            return new BoolValue(input.values().stream()
                    .anyMatch(value -> ValueEquality.equal(value, args.get(1))));
        }
    }
}
