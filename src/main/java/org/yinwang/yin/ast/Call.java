package org.yinwang.yin.ast;


import org.yinwang.yin.Constants;
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
            Scope<Value> funScope = new Scope<>(closure.env);
            List<Name> params = closure.fun.params;

            // set default values for parameters
            if (closure.properties != null) {
                Declare.mergeDefault(closure.properties, funScope);
            }

            if (!args.positional.isEmpty() && args.keywords.isEmpty()) {
                if (args.positional.size() != params.size()) {
                    Util.abort(this.op,
                            "calling function with wrong number of arguments. expected: " + params.size()
                                    + " actual: " + args.positional.size());
                }
                for (int i = 0; i < args.positional.size(); i++) {
                    Value value = args.positional.get(i).interp(s);
                    funScope.putValue(params.get(i).id, value);
                }
            } else {
                Set<String> seen = new HashSet<>();
                for (Name param : params) {
                    Node actual = args.keywords.get(param.id);
                    if (actual != null) {
                        seen.add(param.id);
                        Value value = actual.interp(s);
                        funScope.putValue(param.id, value);
                    } else if (funScope.lookupLocal(param.id) == null) {
                        Util.abort(this, "argument not supplied for: " + param);
                    }
                }

                List<String> extra = new ArrayList<>();
                for (String id : args.keywords.keySet()) {
                    if (!seen.contains(id)) {
                        extra.add(id);
                    }
                }
                if (!extra.isEmpty()) {
                    Util.abort(this, "extra keyword arguments: " + extra);
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

            for (String field : template.properties.keySet()) {
                Node actual = args.keywords.get(field);
                Object defaultValue = template.properties.lookupPropertyLocal(field, "default");
                if (actual != null) {
                    values.putValue(field, actual.interp(s));
                } else if (defaultValue instanceof Value) {
                    values.putValue(field, (Value) defaultValue);
                } else {
                    Util.abort(this, "field is not initialized: " + field);
                }
            }

            // instantiate
            return new RecordValue(template.name, values);
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
            Scope<YinType> funScope = new Scope<>(funtype.environment);
            List<Name> params = funtype.function.params;

            // set default values for parameters
            if (funtype.properties != null) {
                Declare.mergeType(funtype.properties, funScope);
            }

            if (!args.positional.isEmpty() && args.keywords.isEmpty()) {
                // positional
                if (args.positional.size() != params.size()) {
                    Util.abort(this.op,
                            "calling function with wrong number of arguments. expected: " + params.size()
                                    + " actual: " + args.positional.size());
                }

                for (int i = 0; i < args.positional.size(); i++) {
                    YinType value = args.positional.get(i).typecheck(s);
                    YinType expected = funScope.lookup(params.get(i).id);
                    if (expected != null && !Types.subtype(value, expected, false)) {
                        Util.abort(args.positional.get(i), "type error. expected: " + expected + ", actual: " + value);
                    }
                    funScope.putValue(params.get(i).id, value);
                }
            } else {
                // keywords
                Set<String> seen = new HashSet<>();

                // try to bind all arguments
                for (Name param : params) {
                    Node actual = args.keywords.get(param.id);
                    if (actual != null) {
                        seen.add(param.id);
                        YinType value = actual.typecheck(s);
                        YinType expected = funScope.lookup(param.id);
                        if (expected != null && !Types.subtype(value, expected, false)) {
                            Util.abort(actual, "type error. expected: " + expected + ", actual: " + value);
                        }
                        funScope.putValue(param.id, value);
                    } else if (funScope.lookupLocal(param.id) == null) {
                        Util.abort(this, "argument not supplied for: " + param);
                        return Types.VOID;
                    }
                }

                // detect extra arguments
                List<String> extra = new ArrayList<>();
                for (String id : args.keywords.keySet()) {
                    if (!seen.contains(id)) {
                        extra.add(id);
                    }
                }

                if (!extra.isEmpty()) {
                    Util.abort(this, "extra keyword arguments: " + extra);
                    return Types.VOID;
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

            for (String field : template.properties.keySet()) {
                Node actualNode = args.keywords.get(field);
                YinType expected = template.properties.lookupLocalType(field);
                Object defaultValue = template.properties.lookupPropertyLocal(field, "default");
                if (actualNode != null) {
                    YinType actual = actualNode.typecheck(s);
                    if (!Types.subtype(actual, expected, false)) {
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
            return new RecordValueType(template.name, values);
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
