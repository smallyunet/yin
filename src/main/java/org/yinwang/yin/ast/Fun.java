package org.yinwang.yin.ast;


import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.value.Closure;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.FunctionType;
import org.yinwang.yin.type.YinType;

import java.util.List;

public class Fun extends Node {
    public List<Name> params;
    public Node body;
    public Scope<Object> propertyForm;


    public Fun(List<Name> params, Scope<Object> propertyForm, Node body,
               String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.params = params;
        this.propertyForm = propertyForm;     // unevaluated property form
        this.body = body;
    }


    public Value interp(Scope<Value> s) {
        // evaluate and cache the properties in the closure
        Scope<Value> properties = propertyForm == null ? null : Declare.evalProperties(propertyForm, s);
        return new Closure(this, properties, s);
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        // evaluate and cache the properties in the closure
        Scope<YinType> properties = propertyForm == null ? null : Declare.typecheckProperties(propertyForm, s);
        FunctionType ft = new FunctionType(this, properties, s);
        if (properties != null) {
            s.typeChecker.uncalled.add(ft);
        }
        return ft;
    }


    public String toString() {
        return "(" + Constants.FUN_KEYWORD + " (" + params + ") " + body + ")";
    }

}
