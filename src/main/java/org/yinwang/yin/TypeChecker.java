package org.yinwang.yin;


import org.yinwang.yin.ast.Block;
import org.yinwang.yin.ast.Declare;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.type.FunctionType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TypeChecker {

    public String file;
    public Set<FunctionType> uncalled = new HashSet<>();
    public Set<FunctionType> callStack = new HashSet<>();
    private final List<RuntimeContext.ToolDescriptor> tools = new ArrayList<>();


    public TypeChecker(String file) {
        this.file = file;
    }


    public YinType typecheck(String file) {
        uncalled.clear();
        callStack.clear();
        tools.clear();
        Node program;
        try {
            program = Parser.parse(file);
        } catch (ParserException e) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + e.getMessage(), e.span));
        }
        Scope<YinType> s = Scope.buildInitTypeScope(this);
        YinType ret = program.typecheck(s);

        finishChecks(s);

        return ret;
    }

    public void registerTool(RuntimeContext.ToolDescriptor descriptor, Node location) {
        for (RuntimeContext.ToolDescriptor existing : tools) {
            if (existing.name().equals(descriptor.name())) {
                if (!existing.equals(descriptor)) {
                    Util.abort(location, "conflicting tool declaration: " + descriptor.name());
                }
                return;
            }
        }
        tools.add(descriptor);
    }

    public List<RuntimeContext.ToolDescriptor> tools() {
        return List.copyOf(tools);
    }


    public YinType typecheckTopLevel(Block program, Scope<YinType> scope) {
        uncalled.clear();
        callStack.clear();
        YinType result = Types.VOID;
        for (Node statement : program.statements) {
            result = statement.typecheck(scope);
        }
        finishChecks(scope);
        return result;
    }


    private void finishChecks(Scope<YinType> scope) {

        while (!uncalled.isEmpty()) {
            List<FunctionType> toRemove = new ArrayList<>(uncalled);
            for (FunctionType ft : toRemove) {
                invokeUncalled(ft, scope);
            }
            uncalled.removeAll(toRemove);
        }
    }


    public void invokeUncalled(FunctionType fun, Scope<YinType> s) {
        Scope<YinType> funScope = new Scope<>(fun.environment);
        if (fun.properties != null) {
            Declare.mergeType(fun.properties, funScope);
        }

        callStack.add(fun);
        YinType actual = fun.function.body.typecheck(funScope);
        callStack.remove(fun);

        Object retNode = fun.properties == null
                ? null
                : fun.properties.lookupPropertyLocal(Constants.RETURN_ARROW, "type");

        if (retNode == null) {
            return;
        }
        if (!(retNode instanceof Node)) {
            Util.abort("illegal return type: " + retNode);
        }

        YinType expected = ((Node) retNode).typecheck(funScope);
        if (!Types.subtype(actual, expected)) {
            Util.abort(fun.function, "type error in return value, expected: " + expected + ", actual: " + actual);
        }
    }


    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -cp yin.jar org.yinwang.yin.TypeChecker <program.yin>");
            System.exit(2);
        }

        try {
            TypeChecker tc = new TypeChecker(args[0]);
            YinType result = tc.typecheck(args[0]);
            Util.msg(result.toString());
        } catch (GeneralError error) {
            System.err.println(error);
            System.exit(1);
        }
    }

}
