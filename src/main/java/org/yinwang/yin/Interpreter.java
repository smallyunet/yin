package org.yinwang.yin;


import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.Value;

public class Interpreter {

    String file;


    public Interpreter(String file) {
        this.file = file;
    }


    public Value interp(String file) {
        Node program;
        try {
            program = Parser.parse(file);
        } catch (ParserException e) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + e.getMessage(), e.span));
        }
        Scope<Value> scope = Scope.buildInitScope();
        return program.interp(scope);
    }


    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -jar yin.jar <program.yin>");
            System.exit(2);
        }

        try {
            Interpreter i = new Interpreter(args[0]);
            Util.msg(i.interp(args[0]).toString());
        } catch (GeneralError error) {
            System.err.println(error);
            System.exit(1);
        }
    }

}
