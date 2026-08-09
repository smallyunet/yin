package org.yinwang.yin.value.primitives;

import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

import java.util.List;
import java.util.function.Supplier;

/** Reads the complete host-provided text input at call time. */
public final class ReadAll extends PrimFun {
    private final Supplier<String> input;

    public ReadAll(Supplier<String> input) {
        super("read-all", 0);
        this.input = input;
    }

    @Override
    public Value apply(List<Value> args, Node location) {
        return new StringValue(input.get());
    }
}
