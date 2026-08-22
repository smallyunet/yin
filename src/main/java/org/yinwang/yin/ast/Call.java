package org.yinwang.yin.ast;


import org.yinwang.yin.Constants;
import org.yinwang.yin.CallableSupport;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.value.*;
import org.yinwang.yin.type.*;

import java.util.*;

public class Call extends Node {
    public Node op;
    public Argument args;


    public Call(Node op, Argument args, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.op = op;
        this.args = args;
    }


    public Value interp(Scope<Value> s) {
        Value opv = this.op.interp(s);
        if (opv instanceof Closure) {
            Closure closure = (Closure) opv;
            if (!args.positional.isEmpty() && args.keywords.isEmpty()) {
                return CallableSupport.apply(closure, Node.interpList(args.positional, s), this.op);
            }
            Scope<Value> funScope = new Scope<>(closure.env);
            List<Name> params = closure.fun.params;

            // set default values for parameters
            if (closure.properties != null) {
                Declare.mergeDefault(closure.properties, funScope);
            }

            {
                Set<String> parameterNames = new LinkedHashSet<>();
                for (Name param : params) {
                    parameterNames.add(param.id);
                    boolean hasDefault = closure.properties != null
                            && closure.properties.lookupPropertyLocal(param.id, "default") instanceof Value;
                    if (!args.keywords.containsKey(param.id) && !hasDefault) {
                        Util.abort(this, "argument not supplied for: " + param);
                    }
                }

                List<String> extra = new ArrayList<>();
                for (String id : args.keywords.keySet()) {
                    if (!parameterNames.contains(id)) {
                        extra.add(id);
                    }
                }
                if (!extra.isEmpty()) {
                    Util.abort(this, "extra keyword arguments: " + extra);
                }

                // Keyword values are evaluated in their source order.
                for (Map.Entry<String, Node> argument : args.keywords.entrySet()) {
                    funScope.putValue(argument.getKey(), argument.getValue().interp(s));
                }
            }
            return closure.fun.body.interp(funScope);
        } else if (opv instanceof RecordConstructor) {
            RecordConstructor template = (RecordConstructor) opv;
            Scope<Value> values = new Scope<>();

            if (args.keywords.isEmpty() && !args.positional.isEmpty()) {
                Util.abort(this, "record fields must be supplied as keyword arguments");
            }

            for (Map.Entry<String, Node> entry : args.keywords.entrySet()) {
                if (!template.properties.containsKey(entry.getKey())) {
                    Util.abort(this, "extra keyword argument: " + entry.getKey());
                }
            }

            Map<String, Value> actualValues = new LinkedHashMap<>();
            for (Map.Entry<String, Node> argument : args.keywords.entrySet()) {
                actualValues.put(argument.getKey(), argument.getValue().interp(s));
            }

            for (String field : template.properties.keySet()) {
                Object defaultValue = template.properties.lookupPropertyLocal(field, "default");
                if (actualValues.containsKey(field)) {
                    values.putValue(field, actualValues.get(field));
                } else if (defaultValue instanceof Value) {
                    values.putValue(field, (Value) defaultValue);
                } else {
                    Util.abort(this, "field is not initialized: " + field);
                }
            }

            // instantiate
            return new RecordValue(template.name, values, template.nominalTypes(),
                    template.variantName(), template.identity());
        } else if (opv instanceof PrimFun) {
            PrimFun prim = (PrimFun) opv;
            if (!args.keywords.isEmpty()) {
                Util.abort(this, "primitive arguments must be positional: " + prim.name);
            }
            if (prim.arity >= 0 && args.positional.size() != prim.arity) {
                Util.abort(this, "incorrect number of arguments for primitive " +
                        prim.name + ", expecting " + prim.arity + ", but got " + args.positional.size());
            }
            List<Value> args = Node.interpList(this.args.positional, s);
            return prim.apply(args, this);
        } else {  // can't happen
            Util.abort(this.op, "calling non-function: " + opv);
            return Value.VOID;
        }
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        YinType fun = this.op.typecheck(s);
        if (fun instanceof FunctionType funtype) {
            if (!args.positional.isEmpty() && args.keywords.isEmpty()) {
                return CallableSupport.apply(funtype, Node.typecheckList(args.positional, s), this.op);
            }
            Scope<YinType> funScope = new Scope<>(funtype.environment);
            List<Name> params = funtype.function.params;

            // set default values for parameters
            if (funtype.properties != null) {
                Declare.mergeType(funtype.properties, funScope);
            }

            {
                // keywords
                Set<String> parameterNames = new LinkedHashSet<>();
                for (Name param : params) {
                    parameterNames.add(param.id);
                    boolean hasDefault = funtype.properties != null
                            && funtype.properties.lookupPropertyLocal(param.id, "default") instanceof YinType;
                    if (!args.keywords.containsKey(param.id) && !hasDefault) {
                        Util.abort(this, "argument not supplied for: " + param);
                        return Types.VOID;
                    }
                }

                List<String> extra = new ArrayList<>();
                for (String id : args.keywords.keySet()) {
                    if (!parameterNames.contains(id)) {
                        extra.add(id);
                    }
                }

                if (!extra.isEmpty()) {
                    Util.abort(this, "extra keyword arguments: " + extra);
                    return Types.VOID;
                }

                // Mirror runtime evaluation by checking keyword values in source order.
                for (Map.Entry<String, Node> argument : args.keywords.entrySet()) {
                    YinType value = argument.getValue().typecheck(s);
                    YinType expected = funtype.properties == null
                            ? null
                            : funtype.properties.lookupLocalType(argument.getKey());
                    if (expected != null && !Types.subtype(value, expected)) {
                        Util.abort(argument.getValue(),
                                "type error. expected: " + expected + ", actual: " + value);
                    }
                    funScope.putValue(argument.getKey(), value);
                }
            }

            Object retType = funtype.properties == null
                    ? null
                    : funtype.properties.lookupPropertyLocal(Constants.RETURN_ARROW, "type");
            if (retType != null) {
                if (retType instanceof Node) {
                    // evaluate the return type because it might be (typeof x)
                    return ((Node) retType).typecheck(funScope);
                } else {
                    Util.abort("illegal return type: " + retType);
                    return Types.VOID;
                }
            } else {
                if (s.typeChecker.callStack.contains(funtype)) {
                    Util.abort(op, "You must specify return type for recursive functions: " + op);
                    return Types.VOID;
                }

                s.typeChecker.callStack.add(funtype);
                YinType actual = funtype.function.body.typecheck(funScope);
                s.typeChecker.callStack.remove(funtype);
                return actual;
            }
        } else if (fun instanceof DeclaredFunctionType) {
            if (!args.keywords.isEmpty()) {
                Util.abort(this, "declared function arguments must be positional");
            }
            return CallableSupport.apply(fun, Node.typecheckList(args.positional, s), this.op);
        } else if (fun instanceof org.yinwang.yin.type.RecordType) {
            RecordType template = (RecordType) fun;
            Scope<YinType> values = new Scope<>();

            if (args.keywords.isEmpty() && !args.positional.isEmpty()) {
                Util.abort(this, "record fields must be supplied as keyword arguments");
            }

            for (Map.Entry<String, Node> e : args.keywords.entrySet()) {
                if (!template.properties.keySet().contains(e.getKey())) {
                    Util.abort(this, "extra keyword argument: " + e.getKey());
                }
            }

            Map<String, YinType> actualTypes = new LinkedHashMap<>();
            for (Map.Entry<String, Node> argument : args.keywords.entrySet()) {
                actualTypes.put(argument.getKey(), argument.getValue().typecheck(s));
            }

            for (String field : template.properties.keySet()) {
                YinType expected = template.properties.lookupLocalType(field);
                Object defaultValue = template.properties.lookupPropertyLocal(field, "default");
                if (actualTypes.containsKey(field)) {
                    YinType actual = actualTypes.get(field);
                    if (!Types.subtype(actual, expected)) {
                        Util.abort(this, "type error. expected: " + expected + ", actual: " + actual);
                    }
                    values.putValue(field, actual);
                } else if (defaultValue instanceof YinType) {
                    values.putValue(field, (YinType) defaultValue);
                } else {
                    Util.abort(this, "field is not initialized: " + field);
                }
            }

            // instantiate
            return new RecordValueType(template.name, values, template.nominalTypes(),
                    template.variantName(), template.identity());
        } else if (fun instanceof PrimitiveFunctionType primitiveType) {
            if (!args.keywords.isEmpty()) {
                Util.abort(this, "primitive arguments must be positional: " + primitiveType.name);
            }
            if (primitiveType.arity >= 0 && args.positional.size() != primitiveType.arity) {
                Util.abort(this, "incorrect number of arguments for primitive " +
                        primitiveType.name + ", expecting " + primitiveType.arity +
                        ", but got " + args.positional.size());
                return Types.VOID;
            } else {
                List<YinType> arguments = Node.typecheckList(this.args.positional, s);
                return primitiveType.apply(arguments, this);
            }
        } else {
            Util.abort(this.op, "calling non-function: " + fun);
            return Types.VOID;
        }

    }


    public String toString() {
        if (args.positional.size() != 0) {
            return "(" + op + " " + args + ")";
        } else {
            return "(" + op + ")";
        }
    }

}
