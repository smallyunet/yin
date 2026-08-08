package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.BoolType;
import org.yinwang.yin.type.UnionType;
import org.yinwang.yin.type.YinType;

public class If extends Node {
    public Node test;
    public Node then;
    public Node orelse;


    public If(Node test, Node then, Node orelse, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.test = test;
        this.then = then;
        this.orelse = orelse;
    }


    public Value interp(Scope<Value> s) {
        Value tv = interp(test, s);
        if (!(tv instanceof BoolValue)) {
            Util.abort(test, "test is not boolean: " + tv);
        }
        if (((BoolValue) tv).value) {
            return interp(then, s);
        } else {
            return interp(orelse, s);
        }
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        YinType tv = typecheck(test, s);
        if (!(tv instanceof BoolType)) {
            Util.abort(test, "test is not boolean: " + tv);
            return org.yinwang.yin.type.Types.VOID;
        }
        YinType type1 = typecheck(then, s);
        YinType type2 = typecheck(orelse, s);
        return UnionType.union(type1, type2);
    }


    public String toString() {
        return "(" + Constants.IF_KEYWORD + " " + test + " " + then + " " + orelse + ")";
    }

}
