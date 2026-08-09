package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.FloatValue;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Total, immutable string operations used by Yin programs. */
public final class StringPrimitives {
    private StringPrimitives() {
    }

    private static String string(Value value, Node location, String operation) {
        if (!(value instanceof StringValue string)) {
            Util.abort(location, operation + " requires String, got: " + value);
            return "";
        }
        return string.value;
    }

    private static int integer(Value value, Node location, String operation) {
        if (!(value instanceof IntValue integer)) {
            Util.abort(location, operation + " requires Int bounds, got: " + value);
            return 0;
        }
        return integer.value;
    }

    public static final class Length extends PrimFun {
        public Length() { super("string-length", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            return new IntValue(string(args.get(0), location, name).length());
        }
    }

    public static final class Concat extends PrimFun {
        public Concat() { super("concat", 2); }
        @Override public Value apply(List<Value> args, Node location) {
            return new StringValue(string(args.get(0), location, name)
                    + string(args.get(1), location, name));
        }
    }

    public static final class Substring extends PrimFun {
        public Substring() { super("substring", 3); }
        @Override public Value apply(List<Value> args, Node location) {
            String value = string(args.get(0), location, name);
            int start = integer(args.get(1), location, name);
            int end = integer(args.get(2), location, name);
            if (start < 0 || end < start || end > value.length()) {
                Util.abort(location, "invalid substring bounds: " + start + ", " + end
                        + " for length " + value.length());
            }
            return new StringValue(value.substring(start, end));
        }
    }

    public static final class Split extends PrimFun {
        public Split() { super("split", 2); }
        @Override public Value apply(List<Value> args, Node location) {
            String value = string(args.get(0), location, name);
            String delimiter = string(args.get(1), location, name);
            if (delimiter.isEmpty()) {
                Util.abort(location, "split delimiter cannot be empty");
            }
            List<Value> parts = new ArrayList<>();
            for (String part : value.split(Pattern.quote(delimiter), -1)) {
                parts.add(new StringValue(part));
            }
            return new Vector(parts);
        }
    }

    public static final class Join extends PrimFun {
        public Join() { super("join", 2); }
        @Override public Value apply(List<Value> args, Node location) {
            String delimiter = string(args.get(0), location, name);
            if (!(args.get(1) instanceof Vector)) {
                Util.abort(location, "join requires a vector of String values");
            }
            Vector vector = (Vector) args.get(1);
            List<String> values = new ArrayList<>();
            for (Value value : vector.values()) {
                values.add(string(value, location, name));
            }
            return new StringValue(String.join(delimiter, values));
        }
    }

    public static final class Trim extends PrimFun {
        public Trim() { super("trim", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            return new StringValue(string(args.get(0), location, name).trim());
        }
    }

    public static final class ToString extends PrimFun {
        public ToString() { super("to-string", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            Value value = args.get(0);
            return new StringValue(value instanceof StringValue string ? string.value : value.toString());
        }
    }

    public static final class ParseInt extends PrimFun {
        public ParseInt() { super("parse-int", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            try {
                return new IntValue(Integer.parseInt(string(args.get(0), location, name)));
            } catch (NumberFormatException error) {
                return new BoolValue(false);
            }
        }
    }

    public static final class ParseFloat extends PrimFun {
        public ParseFloat() { super("parse-float", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            try {
                return new FloatValue(Double.parseDouble(string(args.get(0), location, name)));
            } catch (NumberFormatException error) {
                return new BoolValue(false);
            }
        }
    }
}
