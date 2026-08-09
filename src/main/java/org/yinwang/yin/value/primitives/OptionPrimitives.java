package org.yinwang.yin.value.primitives;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.OptionValue;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;
import java.util.List;

public final class OptionPrimitives {
    private OptionPrimitives() { }
    public static final class Some extends PrimFun {
        public Some() { super("some", 1); }
        @Override public Value apply(List<Value> args, Node location) {
            return OptionValue.some(args.get(0));
        }
    }
}
