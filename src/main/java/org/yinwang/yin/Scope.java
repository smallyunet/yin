package org.yinwang.yin;


import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.primitives.*;
import org.yinwang.yin.type.PrimitiveFunctionType;
import org.yinwang.yin.type.CollectionPrimitiveTypes;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.ArrayList;

public class Scope<T> {

    public Map<String, Map<String, Object>> table = new LinkedHashMap<>();
    public Scope<T> parent;
    public TypeChecker typeChecker;
    public RuntimeContext runtimeContext;
    public ModuleRuntime moduleRuntime;


    public Scope() {
        this.parent = null;
    }


    public Scope(Scope<T> parent) {
        this.parent = parent;
        this.typeChecker = parent == null ? null : parent.typeChecker;
        this.runtimeContext = parent == null ? null : parent.runtimeContext;
        this.moduleRuntime = parent == null ? null : parent.moduleRuntime;
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
        return buildInitScope(RuntimeContext.standard());
    }


    public static Scope<Value> buildInitScope(Consumer<String> output) {
        return buildInitScope(new RuntimeContext(output, () -> "", java.util.List.of()));
    }


    public static Scope<Value> buildInitScope(RuntimeContext context) {
        Scope<Value> init = new Scope<>();
        init.runtimeContext = context;

        addPrimitiveFunctions(init, context);
        org.yinwang.yin.json.JsonSupport.installRuntime(init);

        init.putValue("true", new BoolValue(true));
        init.putValue("false", new BoolValue(false));
        java.util.List<Value> arguments = new ArrayList<>();
        for (String argument : context.arguments()) {
            arguments.add(new org.yinwang.yin.value.StringValue(argument));
        }
        init.putValue("args", new org.yinwang.yin.value.Vector(arguments));

        return init;
    }


    public static Scope<YinType> buildInitTypeScope(TypeChecker typeChecker) {
        Scope<YinType> init = new Scope<>();
        init.typeChecker = typeChecker;

        addPrimitiveTypes(init);
        org.yinwang.yin.json.JsonSupport.installTypes(init);

        init.putValue("true", Types.BOOL);
        init.putValue("false", Types.BOOL);
        init.putValue("args", new org.yinwang.yin.type.HomogeneousVectorType(Types.STRING));

        addTypes(init);

        return init;
    }


    private static void addPrimitiveFunctions(Scope<Value> init, RuntimeContext context) {

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

        init.putValue("length", new Length());
        init.putValue("at", new At());
        init.putValue("append", new Append());
        init.putValue("map", new VectorPrimitives.Map());
        init.putValue("filter", new VectorPrimitives.Filter());
        init.putValue("fold", new VectorPrimitives.Fold());
        init.putValue("range", new VectorPrimitives.Range());
        init.putValue("slice", new VectorPrimitives.Slice());
        init.putValue("reverse", new VectorPrimitives.Reverse());
        init.putValue("contains", new VectorPrimitives.Contains());
        init.putValue("dict", new CollectionPrimitives.Dict());
        init.putValue("dict/get", new CollectionPrimitives.DictGet());
        init.putValue("dict/put", new CollectionPrimitives.DictPut());
        init.putValue("dict/remove", new CollectionPrimitives.DictRemove());
        init.putValue("dict/keys", new CollectionPrimitives.DictKeys());
        init.putValue("dict/values", new CollectionPrimitives.DictValues());
        init.putValue("dict/contains-key", new CollectionPrimitives.DictContainsKey());
        init.putValue("dict/size", new CollectionPrimitives.DictSize());
        init.putValue("set", new CollectionPrimitives.Set());
        init.putValue("set/add", new CollectionPrimitives.SetAdd());
        init.putValue("set/remove", new CollectionPrimitives.SetRemove());
        init.putValue("set/contains", new CollectionPrimitives.SetContains());
        init.putValue("set/values", new CollectionPrimitives.SetValues());
        init.putValue("set/size", new CollectionPrimitives.SetSize());
        init.putValue("set/union", new CollectionPrimitives.SetUnion());
        init.putValue("set/intersection", new CollectionPrimitives.SetIntersection());
        init.putValue("set/difference", new CollectionPrimitives.SetDifference());
        init.putValue("ok", new ResultPrimitives.Ok());
        init.putValue("err", new ResultPrimitives.Err());
        init.putValue("some", new OptionPrimitives.Some());
        init.putValue("none", org.yinwang.yin.value.OptionValue.none());
        init.putValue("string-length", new StringPrimitives.Length());
        init.putValue("concat", new StringPrimitives.Concat());
        init.putValue("substring", new StringPrimitives.Substring());
        init.putValue("split", new StringPrimitives.Split());
        init.putValue("join", new StringPrimitives.Join());
        init.putValue("trim", new StringPrimitives.Trim());
        init.putValue("to-string", new StringPrimitives.ToString());
        init.putValue("parse-int", new StringPrimitives.ParseInt());
        init.putValue("parse-float", new StringPrimitives.ParseFloat());
        init.putValue("read-all", new ReadAll(context.input()));
        init.putValue("read-text", new ReadText(context.readText()));
        init.putValue("print", new Print(context.output()));
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
        init.putValue("=", PrimitiveFunctionType.equality());
        init.putValue("and", PrimitiveFunctionType.booleanBinary("and"));
        init.putValue("or", PrimitiveFunctionType.booleanBinary("or"));
        init.putValue("not", PrimitiveFunctionType.booleanUnary("not"));
        init.putValue("length", PrimitiveFunctionType.vectorLength());
        init.putValue("at", PrimitiveFunctionType.vectorAt());
        init.putValue("append", PrimitiveFunctionType.vectorAppend());
        init.putValue("map", PrimitiveFunctionType.vectorMap());
        init.putValue("filter", PrimitiveFunctionType.vectorFilter());
        init.putValue("fold", PrimitiveFunctionType.vectorFold());
        init.putValue("range", PrimitiveFunctionType.vectorRange());
        init.putValue("slice", PrimitiveFunctionType.vectorSlice());
        init.putValue("reverse", PrimitiveFunctionType.vectorReverse());
        init.putValue("contains", PrimitiveFunctionType.vectorContains());
        init.putValue("dict", CollectionPrimitiveTypes.dict());
        init.putValue("dict/get", CollectionPrimitiveTypes.dictGet());
        init.putValue("dict/put", CollectionPrimitiveTypes.dictPut());
        init.putValue("dict/remove", CollectionPrimitiveTypes.dictRemove());
        init.putValue("dict/keys", CollectionPrimitiveTypes.dictKeys());
        init.putValue("dict/values", CollectionPrimitiveTypes.dictValues());
        init.putValue("dict/contains-key", CollectionPrimitiveTypes.dictContainsKey());
        init.putValue("dict/size", CollectionPrimitiveTypes.dictSize());
        init.putValue("set", CollectionPrimitiveTypes.set());
        init.putValue("set/add", CollectionPrimitiveTypes.setAdd());
        init.putValue("set/remove", CollectionPrimitiveTypes.setRemove());
        init.putValue("set/contains", CollectionPrimitiveTypes.setContains());
        init.putValue("set/values", CollectionPrimitiveTypes.setValues());
        init.putValue("set/size", CollectionPrimitiveTypes.setSize());
        init.putValue("set/union", CollectionPrimitiveTypes.setUnion());
        init.putValue("set/intersection", CollectionPrimitiveTypes.setIntersection());
        init.putValue("set/difference", CollectionPrimitiveTypes.setDifference());
        init.putValue("ok", PrimitiveFunctionType.resultOk());
        init.putValue("err", PrimitiveFunctionType.resultErr());
        init.putValue("some", PrimitiveFunctionType.optionSome());
        init.putValue("none", Types.NONE);
        init.putValue("string-length", PrimitiveFunctionType.stringUnary("string-length", Types.INT));
        init.putValue("concat", PrimitiveFunctionType.stringBinary("concat", Types.STRING));
        init.putValue("substring", PrimitiveFunctionType.substring());
        init.putValue("split", PrimitiveFunctionType.split());
        init.putValue("join", PrimitiveFunctionType.join());
        init.putValue("trim", PrimitiveFunctionType.stringUnary("trim", Types.STRING));
        init.putValue("to-string", PrimitiveFunctionType.toStringType());
        init.putValue("parse-int", PrimitiveFunctionType.parseNumber("parse-int", Types.INT));
        init.putValue("parse-float", PrimitiveFunctionType.parseNumber("parse-float", Types.FLOAT));
        init.putValue("read-all", PrimitiveFunctionType.readAll());
        init.putValue("read-text", PrimitiveFunctionType.stringUnary("read-text", Types.STRING));
        init.putValue("print", PrimitiveFunctionType.print());
        init.putValue("U", PrimitiveFunctionType.union());
        init.putValue("Vector", PrimitiveFunctionType.vectorType());
        init.putValue("Fn", PrimitiveFunctionType.functionType());
        init.putValue("Result", PrimitiveFunctionType.resultType());
        init.putValue("Option", PrimitiveFunctionType.optionType());
        init.putValue("Dict", new PrimitiveFunctionType("Dict", 2,
                (arguments, location) -> new org.yinwang.yin.type.DictType(
                        arguments.get(0), arguments.get(1))));
        init.putValue("Set", new PrimitiveFunctionType("Set", 1,
                (arguments, location) -> new org.yinwang.yin.type.SetType(arguments.get(0))));
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
