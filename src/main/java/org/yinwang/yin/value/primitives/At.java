package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;

import java.util.List;

public final class At extends PrimFun {
    public At() {
        super("at", 2);
    }

    @Override
    public Value apply(List<Value> args, Node location) {
        Value target = args.get(0);
        Value indexValue = args.get(1);
        if (!(target instanceof Vector)) {
            Util.abort(location, "at requires a vector, got: " + target);
        }
        if (!(indexValue instanceof IntValue)) {
            Util.abort(location, "at index must be Int, got: " + indexValue);
        }
        Vector vector = (Vector) target;
        IntValue index = (IntValue) indexValue;
        if (index.value < 0 || index.value >= vector.size()) {
            Util.abort(location, "vector index out of bounds: " + index.value +
                    " for length " + vector.size());
        }
        return vector.get(index.value);
    }
}
