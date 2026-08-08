package org.yinwang.yin.value.primitives;


import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.PrimFun;
import org.yinwang.yin.value.Value;

import java.util.List;
import java.util.function.Consumer;

public class Print extends PrimFun {
    private final Consumer<String> output;

    public Print() {
        this(System.out::println);
    }


    public Print(Consumer<String> output) {
        super("print", -1);
        this.output = output;
    }


    @Override
    public Value apply(List<Value> args, Node location) {
        StringBuilder line = new StringBuilder();
        boolean first = true;
        for (Value v : args) {
            if (!first) {
                line.append(", ");
            }
            line.append(v);
            first = false;
        }
        output.accept(line.toString());
        return Value.VOID;
    }


}
