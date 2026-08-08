package org.yinwang.yin;


import org.yinwang.yin.ast.Declare;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.FunType;
import org.yinwang.yin.value.Type;
import org.yinwang.yin.value.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TypeChecker {

    public String file;
    public Set<FunType> uncalled = new HashSet<>();
    public Set<FunType> callStack = new HashSet<>();


    public TypeChecker(String file) {
        this.file = file;
    }


    public Value typecheck(String file) {
        uncalled.clear();
        callStack.clear();
        Node program;
        try {
            program = Parser.parse(file);
        } catch (ParserException e) {
            throw new GeneralError("parsing error: " + e);
        }
        Scope s = Scope.buildInitTypeScope(this);
        Value ret = program.typecheck(s);

        while (!uncalled.isEmpty()) {
            List<FunType> toRemove = new ArrayList<>(uncalled);
            for (FunType ft : toRemove) {
                invokeUncalled(ft, s);
            }
            uncalled.removeAll(toRemove);
        }

        return ret;
    }


    public void invokeUncalled(FunType fun, Scope s) {
        Scope funScope = new Scope(fun.env);
        if (fun.properties != null) {
            Declare.mergeType(fun.properties, funScope);
        }

        callStack.add(fun);
        Value actual = fun.fun.body.typecheck(funScope);
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

        Value expected = ((Node) retNode).typecheck(funScope);
        if (!Type.subtype(actual, expected, true)) {
            Util.abort(fun.fun, "type error in return value, expected: " + expected + ", actual: " + actual);
        }
    }


    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -cp yin.jar org.yinwang.yin.TypeChecker <program.yin>");
            System.exit(2);
        }

        try {
            TypeChecker tc = new TypeChecker(args[0]);
            Value result = tc.typecheck(args[0]);
            Util.msg(result.toString());
        } catch (GeneralError error) {
            System.err.println(error);
            System.exit(1);
        }
    }

}
