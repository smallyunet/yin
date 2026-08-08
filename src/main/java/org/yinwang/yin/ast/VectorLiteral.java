package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;
import org.yinwang.yin.type.VectorType;
import org.yinwang.yin.type.YinType;

import java.util.List;

public class VectorLiteral extends Node {

    public List<Node> elements;


    public VectorLiteral(List<Node> elements, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.elements = elements;
    }


    @Override
    public Value interp(Scope<Value> s) {
        return new Vector(interpList(elements, s));
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        return new VectorType(typecheckList(elements, s));
    }

}
