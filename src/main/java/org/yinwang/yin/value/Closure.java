package org.yinwang.yin.value;


import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Fun;

public class Closure extends Value {

    public Fun fun;
    public Scope<Value> properties;
    public Scope<Value> env;


    public Closure(Fun fun, Scope<Value> properties, Scope<Value> env) {
        this.fun = fun;
        this.properties = properties;
        this.env = env;
    }


    public String toString() {
        return fun.toString();
    }

}
