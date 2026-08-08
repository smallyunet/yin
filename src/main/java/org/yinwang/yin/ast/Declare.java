package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.Map;


public class Declare extends Node {
    public Scope<Object> propertyForm;


    public Declare(Scope<Object> propertyForm, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.propertyForm = propertyForm;
    }


    public Value interp(Scope<Value> s) {
//        mergeProperties(propsNode, s);
        return Value.VOID;
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        return Types.VOID;
    }


    public static void mergeDefault(Scope<Value> properties, Scope<Value> s) {
        for (String key : properties.keySet()) {
            Object defaultValue = properties.lookupPropertyLocal(key, "default");
            if (defaultValue == null) {
                continue;
            } else if (defaultValue instanceof Value) {
                Value existing = s.lookup(key);
                if (existing == null) {
                    s.putValue(key, (Value) defaultValue);
                }
            } else {
                Util.abort("default value is not a value, shouldn't happen");
            }
        }
    }


    public static void mergeType(Scope<YinType> properties, Scope<YinType> s) {
        for (String key : properties.keySet()) {
            if (key.equals(Constants.RETURN_ARROW)) {
                continue;
            }
            Object type = properties.lookupPropertyLocal(key, "type");
            if (type == null) {
                continue;
            } else if (type instanceof YinType) {
                YinType existing = s.lookup(key);
                if (existing == null) {
                    s.putValue(key, (YinType) type);
                }
            } else {
                Util.abort("illegal type, shouldn't happen" + type);
            }
        }
    }


    public static Scope<Value> evalProperties(Scope<Object> unevaled, Scope<Value> s) {
        Scope<Value> evaled = new Scope<>();

        for (String field : unevaled.keySet()) {
            evaled.putProperties(field, Map.of());
            Map<String, Object> props = unevaled.lookupAllProps(field);
            for (Map.Entry<String, Object> e : props.entrySet()) {
                if (e.getKey().equals("type")) {
                    continue;
                }
                Object v = e.getValue();
                if (v instanceof Node) {
                    Value vValue = ((Node) v).interp(s);
                    evaled.put(field, e.getKey(), vValue);
                } else {
                    Util.abort("property is not a node, parser bug: " + v);
                }
            }
        }
        return evaled;
    }


    public static Scope<YinType> typecheckProperties(Scope<Object> unevaled, Scope<YinType> s) {
        Scope<YinType> evaled = new Scope<>();

        for (String field : unevaled.keySet()) {
            if (field.equals(Constants.RETURN_ARROW)) {
                evaled.putProperties(field, unevaled.lookupAllProps(field));
            } else {
                Map<String, Object> props = unevaled.lookupAllProps(field);
                for (Map.Entry<String, Object> e : props.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Node) {
                        YinType vValue = ((Node) v).typecheck(s);
                        evaled.put(field, e.getKey(), vValue);
                    } else {
                        Util.abort("property is not a node, parser bug: " + v);
                    }
                }
            }
        }
        return evaled;
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Constants.PAREN_BEGIN);
        sb.append(Constants.DECLARE_KEYWORD).append(" ");

        for (String field : propertyForm.keySet()) {
            Map<String, Object> props = propertyForm.lookupAllProps(field);
            for (Map.Entry<String, Object> e : props.entrySet()) {
                sb.append(" :" + e.getKey() + " " + e.getValue());
            }
        }

        sb.append(Constants.PAREN_END);
        return sb.toString();
    }
}
