package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.ArrayList;
import java.util.List;


public class Block extends Node {
    public List<Node> statements = new ArrayList<>();


    public Block(List<Node> statements, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.statements = statements;
    }


    public Value interp(Scope<Value> s) {
        if (statements.isEmpty()) {
            return Value.VOID;
        }
        s = new Scope<>(s);
        for (int i = 0; i < statements.size() - 1; i++) {
            statements.get(i).interp(s);
        }
        return statements.get(statements.size() - 1).interp(s);
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        if (statements.isEmpty()) {
            return Types.VOID;
        }
        s = new Scope<>(s);
        for (int i = 0; i < statements.size() - 1; i++) {
            statements.get(i).typecheck(s);
        }
        return statements.get(statements.size() - 1).typecheck(s);
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        String sep = statements.size() > 5 ? "\n" : " ";
        sb.append("(seq" + sep);

        for (int i = 0; i < statements.size(); i++) {
            sb.append(statements.get(i).toString());
            if (i != statements.size() - 1) {
                sb.append(sep);
            }
        }

        sb.append(")");
        return sb.toString();
    }
}
