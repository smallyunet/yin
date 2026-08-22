package org.yinwang.yin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.yinwang.yin.ast.Assign;
import org.yinwang.yin.ast.BigInt;
import org.yinwang.yin.ast.FloatNum;
import org.yinwang.yin.ast.Invoke;
import org.yinwang.yin.ast.Import;
import org.yinwang.yin.ast.ModuleDef;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.ToolDef;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.Value;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Deterministic, side-effect-free contract profile for portable policy evaluation. */
public final class DeterministicContractRuntime {
    public static final int CONTRACT_VERSION = 1;

    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "Any", "Float", "args", "parse-float", "print", "read-text");

    private DeterministicContractRuntime() { }

    public static String check(String file) {
        String source = read(file, "source");
        return checkSource(file, source);
    }

    public static String checkSource(String sourceName, String source) {
        Node program = parse(sourceName, source);
        validate(program);
        new TypeChecker(sourceName).typecheckSource(sourceName, source);
        return "{\"contractVersion\":" + CONTRACT_VERSION
                + ",\"profile\":\"deterministic-policy-v1\""
                + ",\"programHash\":" + quote(digest(source))
                + ",\"valid\":true}";
    }

    public static String evaluate(String file, String input) {
        String source = read(file, "source");
        return evaluateSource(file, source, input);
    }

    public static String evaluateSource(String sourceName, String source, String input) {
        Node program = parse(sourceName, source);
        validate(program);
        new TypeChecker(sourceName).typecheckSource(sourceName, source);

        RuntimeContext context = new RuntimeContext(
                ignored -> { throw new GeneralError("print is unavailable in deterministic contracts"); },
                () -> input,
                java.util.List.of(),
                ignored -> { throw new GeneralError("read-text is unavailable in deterministic contracts"); },
                Map.of(),
                request -> false,
                ignored -> { throw new GeneralError("tool audit events are unavailable in deterministic contracts"); });
        Value value = program.interp(Scope.buildInitScope(context));
        if (value instanceof ResultValue outcome) {
            if (outcome.tag() == ResultValue.Tag.ERR) {
                throw new GeneralError("deterministic contract failed to encode its result: "
                        + outcome.payload());
            }
            value = outcome.payload();
        }
        if (!(value instanceof StringValue text)) {
            throw new GeneralError("deterministic contract must return JSON text from encode-json, got: " + value);
        }

        JsonElement result;
        try {
            result = JsonParser.parseString(text.value);
        } catch (RuntimeException error) {
            throw new GeneralError("deterministic contract returned invalid JSON: " + clean(error.getMessage()));
        }
        String resultJson = result.toString();
        return "{\"contractVersion\":" + CONTRACT_VERSION
                + ",\"profile\":\"deterministic-policy-v1\""
                + ",\"status\":\"completed\""
                + ",\"programHash\":" + quote(digest(source))
                + ",\"inputHash\":" + quote(digest(input))
                + ",\"result\":" + resultJson
                + ",\"resultHash\":" + quote(digest(resultJson)) + "}";
    }

    public static int checkCommand(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 1) {
            error.println("usage: --contract-check <program.yin>");
            return 2;
        }
        try {
            output.println(check(args[0]));
            return 0;
        } catch (GeneralError failure) {
            error.println(failure);
            return 1;
        }
    }

    public static int runCommand(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 3 || !args[1].equals("--input")) {
            error.println("usage: --contract-run <program.yin> --input <input.json>");
            return 2;
        }
        try {
            output.println(evaluate(args[0], read(args[2], "input")));
            return 0;
        } catch (GeneralError failure) {
            error.println(failure);
            return 1;
        }
    }

    private static Node parse(String file, String source) {
        try {
            return Parser.parseSource(file, source);
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + error.getMessage(), error.span));
        }
    }

    private static void validate(Node program) {
        visit(program, new IdentityHashMap<>());
    }

    private static void visit(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value.getClass().isEnum() || seen.put(value, true) != null) {
            return;
        }
        if (value instanceof Node node) {
            validateNode(node);
            for (Class<?> type = node.getClass(); type != null && Node.class.isAssignableFrom(type);
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        visit(field.get(node), seen);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("failed to inspect deterministic contract", error);
                    }
                }
            }
            return;
        }
        if (value instanceof Scope<?> scope) {
            visit(scope.table, seen);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> { visit(key, seen); visit(item, seen); });
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> visit(item, seen));
        }
    }

    private static void validateNode(Node node) {
        if (node instanceof FloatNum) reject(node, "Float literals");
        if (node instanceof BigInt) reject(node, "arbitrary-precision integers");
        if (node instanceof Assign) reject(node, "set!");
        if (node instanceof ToolDef) reject(node, "tool declarations");
        if (node instanceof Invoke) reject(node, "tool invocation");
        if (node instanceof Import || node instanceof ModuleDef) reject(node, "modules");
        if (node instanceof Name name && FORBIDDEN_NAMES.contains(name.id)) {
            reject(node, name.id);
        }
    }

    private static void reject(Node node, String feature) {
        throw new GeneralError(node, feature + " is outside deterministic-policy-v1");
    }

    private static String read(String file, String kind) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.IO, "failed to read " + kind + " file: " + file, null));
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte current : bytes) hex.append(String.format("%02x", current & 0xff));
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String clean(String message) {
        return message == null || message.isBlank() ? "invalid JSON" : message;
    }
}
