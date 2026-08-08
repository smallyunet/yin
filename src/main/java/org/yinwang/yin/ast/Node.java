package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.SourceSpan;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.YinType;

import java.util.ArrayList;
import java.util.List;

public abstract class Node {
    public String file;
    public int start;
    public int end;
    public int line;
    public int col;


    protected Node(String file, int start, int end, int line, int col) {
        this.file = file;
        this.start = start;
        this.end = end;
        this.line = line;
        this.col = col;
    }


    public abstract Value interp(Scope<Value> s);


    public static Value interp(Node node, Scope<Value> s) {
        return node.interp(s);
    }


    public abstract YinType typecheck(Scope<YinType> s);


    public static YinType typecheck(Node node, Scope<YinType> s) {
        return node.typecheck(s);
    }


    public static List<Value> interpList(List<Node> nodes, Scope<Value> s) {
        List<Value> values = new ArrayList<>();
        for (Node n : nodes) {
            values.add(n.interp(s));
        }
        return values;
    }


    public static List<YinType> typecheckList(List<Node> nodes, Scope<YinType> s) {
        List<YinType> types = new ArrayList<>();
        for (Node n : nodes) {
            types.add(n.typecheck(s));
        }
        return types;
    }


    public String getFileLineCol() {
        return file + ":" + (line + 1) + ":" + (col + 1);
    }


    public SourceSpan sourceSpan() {
        return SourceSpan.from(this);
    }


    public static String printList(List<? extends Node> nodes) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Node e : nodes) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(e);
            first = false;
        }
        return sb.toString();
    }

}
