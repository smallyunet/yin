package org.yinwang.yin.ast;


import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.type.Types;

public class Keyword extends Node {
    public String id;


    public Keyword(String id, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.id = id;
    }


    public Name asName() {
        return new Name(id, file, start, end, line, col);
    }


    public Value interp(Scope<Value> s) {
        Util.abort(this, "keyword used as value");
        return Value.VOID;
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        Util.abort(this, "keyword used as value");
        return Types.VOID;
    }


    public String toString() {
        return ":" + id;
    }
}
