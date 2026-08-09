package org.yinwang.yin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;

/** Host capabilities explicitly injected into a Yin runtime scope. */
public record RuntimeContext(
        Consumer<String> output,
        Supplier<String> input,
        List<String> arguments,
        Function<String, String> readText) {

    public RuntimeContext {
        output = output == null ? ignored -> { } : output;
        input = input == null ? () -> "" : input;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        readText = readText == null ? RuntimeContext::readFile : readText;
    }

    public RuntimeContext(
            Consumer<String> output, Supplier<String> input, List<String> arguments) {
        this(output, input, arguments, RuntimeContext::readFile);
    }

    public static RuntimeContext standard() {
        return new RuntimeContext(System.out::println, () -> "", List.of());
    }

    private static String readFile(String path) {
        String content = Util.readFile(path);
        if (content == null) {
            throw new GeneralError("failed to read text file: " + path);
        }
        return content;
    }
}
