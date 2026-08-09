package org.yinwang.yin.value.primitives;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.Value;

import java.util.List;

/** Constructors for explicit success and failure outcomes. */
public final class ResultPrimitives {
    private ResultPrimitives() {
    }

    public static final class Ok extends PrimFun {
        public Ok() {
            super("ok", 1);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            return new ResultValue(ResultValue.Tag.OK, args.get(0));
        }
    }

    public static final class Err extends PrimFun {
        public Err() {
            super("err", 1);
        }

        @Override
        public Value apply(List<Value> args, Node location) {
            return new ResultValue(ResultValue.Tag.ERR, args.get(0));
        }
    }
}
