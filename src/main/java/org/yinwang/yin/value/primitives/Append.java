package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;

import java.util.List;

public final class Append extends PrimFun {
    public Append() {
        super("append", 2);
    }

    @Override
    public Value apply(List<Value> args, Node location) {
        Value left = args.get(0);
        Value right = args.get(1);
        if (!(left instanceof Vector)) {
            Util.abort(location, "append requires vectors, left argument was: " + left);
        }
        if (!(right instanceof Vector)) {
            Util.abort(location, "append requires vectors, right argument was: " + right);
        }
        Vector leftVector = (Vector) left;
        Vector rightVector = (Vector) right;
        return leftVector.append(rightVector);
    }
}
