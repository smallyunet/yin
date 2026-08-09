package org.yinwang.yin;


import org.yinwang.yin.ast.Node;
import org.yinwang.yin.lsp.YinLanguageServer;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.json.JsonCodec;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.io.PrintStream;
import java.util.function.Supplier;

public class Interpreter {

    String file;


    public Interpreter(String file) {
        this.file = file;
    }


    public Value interp(String file) {
        return interp(file, RuntimeContext.standard());
    }

    public Value interp(String file, RuntimeContext context) {
        Node program;
        try {
            program = Parser.parse(file);
        } catch (ParserException e) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + e.getMessage(), e.span));
        }
        Scope<Value> scope = Scope.buildInitScope(context);
        return program.interp(scope);
    }


    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Yin " + Constants.VERSION);
            return;
        }
        if (args.length == 1 && args[0].equals("--lsp")) {
            try {
                new YinLanguageServer(System.in, System.out).run();
            } catch (IOException error) {
                System.err.println("language server failed: " + error.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--capabilities")) {
            int status = CapabilityManifest.run(
                    Arrays.copyOfRange(args, 1, args.length), System.out, System.err);
            if (status != 0) System.exit(status);
            return;
        }
        if (args.length > 0 && args[0].equals("--json")) {
            int status = runJson(
                    Arrays.copyOfRange(args, 1, args.length),
                    System.out, System.err, Interpreter::readStandardInput);
            if (status != 0) System.exit(status);
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
        try {
            Interpreter i = new Interpreter(args[0]);
            List<String> programArguments = Arrays.asList(Arrays.copyOfRange(args, 1, args.length));
            RuntimeContext context = new RuntimeContext(
                    System.out::println, Interpreter::readStandardInput, programArguments);
            Util.msg(i.interp(args[0], context).toString());
        } catch (GeneralError error) {
            System.err.println(error);
            System.exit(1);
        }
    }

    private static String readStandardInput() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new GeneralError("failed to read standard input: " + error.getMessage());
        }
    }

    /** Runs a JSON-boundary program without mixing Yin debug rendering into stdout. */
    public static int runJson(String[] args, PrintStream output, PrintStream error,
                              Supplier<String> input) {
        if (args.length == 0) {
            error.println("usage: --json <program.yin> [arguments...]");
            return 2;
        }
        try {
            String file = args[0];
            RuntimeContext context = new RuntimeContext(
                    error::println,
                    input,
                    Arrays.asList(Arrays.copyOfRange(args, 1, args.length)));
            Value result = new Interpreter(file).interp(file, context);
            if (result instanceof StringValue text) {
                output.println(text.value);
                return 0;
            }
            if (result instanceof ResultValue outcome) {
                if (outcome.tag() == ResultValue.Tag.OK
                        && outcome.payload() instanceof StringValue text) {
                    output.println(text.value);
                    return 0;
                }
                if (outcome.tag() == ResultValue.Tag.ERR) {
                    output.println(JsonCodec.encode(outcome.payload()));
                    return 1;
                }
            }
            error.println("--json expects String or (Result String E), got: " + result);
            return 1;
        } catch (GeneralError languageError) {
            error.println(languageError);
            return 1;
        } catch (JsonCodec.Failure encodingError) {
            error.println("failed to encode JSON error at " + encodingError.path()
                    + ": " + encodingError.getMessage());
            return 1;
        }
    }

}
