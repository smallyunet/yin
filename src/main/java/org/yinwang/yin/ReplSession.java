package org.yinwang.yin;

import org.yinwang.yin.ast.Block;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.util.function.Consumer;

/** Persistent interpreter and type-checker state for interactive evaluation. */
public final class ReplSession {
    private static final String SOURCE_NAME = "<repl>";

    private final TypeChecker typeChecker;
    private Scope<Value> runtimeScope;
    private Scope<YinType> typeScope;

    public ReplSession() {
        this(System.out::println);
    }

    public ReplSession(Consumer<String> output) {
        this(new RuntimeContext(output, () -> "", java.util.List.of()));
    }

    public ReplSession(RuntimeContext context) {
        typeChecker = new TypeChecker(SOURCE_NAME);
        runtimeScope = Scope.buildInitScope(context);
        typeScope = Scope.buildInitTypeScope(typeChecker);
    }

    public Evaluation evaluate(String source) {
        Block program = parse(source);

        Scope<YinType> candidateTypes = new Scope<>(typeScope);
        YinType type = typeChecker.typecheckTopLevel(program, candidateTypes);

        Scope<Value> candidateValues = new Scope<>(runtimeScope);
        Value value = Value.VOID;
        for (Node statement : program.statements) {
            value = statement.interp(candidateValues);
        }

        typeScope = candidateTypes;
        runtimeScope = candidateValues;
        return new Evaluation(value, type);
    }

    private Block parse(String source) {
        try {
            return (Block) Parser.parseSource(SOURCE_NAME, source);
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX,
                    "parsing error: " + error.getMessage(),
                    error.span));
        }
    }

    public record Evaluation(Value value, YinType type) {
    }
}
