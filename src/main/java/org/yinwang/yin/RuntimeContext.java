package org.yinwang.yin;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;

/** Host capabilities explicitly injected into a Yin runtime scope. */
public record RuntimeContext(
        Consumer<String> output,
        Supplier<String> input,
        List<String> arguments,
        Function<String, String> readText,
        Map<String, ToolHandler> tools,
        AuthorizationPolicy authorizationPolicy,
        Consumer<ToolAuditEvent> auditSink) {

    public enum Effect {
        READ("read"), WRITE("write"), DESTRUCTIVE("destructive");
        private final String sourceName;
        Effect(String sourceName) { this.sourceName = sourceName; }
        public String sourceName() { return sourceName; }
        public static Effect parse(String name) {
            for (Effect effect : values()) {
                if (effect.sourceName.equals(name)) return effect;
            }
            throw new IllegalArgumentException("unknown tool effect: " + name);
        }
    }

    public record ToolDescriptor(
            String name,
            String inputType,
            String outputType,
            String errorType,
            String capability,
            Effect effect,
            boolean approvalRequired,
            boolean idempotent,
            boolean openWorld) {

        public String toJson() {
            return "{\"name\":" + quote(name)
                    + ",\"inputType\":" + quote(inputType)
                    + ",\"outputType\":" + quote(outputType)
                    + ",\"errorType\":" + quote(errorType)
                    + ",\"capability\":" + quote(capability)
                    + ",\"effect\":" + quote(effect.sourceName)
                    + ",\"approvalRequired\":" + approvalRequired
                    + ",\"idempotent\":" + idempotent
                    + ",\"openWorld\":" + openWorld + "}";
        }
    }

    public record ToolRequest(ToolDescriptor descriptor, String inputJson) { }
    public record ToolResponse(boolean error, String json) {
        public static ToolResponse success(String json) { return new ToolResponse(false, json); }
        public static ToolResponse failure(String json) { return new ToolResponse(true, json); }
    }
    public record ToolAuditEvent(
            ToolDescriptor descriptor, String inputJson, String status, String outputJson) { }

    @FunctionalInterface public interface ToolHandler {
        ToolResponse invoke(String inputJson) throws Exception;
    }
    @FunctionalInterface public interface AuthorizationPolicy {
        boolean authorize(ToolRequest request);
    }

    public RuntimeContext {
        output = output == null ? ignored -> { } : output;
        input = input == null ? () -> "" : input;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        readText = readText == null ? RuntimeContext::readFile : readText;
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        authorizationPolicy = authorizationPolicy == null ? request -> false : authorizationPolicy;
        auditSink = auditSink == null ? ignored -> { } : auditSink;
    }

    public RuntimeContext(
            Consumer<String> output, Supplier<String> input, List<String> arguments) {
        this(output, input, arguments, RuntimeContext::readFile, Map.of(), null, null);
    }

    public RuntimeContext(
            Consumer<String> output, Supplier<String> input, List<String> arguments,
            Function<String, String> readText) {
        this(output, input, arguments, readText, Map.of(), null, null);
    }

    public static RuntimeContext standard() {
        return new RuntimeContext(System.out::println, () -> "", List.of());
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (current < 0x20) {
                        String hex = Integer.toHexString(current);
                        json.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        json.append(current);
                    }
                }
            }
        }
        return json.append('\"').toString();
    }

    private static String readFile(String path) {
        String content = Util.readFile(path);
        if (content == null) {
            throw new GeneralError("failed to read text file: " + path);
        }
        return content;
    }
}
