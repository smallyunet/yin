package org.yinwang.yin.value.primitives;


import org.yinwang.yin.Util;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.value.*;

import java.util.List;

public class Eq extends PrimFun {

    public Eq() {
        super("=", 2);
    }


    @Override
    public Value apply(List<Value> args, Node location) {

        return new BoolValue(ValueEquality.equal(args.get(0), args.get(1)));
    }


}
