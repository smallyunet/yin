package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;

import java.util.List;

public final class Length extends PrimFun {
    public Length() {
        super("length", 1);
    }

    @Override
    public Value apply(List<Value> args, Node location) {
        Value value = args.get(0);
        if (!(value instanceof Vector)) {
            Util.abort(location, "length requires a vector, got: " + value);
        }
        Vector vector = (Vector) value;
        return new IntValue(vector.size());
    }
}
