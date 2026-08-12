package org.yinwang.yin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yinwang.yin.json.JsonCodec;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A deny-by-default reference host for local, policy-guarded tool calls. */
public final class ReferencePolicyRuntime {
    private static final int TRACE_VERSION = 1;
    private static final Set<String> HOST_FIELDS = Set.of("version", "root", "tools");
    private static final Set<String> TOOL_FIELDS = Set.of("name", "kind", "capability");

    private ReferencePolicyRuntime() { }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (UsageFailure failure) {
            error.println(failure.getMessage());
            error.println(usage());
            return 2;
        }

        TraceWriter trace = null;
        try {
            String source = Files.readString(options.program, StandardCharsets.UTF_8);
            String input = Files.readString(options.input, StandardCharsets.UTF_8);
            String hostSource = Files.readString(options.host, StandardCharsets.UTF_8);
            HostConfig host = HostConfig.read(options.host);

            TypeChecker checker = new TypeChecker(options.program.toString());
            checker.typecheck(options.program.toString());
            Map<String, RuntimeContext.ToolDescriptor> declarations = new LinkedHashMap<>();
            for (RuntimeContext.ToolDescriptor descriptor : checker.tools()) {
                declarations.put(descriptor.name(), descriptor);
            }
            host.validate(declarations);
            validateApprovals(options.approvals, declarations, host.tools);

            trace = new TraceWriter(options.trace);
            JsonObject started = event("run-started");
            started.addProperty("traceVersion", TRACE_VERSION);
            started.addProperty("runtimeVersion", Constants.VERSION);
            started.addProperty("runId", UUID.randomUUID().toString());
            started.addProperty("source", options.program.toString());
            started.addProperty("sourceSha256", sha256(source));
            started.addProperty("inputSha256", sha256(input));
            started.addProperty("hostSha256", sha256(hostSource));
            JsonArray approvals = new JsonArray();
            options.approvals.stream().sorted().forEach(approvals::add);
            started.add("approvedCapabilities", approvals);
            started.add("capabilities", JsonParser.parseString(
                    CapabilityManifest.toJson(checker.tools())));
            trace.write(started);

            Map<String, RuntimeContext.ToolHandler> tools = host.handlers();
            TraceWriter activeTrace = trace;
            RuntimeContext.AuthorizationPolicy authorization = request -> {
                HostTool installed = host.tools.get(request.descriptor().name());
                boolean approved = false;
                String reason;
                if (installed == null) {
                    reason = "tool-not-installed";
                } else if (!installed.capability.equals(request.descriptor().capability())) {
                    reason = "capability-mismatch";
                } else if (!installed.kind.effect.equals(request.descriptor().effect())) {
                    reason = "effect-mismatch";
                } else if (request.descriptor().approvalRequired()
                        && options.approvals.contains(request.descriptor().capability())) {
                    approved = true;
                    reason = "explicit-cli-approval";
                } else if (request.descriptor().approvalRequired()) {
                    reason = "approval-required";
                } else if (request.descriptor().effect() == RuntimeContext.Effect.READ) {
                    approved = true;
                    reason = "read-capability-installed";
                } else {
                    reason = "approval-required";
                }

                JsonObject decision = event("authorization");
                decision.addProperty("tool", request.descriptor().name());
                decision.addProperty("capability", request.descriptor().capability());
                decision.addProperty("effect", request.descriptor().effect().sourceName());
                decision.addProperty("inputSha256", sha256(request.inputJson()));
                decision.addProperty("approved", approved);
                decision.addProperty("reason", reason);
                activeTrace.writeUnchecked(decision);
                return approved;
            };

            RuntimeContext context = new RuntimeContext(
                    error::println,
                    () -> input,
                    List.of(),
                    path -> { throw new GeneralError("read-text is unavailable in --guard mode"); },
                    tools,
                    authorization,
                    audit -> {
                        JsonObject toolEvent = event("tool-result");
                        toolEvent.addProperty("tool", audit.descriptor().name());
                        toolEvent.addProperty("capability", audit.descriptor().capability());
                        toolEvent.addProperty("status", audit.status());
                        toolEvent.addProperty("inputSha256", sha256(audit.inputJson()));
                        toolEvent.addProperty("outputSha256", sha256(audit.outputJson()));
                        toolEvent.addProperty("output", audit.outputJson());
                        activeTrace.writeUnchecked(toolEvent);
                    });

            Value value = new Interpreter(options.program.toString())
                    .interp(options.program.toString(), context);
            Rendered rendered = render(value);
            JsonObject completed = event("run-completed");
            completed.addProperty("exitCode", rendered.exitCode);
            completed.addProperty("channel", rendered.channel);
            completed.addProperty("output", rendered.output);
            completed.addProperty("outputSha256", sha256(rendered.output));
            trace.write(completed);
            rendered.print(output, error);
            return rendered.exitCode;
        } catch (IOException | RuntimeException failure) {
            String message = clean(failure);
            if (trace != null) {
                JsonObject completed = event("run-completed");
                completed.addProperty("exitCode", 1);
                completed.addProperty("channel", "stderr");
                completed.addProperty("output", message);
                completed.addProperty("outputSha256", sha256(message));
                try {
                    trace.write(completed);
                } catch (IOException ignored) {
                    // Preserve the original failure when the trace itself is unavailable.
                }
            }
            error.println(message);
            return 1;
        } finally {
            if (trace != null) trace.closeUnchecked();
        }
    }

    public static int replay(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 1) {
            error.println("usage: --replay <trace.jsonl>");
            return 2;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
            if (lines.isEmpty()) throw new IllegalArgumentException("trace is empty");
            String previous = "";
            JsonObject completed = null;
            for (int index = 0; index < lines.size(); index++) {
                JsonElement parsed = JsonParser.parseString(lines.get(index));
                if (!parsed.isJsonObject()) throw new IllegalArgumentException("trace line is not an object");
                JsonObject record = parsed.getAsJsonObject();
                requireInt(record, "sequence", index + 1);
                requireString(record, "previousHash", previous);
                String actual = requiredString(record, "eventHash");
                JsonObject unsigned = record.deepCopy();
                unsigned.remove("eventHash");
                String expected = sha256(unsigned.toString());
                if (!actual.equals(expected)) {
                    throw new IllegalArgumentException("trace hash mismatch at line " + (index + 1));
                }
                previous = actual;
                String type = requiredString(record, "type");
                if (index == 0) {
                    if (!type.equals("run-started")) {
                        throw new IllegalArgumentException("trace must begin with run-started");
                    }
                    requireInt(record, "traceVersion", TRACE_VERSION);
                }
                if (type.equals("run-completed")) {
                    if (completed != null || index != lines.size() - 1) {
                        throw new IllegalArgumentException("run-completed must be the final trace event");
                    }
                    completed = record;
                }
            }
            if (completed == null) throw new IllegalArgumentException("trace has no completed run");
            int exitCode = completed.get("exitCode").getAsInt();
            String channel = requiredString(completed, "channel");
            String replayed = requiredString(completed, "output");
            if (channel.equals("stdout")) output.println(replayed);
            else error.println(replayed);
            return exitCode;
        } catch (IOException | RuntimeException failure) {
            error.println("cannot replay trace: " + clean(failure));
            return 1;
        }
    }

    private static Rendered render(Value value) {
        if (value instanceof StringValue text) return new Rendered(0, "stdout", text.value);
        if (value instanceof ResultValue result) {
            if (result.tag() == ResultValue.Tag.OK && result.payload() instanceof StringValue text) {
                return new Rendered(0, "stdout", text.value);
            }
            if (result.tag() == ResultValue.Tag.ERR) {
                return new Rendered(1, "stdout", JsonCodec.encode(result.payload()));
            }
        }
        throw new GeneralError("--guard expects String or (Result String E), got: " + value);
    }

    private record Rendered(int exitCode, String channel, String output) {
        void print(PrintStream stdout, PrintStream stderr) {
            (channel.equals("stdout") ? stdout : stderr).println(output);
        }
    }

    private enum ToolKind {
        READ_TEXT("read-text", RuntimeContext.Effect.READ),
        WRITE_TEXT("write-text", RuntimeContext.Effect.WRITE);

        final String sourceName;
        final RuntimeContext.Effect effect;
        ToolKind(String sourceName, RuntimeContext.Effect effect) {
            this.sourceName = sourceName;
            this.effect = effect;
        }
        static ToolKind parse(String value) {
            for (ToolKind kind : values()) if (kind.sourceName.equals(value)) return kind;
            throw new IllegalArgumentException("unsupported host tool kind: " + value);
        }
    }

    private record HostTool(String name, ToolKind kind, String capability) { }

    private static final class HostConfig {
        final Path root;
        final Map<String, HostTool> tools;

        private HostConfig(Path root, Map<String, HostTool> tools) {
            this.root = root;
            this.tools = Map.copyOf(tools);
        }

        static HostConfig read(Path file) throws IOException {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("host config must be an object");
            JsonObject object = parsed.getAsJsonObject();
            exactFields(object, HOST_FIELDS, "host config");
            if (!object.has("version") || !object.get("version").isJsonPrimitive()
                    || !object.get("version").getAsJsonPrimitive().isNumber()
                    || object.get("version").getAsInt() != 1) {
                throw new IllegalArgumentException("host config version must be 1");
            }
            Path base = file.toAbsolutePath().normalize().getParent();
            Path root = base.resolve(requiredString(object, "root")).normalize();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("host root is not a directory: " + root);
            root = root.toRealPath();

            JsonElement toolElement = object.get("tools");
            if (toolElement == null || !toolElement.isJsonArray()) {
                throw new IllegalArgumentException("host config tools must be an array");
            }
            Map<String, HostTool> tools = new LinkedHashMap<>();
            for (JsonElement element : toolElement.getAsJsonArray()) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("host tool must be an object");
                JsonObject tool = element.getAsJsonObject();
                exactFields(tool, TOOL_FIELDS, "host tool");
                HostTool installed = new HostTool(
                        requiredString(tool, "name"),
                        ToolKind.parse(requiredString(tool, "kind")),
                        requiredString(tool, "capability"));
                if (tools.putIfAbsent(installed.name, installed) != null) {
                    throw new IllegalArgumentException("duplicate host tool: " + installed.name);
                }
            }
            return new HostConfig(root, tools);
        }

        void validate(Map<String, RuntimeContext.ToolDescriptor> declarations) {
            for (HostTool tool : tools.values()) {
                RuntimeContext.ToolDescriptor declared = declarations.get(tool.name);
                if (declared == null) {
                    throw new IllegalArgumentException("host installs undeclared tool: " + tool.name);
                }
                if (!declared.capability().equals(tool.capability)) {
                    throw new IllegalArgumentException("host capability does not match declaration: " + tool.name);
                }
                if (declared.effect() != tool.kind.effect) {
                    throw new IllegalArgumentException("host effect does not match declaration: " + tool.name);
                }
                if (tool.kind == ToolKind.WRITE_TEXT && !declared.approvalRequired()) {
                    throw new IllegalArgumentException("reference host requires approval for writes: " + tool.name);
                }
            }
        }

        Map<String, RuntimeContext.ToolHandler> handlers() {
            Map<String, RuntimeContext.ToolHandler> handlers = new HashMap<>();
            for (HostTool tool : tools.values()) {
                handlers.put(tool.name, switch (tool.kind) {
                    case READ_TEXT -> this::readText;
                    case WRITE_TEXT -> this::writeText;
                });
            }
            return handlers;
        }

        private RuntimeContext.ToolResponse readText(String inputJson) {
            try {
                JsonObject input = request(inputJson, Set.of("path"));
                String relative = requiredString(input, "path");
                Path target = existingTarget(relative);
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return failure("NotFound", "path is not a regular file");
                }
                JsonObject result = new JsonObject();
                result.addProperty("path", relative);
                result.addProperty("content", Files.readString(target, StandardCharsets.UTF_8));
                return RuntimeContext.ToolResponse.success(result.toString());
            } catch (NoSuchFileExceptionLike failure) {
                return failure("NotFound", failure.getMessage());
            } catch (InvalidPathFailure failure) {
                return failure("InvalidPath", failure.getMessage());
            } catch (IOException failure) {
                return failure("IoError", "read failed");
            } catch (RuntimeException failure) {
                return failure("InvalidPath", clean(failure));
            }
        }

        private RuntimeContext.ToolResponse writeText(String inputJson) {
            try {
                JsonObject input = request(inputJson, Set.of("path", "content"));
                String relative = requiredString(input, "path");
                String content = requiredString(input, "content");
                Path target = writableTarget(relative);
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                JsonObject result = new JsonObject();
                result.addProperty("path", relative);
                result.addProperty("bytes", content.getBytes(StandardCharsets.UTF_8).length);
                return RuntimeContext.ToolResponse.success(result.toString());
            } catch (InvalidPathFailure failure) {
                return failure("InvalidPath", failure.getMessage());
            } catch (IOException failure) {
                return failure("IoError", "write failed");
            } catch (RuntimeException failure) {
                return failure("InvalidPath", clean(failure));
            }
        }

        private Path existingTarget(String relative) throws IOException {
            Path candidate = lexicalTarget(relative);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new NoSuchFileExceptionLike("file does not exist");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) throw new InvalidPathFailure("path escapes host root");
            return real;
        }

        private Path writableTarget(String relative) throws IOException {
            Path candidate = lexicalTarget(relative);
            Path parent = candidate.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new InvalidPathFailure("parent directory does not exist");
            }
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(root)) throw new InvalidPathFailure("path escapes host root");
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root)) throw new InvalidPathFailure("path escapes host root");
            }
            return candidate;
        }

        private Path lexicalTarget(String relative) {
            Path supplied = Path.of(relative);
            if (supplied.isAbsolute()) throw new InvalidPathFailure("absolute paths are not allowed");
            Path candidate = root.resolve(supplied).normalize();
            if (!candidate.startsWith(root)) throw new InvalidPathFailure("path escapes host root");
            return candidate;
        }

        private static JsonObject request(String inputJson, Set<String> fields) {
            JsonElement parsed = JsonParser.parseString(inputJson);
            if (!parsed.isJsonObject()) throw new InvalidPathFailure("tool input must be an object");
            JsonObject object = parsed.getAsJsonObject();
            exactFields(object, fields, "tool input");
            return object;
        }

        private static RuntimeContext.ToolResponse failure(String tag, String message) {
            JsonObject result = new JsonObject();
            result.addProperty("tag", tag);
            result.addProperty("message", message);
            return RuntimeContext.ToolResponse.failure(result.toString());
        }
    }

    private static final class TraceWriter implements AutoCloseable {
        private final BufferedWriter writer;
        private int sequence;
        private String previousHash = "";

        TraceWriter(Path path) throws IOException {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        synchronized void write(JsonObject event) throws IOException {
            event.addProperty("sequence", ++sequence);
            event.addProperty("timestamp", Instant.now().toString());
            event.addProperty("previousHash", previousHash);
            String hash = sha256(event.toString());
            event.addProperty("eventHash", hash);
            writer.write(event.toString());
            writer.newLine();
            writer.flush();
            previousHash = hash;
        }

        void writeUnchecked(JsonObject event) {
            try {
                write(event);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot write decision trace", failure);
            }
        }

        @Override public void close() throws IOException { writer.close(); }
        void closeUnchecked() {
            try { close(); } catch (IOException ignored) { }
        }
    }

    private record Options(Path program, Path input, Path host, Path trace, Set<String> approvals) {
        static Options parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) throw new UsageFailure("missing program path");
            Path program = Path.of(args[0]);
            Path input = null;
            Path host = null;
            Path trace = null;
            Set<String> approvals = new HashSet<>();
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (index + 1 >= args.length) throw new UsageFailure("missing value for " + option);
                String value = args[++index];
                switch (option) {
                    case "--input" -> input = unique(input, value, option);
                    case "--host" -> host = unique(host, value, option);
                    case "--trace" -> trace = unique(trace, value, option);
                    case "--approve" -> {
                        if (!approvals.add(value)) throw new UsageFailure("duplicate approval: " + value);
                    }
                    default -> throw new UsageFailure("unknown option: " + option);
                }
            }
            if (input == null || host == null || trace == null) {
                throw new UsageFailure("--input, --host, and --trace are required");
            }
            return new Options(program, input, host, trace, Set.copyOf(approvals));
        }

        private static Path unique(Path existing, String value, String option) {
            if (existing != null) throw new UsageFailure("duplicate option: " + option);
            return Path.of(value);
        }
    }

    private static final class UsageFailure extends RuntimeException {
        UsageFailure(String message) { super(message); }
    }
    private static final class InvalidPathFailure extends RuntimeException {
        InvalidPathFailure(String message) { super(message); }
    }
    private static final class NoSuchFileExceptionLike extends RuntimeException {
        NoSuchFileExceptionLike(String message) { super(message); }
    }

    private static JsonObject event(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }

    private static void validateApprovals(Set<String> approvals,
                                          Map<String, RuntimeContext.ToolDescriptor> declarations,
                                          Map<String, HostTool> installed) {
        for (String capability : approvals) {
            boolean valid = declarations.values().stream().anyMatch(descriptor ->
                    descriptor.capability().equals(capability)
                            && descriptor.approvalRequired()
                            && installed.containsKey(descriptor.name()));
            if (!valid) {
                throw new IllegalArgumentException(
                        "approval does not name an installed approval-required capability: "
                                + capability);
            }
        }
    }

    private static void exactFields(JsonObject object, Set<String> expected, String label) {
        Set<String> actual = object.keySet();
        List<String> unknown = actual.stream().filter(field -> !expected.contains(field)).sorted().toList();
        List<String> missing = expected.stream().filter(field -> !actual.contains(field)).sorted().toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException(label + " has unknown fields: " + unknown);
        if (!missing.isEmpty()) throw new IllegalArgumentException(label + " is missing fields: " + missing);
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static void requireString(JsonObject object, String field, String expected) {
        if (!requiredString(object, field).equals(expected)) {
            throw new IllegalArgumentException("trace " + field + " mismatch");
        }
    }

    private static void requireInt(JsonObject object, String field, int expected) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber() || value.getAsInt() != expected) {
            throw new IllegalArgumentException("trace " + field + " mismatch");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String clean(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String usage() {
        return "usage: --guard <program.yin> --input <request.json> --host <host.json> "
                + "--trace <trace.jsonl> [--approve <capability>]";
    }
}
