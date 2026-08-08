package org.yinwang.yin.ast;


import org.yinwang.yin.Scope;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

public class Str extends Node {
    public String value;


    public Str(String value, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.value = value;
    }


    public Value interp(Scope<Value> s) {
        return new StringValue(value);
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        return Types.STRING;
    }


    public String toString() {
        return "\"" + value + "\"";
    }

}
