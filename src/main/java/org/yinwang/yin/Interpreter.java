package org.yinwang.yin;


import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.Value;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Yin " + Constants.VERSION);
            return;
        }
        if (args.length > 0 && args[0].equals("--format")) {
            int status = Formatter.run(
                    Arrays.copyOfRange(args, 1, args.length),
                    new java.io.PrintWriter(System.out, true),
                    new java.io.PrintWriter(System.err, true));
            if (status != 0) {
                System.exit(status);
            }
            return;
        }
        if (args.length == 0 || (args.length == 1 && args[0].equals("--repl"))) {
            try {
                new Repl(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8),
                        new OutputStreamWriter(System.out, StandardCharsets.UTF_8),
                        System.console() != null).run();
            } catch (IOException error) {
                System.err.println("failed to read REPL input: " + error.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length != 1) {
            System.err.println(
                    "usage: java -jar yin.jar [--version | --repl | --format [mode] <file>... | <program.yin>]");
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
