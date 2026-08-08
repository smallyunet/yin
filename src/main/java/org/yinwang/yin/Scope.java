package org.yinwang.yin;


import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.primitives.*;
import org.yinwang.yin.type.PrimitiveFunctionType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Scope<T> {

    public Map<String, Map<String, Object>> table = new LinkedHashMap<>();
    public Scope<T> parent;
    public TypeChecker typeChecker;


    public Scope() {
        this.parent = null;
    }


    public Scope(Scope<T> parent) {
        this.parent = parent;
        this.typeChecker = parent == null ? null : parent.typeChecker;
    }


    public Scope<T> copy() {
        Scope<T> ret = new Scope<>();
        for (String name : table.keySet()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.putAll(table.get(name));
            ret.table.put(name, props);
        }
        return ret;
    }


    public void putAll(Scope<T> other) {
        for (String name : other.table.keySet()) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.putAll(other.table.get(name));
            table.put(name, props);
        }
    }


    public T lookup(String name) {
        Object v = lookupProperty(name, "value");
        if (v == null) {
            return null;
        }
        return castBinding(v);
    }


    public T lookupLocal(String name) {
        Object v = lookupPropertyLocal(name, "value");
        if (v == null) {
            return null;
        }
        return castBinding(v);
    }


    public T lookupType(String name) {
        Object v = lookupProperty(name, "type");
        if (v == null) {
            return null;
        }
        return castBinding(v);
    }


    public T lookupLocalType(String name) {
        Object v = lookupPropertyLocal(name, "type");
        if (v == null) {
            return null;
        }
        return castBinding(v);
    }


    @SuppressWarnings("unchecked")
    private T castBinding(Object value) {
        return (T) value;
    }


    public Object lookupPropertyLocal(String name, String key) {
        Map<String, Object> item = table.get(name);
        if (item != null) {
            return item.get(key);
        } else {
            return null;
        }
    }


    public Object lookupProperty(String name, String key) {
        Object v = lookupPropertyLocal(name, key);
        if (v != null) {
            return v;
        } else if (parent != null) {
            return parent.lookupProperty(name, key);
        } else {
            return null;
        }
    }


    public Map<String, Object> lookupAllProps(String name) {
        return table.get(name);
    }


    public Scope<T> findDefiningScope(String name) {
        Object v = table.get(name);
        if (v != null) {
            return this;
        } else if (parent != null) {
            return parent.findDefiningScope(name);
        } else {
            return null;
        }
    }


    public static Scope<Value> buildInitScope() {
        Scope<Value> init = new Scope<>();

        addPrimitiveFunctions(init);

        init.putValue("true", new BoolValue(true));
        init.putValue("false", new BoolValue(false));

        return init;
    }


    public static Scope<YinType> buildInitTypeScope(TypeChecker typeChecker) {
        Scope<YinType> init = new Scope<>();
        init.typeChecker = typeChecker;

        addPrimitiveTypes(init);

        init.putValue("true", Types.BOOL);
        init.putValue("false", Types.BOOL);

        addTypes(init);

        return init;
    }


    private static void addPrimitiveFunctions(Scope<Value> init) {

        init.putValue("+", new Add());
        init.putValue("-", new Sub());
        init.putValue("*", new Mult());
        init.putValue("/", new Div());

        init.putValue("<", new Lt());
        init.putValue("<=", new LtE());
        init.putValue(">", new Gt());
        init.putValue(">=", new GtE());
        init.putValue("=", new Eq());
        init.putValue("and", new And());
        init.putValue("or", new Or());
        init.putValue("not", new Not());

        init.putValue("print", new Print());
    }


    private static void addPrimitiveTypes(Scope<YinType> init) {
        init.putValue("+", PrimitiveFunctionType.arithmetic("+"));
        init.putValue("-", PrimitiveFunctionType.arithmetic("-"));
        init.putValue("*", PrimitiveFunctionType.arithmetic("*"));
        init.putValue("/", PrimitiveFunctionType.arithmetic("/"));
        init.putValue("<", PrimitiveFunctionType.numericComparison("<"));
        init.putValue("<=", PrimitiveFunctionType.numericComparison("<="));
        init.putValue(">", PrimitiveFunctionType.numericComparison(">"));
        init.putValue(">=", PrimitiveFunctionType.numericComparison(">="));
        init.putValue("=", PrimitiveFunctionType.numericComparison("="));
        init.putValue("and", PrimitiveFunctionType.booleanBinary("and"));
        init.putValue("or", PrimitiveFunctionType.booleanBinary("or"));
        init.putValue("not", PrimitiveFunctionType.booleanUnary("not"));
        init.putValue("print", PrimitiveFunctionType.print());
        init.putValue("U", PrimitiveFunctionType.union());
    }


    private static void addTypes(Scope<YinType> init) {
        init.putValue("Int", Types.INT);
        init.putValue("Float", Types.FLOAT);
        init.putValue("Bool", Types.BOOL);
        init.putValue("String", Types.STRING);
        init.putValue("Any", Types.ANY);
    }


    public void put(String name, String key, Object value) {
        Map<String, Object> item = table.get(name);
        if (item == null) {
            item = new LinkedHashMap<>();
        }
        item.put(key, value);
        table.put(name, item);
    }


    public void putProperties(String name, Map<String, Object> props) {
        Map<String, Object> item = table.get(name);
        if (item == null) {
            item = new LinkedHashMap<>();
        }
        item.putAll(props);
        table.put(name, item);
    }


    public void putValue(String name, T value) {
        put(name, "value", value);
    }


    public void putType(String name, T value) {
        put(name, "type", value);
    }


    public Set<String> keySet() {
        return table.keySet();
    }


    public boolean containsKey(String key) {
        return table.containsKey(key);
    }


    public String toString() {
        StringBuffer sb = new StringBuffer();
        for (String name : table.keySet()) {
            sb.append(Constants.SQUARE_BEGIN).append(name).append(" ");
            for (Map.Entry<String, Object> e : table.get(name).entrySet()) {
                sb.append(":" + e.getKey() + " " + e.getValue());
            }
            sb.append(Constants.SQUARE_END);
        }
        return sb.toString();
    }

}
