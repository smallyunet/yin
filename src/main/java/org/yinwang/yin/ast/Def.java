package org.yinwang.yin.ast;

import org.yinwang.yin.Binder;
import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

public class Def extends Node {
    public Node pattern;
    public Node value;


    public Def(Node pattern, Node value, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.pattern = pattern;
        this.value = value;
    }


    public Value interp(Scope<Value> s) {
        Value valueValue = value.interp(s);
        Binder.checkDup(pattern);
        Binder.define(pattern, valueValue, s);
        return Value.VOID;
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        YinType t = value.typecheck(s);
        Binder.checkDup(pattern);
        Binder.defineType(pattern, t, s);
        return Types.VOID;
    }


    public String toString() {
        return "(" + Constants.DEF_KEYWORD + " " + pattern + " " + value + ")";
    }

}
