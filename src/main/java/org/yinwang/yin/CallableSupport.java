package org.yinwang.yin;

import org.yinwang.yin.ast.Declare;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.type.DeclaredFunctionType;
import org.yinwang.yin.type.FunctionType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Closure;
import org.yinwang.yin.value.Value;

import java.util.List;

/** Shared positional invocation for source functions and higher-order primitives. */
public final class CallableSupport {
    private CallableSupport() {
    }

    public static Value apply(Closure closure, List<Value> arguments, Node location) {
        List<Name> parameters = closure.fun.params;
        if (arguments.size() != parameters.size()) {
            Util.abort(location, "calling function with wrong number of arguments. expected: "
                    + parameters.size() + " actual: " + arguments.size());
        }
        Scope<Value> scope = new Scope<>(closure.env);
        if (closure.properties != null) {
            Declare.mergeDefault(closure.properties, scope);
        }
        for (int i = 0; i < arguments.size(); i++) {
            scope.putValue(parameters.get(i).id, arguments.get(i));
        }
        return closure.fun.body.interp(scope);
    }

    public static YinType apply(YinType callable, List<YinType> arguments, Node location) {
        if (callable instanceof DeclaredFunctionType declared) {
            checkArguments(declared.parameters(), arguments, location);
            return declared.result();
        }
        if (!(callable instanceof FunctionType function)) {
            Util.abort(location, "expected a function, got: " + callable);
            return Types.VOID;
        }

        List<Name> parameters = function.function.params;
        if (arguments.size() != parameters.size()) {
            Util.abort(location, "calling function with wrong number of arguments. expected: "
                    + parameters.size() + " actual: " + arguments.size());
        }
        Scope<YinType> scope = new Scope<>(function.environment);
        if (function.properties != null) {
            Declare.mergeType(function.properties, scope);
        }
        for (int i = 0; i < arguments.size(); i++) {
            YinType expected = scope.lookup(parameters.get(i).id);
            YinType actual = arguments.get(i);
            if (expected != null && !Types.subtype(actual, expected)) {
                Util.abort(location, "type error. expected: " + expected + ", actual: " + actual);
            }
            scope.putValue(parameters.get(i).id, actual);
        }

        Object resultNode = function.properties == null
                ? null
                : function.properties.lookupPropertyLocal(Constants.RETURN_ARROW, "type");
        if (resultNode instanceof Node node) {
            return node.typecheck(scope);
        }
        if (scope.typeChecker.callStack.contains(function)) {
            Util.abort(location, "You must specify return type for recursive functions");
        }
        scope.typeChecker.callStack.add(function);
        YinType result = function.function.body.typecheck(scope);
        scope.typeChecker.callStack.remove(function);
        return result;
    }

    private static void checkArguments(
            List<YinType> parameters, List<YinType> arguments, Node location) {
        if (parameters.size() != arguments.size()) {
            Util.abort(location, "calling function with wrong number of arguments. expected: "
                    + parameters.size() + " actual: " + arguments.size());
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!Types.subtype(arguments.get(i), parameters.get(i))) {
                Util.abort(location, "type error. expected: " + parameters.get(i)
                        + ", actual: " + arguments.get(i));
            }
        }
    }
}
