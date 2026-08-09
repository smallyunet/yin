package org.yinwang.yin.value.primitives;

import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

import java.util.List;
import java.util.function.Function;

/** Reads one UTF-8 text resource through the injected host capability. */
public final class ReadText extends PrimFun {
    private final Function<String, String> readText;

    public ReadText(Function<String, String> readText) {
        super("read-text", 1);
        this.readText = readText;
    }

    @Override
    public Value apply(List<Value> args, Node location) {
        if (!(args.get(0) instanceof StringValue path)) {
            Util.abort(location, "read-text path must be String, got: " + args.get(0));
            return Value.VOID;
        }
        return new StringValue(readText.apply(path.value));
    }
}
