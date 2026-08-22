package org.yinwang.yin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yinwang.yin.json.JsonCodec;
import org.yinwang.yin.tool.McpStdioClient;
import org.yinwang.yin.tool.McpToolAdapter;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A request-bound, deny-by-default gateway from typed Yin tools to MCP stdio servers. */
public final class ActionGatewayRuntime {
    private static final int TRACE_VERSION = 1;
    private static final int CONFIG_VERSION = 1;
    private static final int APPROVAL_VERSION = 1;
    private static final long MAX_NONCE_STORE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> INTENT_FIELDS = Set.of(
            "requestId", "actor", "agent", "server", "tool", "capability",
            "effect", "resource", "arguments");
    private static final Set<String> HOST_FIELDS = Set.of(
            "version", "timeoutMillis", "servers");
    private static final Set<String> SERVER_FIELDS = Set.of(
            "name", "command", "cwd", "tools");
    private static final Set<String> TOOL_FIELDS = Set.of(
            "name", "remoteName", "capability", "effect", "approvalRequired");
    private static final Set<String> APPROVAL_FIELDS = Set.of(
            "version", "requestId", "actor", "agent", "server", "tool",
            "capability", "effect", "resource", "programSha256", "intentSha256",
            "argumentsSha256", "hostSha256", "expiresAt", "nonce", "approvedBy");

    private ActionGatewayRuntime() { }

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
        GatewayHost gateway = null;
        try {
            Inputs inputs = Inputs.read(options.program, options.intent, options.host);
            Map<String, RuntimeContext.ToolDescriptor> declarations = declarations(
                    options.program, inputs.programSource);
            inputs.host.validate(declarations);
            inputs.host.mappingFor(inputs.intent);
            Approval approval = options.approval == null ? null : Approval.read(options.approval);

            trace = new TraceWriter(options.trace);
            JsonObject started = event("run-started");
            started.addProperty("traceVersion", TRACE_VERSION);
            started.addProperty("runtimeVersion", Constants.VERSION);
            started.addProperty("mode", "action-gateway");
            started.addProperty("runId", UUID.randomUUID().toString());
            started.addProperty("source", options.program.toString());
            started.addProperty("sourceSha256", inputs.programSha256);
            started.addProperty("inputSha256", inputs.intentSha256);
            started.addProperty("hostSha256", inputs.hostSha256);
            started.addProperty("requestId", inputs.intent.requestId);
            started.addProperty("actor", inputs.intent.actor);
            started.addProperty("agent", inputs.intent.agent);
            started.addProperty("server", inputs.intent.server);
            started.addProperty("tool", inputs.intent.tool);
            started.addProperty("capability", inputs.intent.capability);
            started.addProperty("effect", inputs.intent.effect.sourceName());
            started.addProperty("resource", inputs.intent.resource);
            started.addProperty("argumentsSha256", inputs.argumentsSha256);
            if (approval != null) {
                started.addProperty("approvalSha256", sha256(
                        Files.readString(options.approval, StandardCharsets.UTF_8)));
                started.addProperty("approvedBy", approval.approvedBy);
                started.addProperty("approvalExpiresAt", approval.expiresAt.toString());
            }
            started.add("capabilities", JsonParser.parseString(
                    CapabilityManifest.toJson(List.copyOf(declarations.values()))));
            trace.write(started);

            TraceWriter activeTrace = trace;
            Approval activeApproval = approval;
            gateway = new GatewayHost(inputs.host);
            RuntimeContext.AuthorizationPolicy authorization = request -> {
                Authorization result = authorize(request, inputs, activeApproval, options.nonceStore);
                JsonObject decision = event("authorization");
                decision.addProperty("requestId", inputs.intent.requestId);
                decision.addProperty("tool", request.descriptor().name());
                decision.addProperty("remoteTool", inputs.intent.tool);
                decision.addProperty("capability", request.descriptor().capability());
                decision.addProperty("effect", request.descriptor().effect().sourceName());
                decision.addProperty("inputSha256", sha256(canonical(
                        JsonParser.parseString(request.inputJson()))));
                decision.addProperty("approved", result.approved);
                decision.addProperty("reason", result.reason);
                activeTrace.writeUnchecked(decision);
                return result.approved;
            };

            RuntimeContext context = new RuntimeContext(
                    error::println,
                    () -> inputs.canonicalIntent,
                    List.of(),
                    path -> { throw new GeneralError("read-text is unavailable in --gateway mode"); },
                    gateway.handlers(),
                    authorization,
                    audit -> {
                        JsonObject toolEvent = event("tool-result");
                        toolEvent.addProperty("requestId", inputs.intent.requestId);
                        toolEvent.addProperty("tool", audit.descriptor().name());
                        toolEvent.addProperty("remoteTool", inputs.intent.tool);
                        toolEvent.addProperty("capability", audit.descriptor().capability());
                        toolEvent.addProperty("status", audit.status());
                        toolEvent.addProperty("inputSha256", sha256(audit.inputJson()));
                        toolEvent.addProperty("outputSha256", sha256(audit.outputJson()));
                        activeTrace.writeUnchecked(toolEvent);
                    });

            Value value = new Interpreter(options.program.toString())
                    .interpSource(options.program.toString(), inputs.programSource, context);
            Rendered rendered = render(value, "--gateway");
            writeCompleted(trace, rendered.exitCode, rendered.channel, rendered.output);
            rendered.print(output, error);
            return rendered.exitCode;
        } catch (IOException | RuntimeException failure) {
            String message = clean(failure);
            if (trace != null) {
                try { writeCompleted(trace, 1, "stderr", message); }
                catch (IOException ignored) { }
            }
            error.println(message);
            return 1;
        } finally {
            if (gateway != null) gateway.close();
            if (trace != null) trace.closeUnchecked();
        }
    }

    /** Creates out-of-band approval evidence; the caller remains responsible for approver identity. */
    public static int approvalRequest(String[] args, PrintStream output, PrintStream error) {
        ApprovalOptions options;
        try {
            options = ApprovalOptions.parse(args);
        } catch (UsageFailure failure) {
            error.println(failure.getMessage());
            error.println(approvalUsage());
            return 2;
        }
        try {
            Inputs inputs = Inputs.read(options.program, options.intent, options.host);
            Map<String, RuntimeContext.ToolDescriptor> declarations = declarations(
                    options.program, inputs.programSource);
            inputs.host.validate(declarations);
            ToolBinding binding = inputs.host.mappingFor(inputs.intent);
            if (!binding.approvalRequired) {
                throw new IllegalArgumentException(
                        "the selected tool does not require approval: " + binding.name);
            }
            Instant expiresAt = Instant.now().plusSeconds(options.expiresInSeconds);
            JsonObject approval = new JsonObject();
            approval.addProperty("version", APPROVAL_VERSION);
            approval.addProperty("requestId", inputs.intent.requestId);
            approval.addProperty("actor", inputs.intent.actor);
            approval.addProperty("agent", inputs.intent.agent);
            approval.addProperty("server", inputs.intent.server);
            approval.addProperty("tool", inputs.intent.tool);
            approval.addProperty("capability", inputs.intent.capability);
            approval.addProperty("effect", inputs.intent.effect.sourceName());
            approval.addProperty("resource", inputs.intent.resource);
            approval.addProperty("programSha256", inputs.programSha256);
            approval.addProperty("intentSha256", inputs.intentSha256);
            approval.addProperty("argumentsSha256", inputs.argumentsSha256);
            approval.addProperty("hostSha256", inputs.hostSha256);
            approval.addProperty("expiresAt", expiresAt.toString());
            approval.addProperty("nonce", UUID.randomUUID().toString());
            approval.addProperty("approvedBy", options.approvedBy);
            Path parent = options.output.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(options.output, canonical(approval) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            output.println(options.output);
            return 0;
        } catch (IOException | RuntimeException failure) {
            error.println(clean(failure));
            return 1;
        }
    }

    private static Map<String, RuntimeContext.ToolDescriptor> declarations(
            Path program, String source) {
        ModuleBoundary.requireSingleFile(program.toString(), source, "--gateway");
        TypeChecker checker = new TypeChecker(program.toString());
        checker.typecheckSource(program.toString(), source);
        Map<String, RuntimeContext.ToolDescriptor> declarations = new LinkedHashMap<>();
        for (RuntimeContext.ToolDescriptor descriptor : checker.tools()) {
            if (declarations.putIfAbsent(descriptor.name(), descriptor) != null) {
                throw new IllegalArgumentException("duplicate tool declaration: " + descriptor.name());
            }
        }
        return declarations;
    }

    private static Authorization authorize(RuntimeContext.ToolRequest request, Inputs inputs,
                                           Approval approval, Path nonceStore) {
        ToolBinding binding = inputs.host.tools.get(request.descriptor().name());
        if (binding == null) return Authorization.denied("tool-not-installed");
        if (!binding.server.name.equals(inputs.intent.server)
                || !binding.remoteName.equals(inputs.intent.tool)) {
            return Authorization.denied("intent-tool-mismatch");
        }
        if (!binding.capability.equals(inputs.intent.capability)
                || !binding.capability.equals(request.descriptor().capability())) {
            return Authorization.denied("capability-mismatch");
        }
        if (binding.effect != inputs.intent.effect
                || binding.effect != request.descriptor().effect()) {
            return Authorization.denied("effect-mismatch");
        }
        String actualArguments;
        try {
            actualArguments = canonical(JsonParser.parseString(request.inputJson()));
        } catch (RuntimeException invalid) {
            return Authorization.denied("invalid-tool-arguments");
        }
        if (!actualArguments.equals(inputs.canonicalArguments)) {
            return Authorization.denied("arguments-mismatch");
        }
        if (!binding.approvalRequired) return Authorization.approved("installed-read-capability");
        if (approval == null) return Authorization.denied("approval-required");
        try {
            approval.validate(inputs);
            if (nonceStore == null) return Authorization.denied("nonce-store-required");
            NonceStore.consume(nonceStore, approval, inputs.intent.requestId);
            return Authorization.approved("request-bound-approval");
        } catch (ApprovalFailure failure) {
            return Authorization.denied(failure.getMessage());
        } catch (IOException failure) {
            return Authorization.denied("nonce-store-failed");
        } catch (RuntimeException failure) {
            return Authorization.denied("nonce-store-invalid");
        }
    }

    private record Authorization(boolean approved, String reason) {
        static Authorization approved(String reason) { return new Authorization(true, reason); }
        static Authorization denied(String reason) { return new Authorization(false, reason); }
    }

    private record ActionIntent(String requestId, String actor, String agent, String server,
                                String tool, String capability, RuntimeContext.Effect effect,
                                String resource, JsonObject arguments) {
        static ActionIntent read(JsonObject object) {
            exactFields(object, INTENT_FIELDS, "action intent");
            JsonElement arguments = object.get("arguments");
            if (arguments == null || !arguments.isJsonObject()) {
                throw new IllegalArgumentException("action intent arguments must be an object");
            }
            return new ActionIntent(
                    nonBlank(object, "requestId"), nonBlank(object, "actor"),
                    nonBlank(object, "agent"), nonBlank(object, "server"),
                    nonBlank(object, "tool"), nonBlank(object, "capability"),
                    RuntimeContext.Effect.parse(nonBlank(object, "effect")),
                    nonBlank(object, "resource"), arguments.getAsJsonObject());
        }
    }

    private record Inputs(ActionIntent intent, HostConfig host, String programSource,
                          String canonicalIntent,
                          String canonicalArguments, String programSha256, String intentSha256,
                          String argumentsSha256, String hostSha256) {
        static Inputs read(Path program, Path intentPath, Path hostPath) throws IOException {
            String programSource = Files.readString(program, StandardCharsets.UTF_8);
            String intentSource = Files.readString(intentPath, StandardCharsets.UTF_8);
            String hostSource = Files.readString(hostPath, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(intentSource);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("action intent must be an object");
            ActionIntent intent = ActionIntent.read(parsed.getAsJsonObject());
            String canonicalIntent = canonical(parsed);
            String canonicalArguments = canonical(intent.arguments);
            return new Inputs(intent, HostConfig.read(hostPath, hostSource), programSource,
                    canonicalIntent, canonicalArguments, sha256(programSource),
                    sha256(canonicalIntent), sha256(canonicalArguments), sha256(hostSource));
        }
    }

    private record ToolBinding(String name, String remoteName, String capability,
                               RuntimeContext.Effect effect, boolean approvalRequired,
                               ServerConfig server) { }

    private record ServerConfig(String name, List<String> command, Path cwd,
                                List<ToolBinding> tools) { }

    private static final class HostConfig {
        final Duration timeout;
        final Map<String, ToolBinding> tools;

        private HostConfig(Duration timeout, Map<String, ToolBinding> tools) {
            this.timeout = timeout;
            this.tools = Map.copyOf(tools);
        }

        static HostConfig read(Path file, String source) {
            JsonElement parsed = JsonParser.parseString(source);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("gateway host must be an object");
            JsonObject object = parsed.getAsJsonObject();
            exactFields(object, HOST_FIELDS, "gateway host");
            requireInt(object, "version", CONFIG_VERSION);
            int timeoutMillis = positiveInt(object, "timeoutMillis");
            if (timeoutMillis > 300_000) {
                throw new IllegalArgumentException("timeoutMillis must not exceed 300000");
            }
            JsonElement serversElement = object.get("servers");
            if (serversElement == null || !serversElement.isJsonArray()) {
                throw new IllegalArgumentException("gateway host servers must be an array");
            }
            Path base = file.toAbsolutePath().normalize().getParent();
            Map<String, ToolBinding> allTools = new LinkedHashMap<>();
            Set<String> serverNames = new HashSet<>();
            for (JsonElement serverElement : serversElement.getAsJsonArray()) {
                if (!serverElement.isJsonObject()) {
                    throw new IllegalArgumentException("gateway server must be an object");
                }
                JsonObject serverObject = serverElement.getAsJsonObject();
                exactFields(serverObject, SERVER_FIELDS, "gateway server");
                String name = nonBlank(serverObject, "name");
                if (!serverNames.add(name)) throw new IllegalArgumentException("duplicate server: " + name);
                List<String> command = stringArray(serverObject, "command");
                if (command.isEmpty()) throw new IllegalArgumentException("server command must not be empty");
                Path cwd = base.resolve(nonBlank(serverObject, "cwd")).normalize();
                if (!Files.isDirectory(cwd)) {
                    throw new IllegalArgumentException("server cwd is not a directory: " + cwd);
                }
                JsonElement toolsElement = serverObject.get("tools");
                if (toolsElement == null || !toolsElement.isJsonArray()) {
                    throw new IllegalArgumentException("gateway server tools must be an array");
                }
                List<ToolBinding> provisional = new ArrayList<>();
                ServerConfig shell = new ServerConfig(name, command, cwd, provisional);
                for (JsonElement toolElement : toolsElement.getAsJsonArray()) {
                    if (!toolElement.isJsonObject()) {
                        throw new IllegalArgumentException("gateway tool must be an object");
                    }
                    JsonObject tool = toolElement.getAsJsonObject();
                    exactFields(tool, TOOL_FIELDS, "gateway tool");
                    RuntimeContext.Effect effect = RuntimeContext.Effect.parse(nonBlank(tool, "effect"));
                    boolean approval = requiredBoolean(tool, "approvalRequired");
                    if (effect != RuntimeContext.Effect.READ && !approval) {
                        throw new IllegalArgumentException(
                                "non-read gateway tools must require approval: " + nonBlank(tool, "name"));
                    }
                    ToolBinding binding = new ToolBinding(
                            nonBlank(tool, "name"), nonBlank(tool, "remoteName"),
                            nonBlank(tool, "capability"), effect, approval, shell);
                    if (allTools.putIfAbsent(binding.name, binding) != null) {
                        throw new IllegalArgumentException("duplicate installed tool: " + binding.name);
                    }
                    provisional.add(binding);
                }
            }
            return new HostConfig(Duration.ofMillis(timeoutMillis), allTools);
        }

        void validate(Map<String, RuntimeContext.ToolDescriptor> declarations) {
            for (ToolBinding binding : tools.values()) {
                RuntimeContext.ToolDescriptor declared = declarations.get(binding.name);
                if (declared == null) {
                    throw new IllegalArgumentException("gateway installs undeclared tool: " + binding.name);
                }
                if (!binding.capability.equals(declared.capability())) {
                    throw new IllegalArgumentException("gateway capability mismatch: " + binding.name);
                }
                if (binding.effect != declared.effect()) {
                    throw new IllegalArgumentException("gateway effect mismatch: " + binding.name);
                }
                if (binding.approvalRequired != declared.approvalRequired()) {
                    throw new IllegalArgumentException("gateway approval mismatch: " + binding.name);
                }
            }
        }

        ToolBinding mappingFor(ActionIntent intent) {
            for (ToolBinding binding : tools.values()) {
                if (binding.server.name.equals(intent.server)
                        && binding.remoteName.equals(intent.tool)
                        && binding.capability.equals(intent.capability)
                        && binding.effect == intent.effect) return binding;
            }
            throw new IllegalArgumentException("action intent does not match an installed tool");
        }
    }

    private static final class GatewayHost implements AutoCloseable {
        private final HostConfig config;
        private final Map<String, McpStdioClient> clients = new HashMap<>();
        private final Map<String, Set<String>> advertisedTools = new HashMap<>();

        GatewayHost(HostConfig config) { this.config = config; }

        Map<String, RuntimeContext.ToolHandler> handlers() {
            Map<String, RuntimeContext.ToolHandler> handlers = new LinkedHashMap<>();
            for (ToolBinding binding : config.tools.values()) {
                handlers.put(binding.name, McpToolAdapter.handler(binding.remoteName,
                        (remoteName, inputJson) -> client(binding).callTool(remoteName, inputJson)));
            }
            return handlers;
        }

        private synchronized McpStdioClient client(ToolBinding binding) throws IOException {
            String name = binding.server.name;
            McpStdioClient client = clients.get(name);
            if (client == null) {
                client = new McpStdioClient(binding.server.command, binding.server.cwd, config.timeout);
                try {
                    Set<String> available = client.listTools();
                    clients.put(name, client);
                    advertisedTools.put(name, available);
                } catch (IOException | RuntimeException failure) {
                    client.close();
                    throw failure;
                }
            }
            if (!advertisedTools.get(name).contains(binding.remoteName)) {
                throw new IOException("MCP server " + name + " does not advertise tool "
                        + binding.remoteName);
            }
            return client;
        }

        @Override public synchronized void close() {
            for (McpStdioClient client : clients.values()) client.close();
            clients.clear();
        }
    }

    private record Approval(String requestId, String actor, String agent, String server,
                            String tool, String capability, RuntimeContext.Effect effect,
                            String resource, String programSha256, String intentSha256,
                            String argumentsSha256, String hostSha256, Instant expiresAt,
                            String nonce, String approvedBy) {
        static Approval read(Path path) throws IOException {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("approval must be an object");
            JsonObject object = parsed.getAsJsonObject();
            exactFields(object, APPROVAL_FIELDS, "approval");
            requireInt(object, "version", APPROVAL_VERSION);
            Instant expiresAt;
            try { expiresAt = Instant.parse(nonBlank(object, "expiresAt")); }
            catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("approval expiresAt must be an ISO-8601 instant");
            }
            String nonce = nonBlank(object, "nonce");
            if (nonce.length() < 16 || nonce.length() > 200) {
                throw new IllegalArgumentException("approval nonce must contain 16 to 200 characters");
            }
            return new Approval(
                    nonBlank(object, "requestId"), nonBlank(object, "actor"),
                    nonBlank(object, "agent"), nonBlank(object, "server"),
                    nonBlank(object, "tool"), nonBlank(object, "capability"),
                    RuntimeContext.Effect.parse(nonBlank(object, "effect")),
                    nonBlank(object, "resource"), nonBlank(object, "programSha256"),
                    nonBlank(object, "intentSha256"), nonBlank(object, "argumentsSha256"),
                    nonBlank(object, "hostSha256"), expiresAt, nonce,
                    nonBlank(object, "approvedBy"));
        }

        void validate(Inputs inputs) {
            ActionIntent intent = inputs.intent;
            if (!requestId.equals(intent.requestId) || !actor.equals(intent.actor)
                    || !agent.equals(intent.agent) || !server.equals(intent.server)
                    || !tool.equals(intent.tool) || !capability.equals(intent.capability)
                    || effect != intent.effect || !resource.equals(intent.resource)) {
                throw new ApprovalFailure("approval-intent-mismatch");
            }
            if (!programSha256.equals(inputs.programSha256)) {
                throw new ApprovalFailure("approval-program-mismatch");
            }
            if (!intentSha256.equals(inputs.intentSha256)) {
                throw new ApprovalFailure("approval-input-mismatch");
            }
            if (!argumentsSha256.equals(inputs.argumentsSha256)) {
                throw new ApprovalFailure("approval-arguments-mismatch");
            }
            if (!hostSha256.equals(inputs.hostSha256)) {
                throw new ApprovalFailure("approval-host-mismatch");
            }
            Instant now = Instant.now();
            if (!expiresAt.isAfter(now)) throw new ApprovalFailure("approval-expired");
            if (expiresAt.isAfter(now.plusSeconds(86_400))) {
                throw new ApprovalFailure("approval-expiry-too-long");
            }
        }
    }

    private static final class NonceStore {
        static void consume(Path path, Approval approval, String requestId) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                long size = channel.size();
                if (size > MAX_NONCE_STORE_BYTES) {
                    throw new IOException("nonce store exceeds size limit");
                }
                ByteBuffer existing = ByteBuffer.allocate((int) size);
                channel.position(0);
                while (existing.hasRemaining() && channel.read(existing) >= 0) { }
                String contents = new String(existing.array(), StandardCharsets.UTF_8);
                for (String line : contents.split("\\R")) {
                    if (line.isBlank()) continue;
                    JsonElement parsed = JsonParser.parseString(line);
                    if (parsed.isJsonObject() && parsed.getAsJsonObject().has("nonce")
                            && approval.nonce.equals(
                            parsed.getAsJsonObject().get("nonce").getAsString())) {
                        throw new ApprovalFailure("approval-already-used");
                    }
                }
                JsonObject consumed = new JsonObject();
                consumed.addProperty("nonce", approval.nonce);
                consumed.addProperty("requestId", requestId);
                consumed.addProperty("approvedBy", approval.approvedBy);
                consumed.addProperty("consumedAt", Instant.now().toString());
                byte[] bytes = (canonical(consumed) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
                channel.position(channel.size());
                ByteBuffer record = ByteBuffer.wrap(bytes);
                while (record.hasRemaining()) channel.write(record);
                channel.force(true);
            }
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

        synchronized void write(JsonObject value) throws IOException {
            value.addProperty("sequence", ++sequence);
            value.addProperty("timestamp", Instant.now().toString());
            value.addProperty("previousHash", previousHash);
            String hash = sha256(value.toString());
            value.addProperty("eventHash", hash);
            writer.write(value.toString());
            writer.newLine();
            writer.flush();
            previousHash = hash;
        }

        void writeUnchecked(JsonObject value) {
            try { write(value); }
            catch (IOException failure) {
                throw new IllegalStateException("cannot write gateway trace", failure);
            }
        }

        @Override public void close() throws IOException { writer.close(); }
        void closeUnchecked() { try { close(); } catch (IOException ignored) { } }
    }

    private record Rendered(int exitCode, String channel, String output) {
        void print(PrintStream stdout, PrintStream stderr) {
            (channel.equals("stdout") ? stdout : stderr).println(output);
        }
    }

    private static Rendered render(Value value, String command) {
        if (value instanceof StringValue text) return new Rendered(0, "stdout", text.value);
        if (value instanceof ResultValue result) {
            if (result.tag() == ResultValue.Tag.OK && result.payload() instanceof StringValue text) {
                return new Rendered(0, "stdout", text.value);
            }
            if (result.tag() == ResultValue.Tag.ERR) {
                return new Rendered(1, "stdout", JsonCodec.encode(result.payload()));
            }
        }
        throw new GeneralError(command + " expects String or (Result String E), got: " + value);
    }

    private static void writeCompleted(TraceWriter trace, int exitCode, String channel,
                                       String output) throws IOException {
        JsonObject completed = event("run-completed");
        completed.addProperty("exitCode", exitCode);
        completed.addProperty("channel", channel);
        completed.addProperty("output", output);
        completed.addProperty("outputSha256", sha256(output));
        trace.write(completed);
    }

    private record Options(Path program, Path intent, Path host, Path trace,
                           Path approval, Path nonceStore) {
        static Options parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) {
                throw new UsageFailure("missing program path");
            }
            Path program = Path.of(args[0]);
            Map<String, Path> paths = parsePaths(args, 1,
                    Set.of("--intent", "--host", "--trace", "--approval", "--nonce-store"));
            Path intent = paths.get("--intent");
            Path host = paths.get("--host");
            Path trace = paths.get("--trace");
            if (intent == null || host == null || trace == null) {
                throw new UsageFailure("--intent, --host, and --trace are required");
            }
            if ((paths.get("--approval") == null) != (paths.get("--nonce-store") == null)) {
                throw new UsageFailure("--approval and --nonce-store must be supplied together");
            }
            return new Options(program, intent, host, trace,
                    paths.get("--approval"), paths.get("--nonce-store"));
        }
    }

    private record ApprovalOptions(Path program, Path intent, Path host, Path output,
                                   String approvedBy, long expiresInSeconds) {
        static ApprovalOptions parse(String[] args) {
            if (args.length == 0 || args[0].startsWith("--")) {
                throw new UsageFailure("missing program path");
            }
            Path program = Path.of(args[0]);
            Path intent = null;
            Path host = null;
            Path output = null;
            String approvedBy = null;
            Long expires = null;
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (index + 1 >= args.length) throw new UsageFailure("missing value for " + option);
                String value = args[++index];
                switch (option) {
                    case "--intent" -> intent = unique(intent, value, option);
                    case "--host" -> host = unique(host, value, option);
                    case "--out" -> output = unique(output, value, option);
                    case "--approved-by" -> {
                        if (approvedBy != null) throw new UsageFailure("duplicate option: " + option);
                        approvedBy = value;
                    }
                    case "--expires-in-seconds" -> {
                        if (expires != null) throw new UsageFailure("duplicate option: " + option);
                        try { expires = Long.parseLong(value); }
                        catch (NumberFormatException invalid) {
                            throw new UsageFailure("--expires-in-seconds must be an integer");
                        }
                    }
                    default -> throw new UsageFailure("unknown option: " + option);
                }
            }
            if (intent == null || host == null || output == null
                    || approvedBy == null || approvedBy.isBlank() || expires == null) {
                throw new UsageFailure("all approval-request options are required");
            }
            if (expires < 1 || expires > 86_400) {
                throw new UsageFailure("--expires-in-seconds must be between 1 and 86400");
            }
            return new ApprovalOptions(program, intent, host, output, approvedBy, expires);
        }
    }

    private static Map<String, Path> parsePaths(String[] args, int start, Set<String> allowed) {
        Map<String, Path> values = new HashMap<>();
        for (int index = start; index < args.length; index++) {
            String option = args[index];
            if (!allowed.contains(option)) throw new UsageFailure("unknown option: " + option);
            if (index + 1 >= args.length) throw new UsageFailure("missing value for " + option);
            if (values.putIfAbsent(option, Path.of(args[++index])) != null) {
                throw new UsageFailure("duplicate option: " + option);
            }
        }
        return values;
    }

    private static Path unique(Path existing, String value, String option) {
        if (existing != null) throw new UsageFailure("duplicate option: " + option);
        return Path.of(value);
    }

    private static JsonObject event(String type) {
        JsonObject value = new JsonObject();
        value.addProperty("type", type);
        return value;
    }

    /** Produces a stable JSON representation with recursively sorted object keys. */
    static String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return value.toString();
        if (value.isJsonArray()) {
            StringBuilder result = new StringBuilder("[");
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                if (index > 0) result.append(',');
                result.append(canonical(array.get(index)));
            }
            return result.append(']').toString();
        }
        StringBuilder result = new StringBuilder("{");
        List<String> names = value.getAsJsonObject().keySet().stream()
                .sorted(Comparator.naturalOrder()).toList();
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) result.append(',');
            String name = names.get(index);
            result.append(new com.google.gson.JsonPrimitive(name));
            result.append(':').append(canonical(value.getAsJsonObject().get(name)));
        }
        return result.append('}').toString();
    }

    private static void exactFields(JsonObject object, Set<String> expected, String label) {
        List<String> unknown = object.keySet().stream()
                .filter(field -> !expected.contains(field)).sorted().toList();
        List<String> missing = expected.stream()
                .filter(field -> !object.has(field)).sorted().toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException(label + " has unknown fields: " + unknown);
        if (!missing.isEmpty()) throw new IllegalArgumentException(label + " is missing fields: " + missing);
    }

    private static String nonBlank(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.getAsString();
    }

    private static boolean requiredBoolean(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static void requireInt(JsonObject object, String field, int expected) {
        if (positiveOrZeroInt(object, field) != expected) {
            throw new IllegalArgumentException(field + " must be " + expected);
        }
    }

    private static int positiveInt(JsonObject object, String field) {
        int value = positiveOrZeroInt(object, field);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static int positiveOrZeroInt(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static List<String> stringArray(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || element.getAsString().isBlank()) {
                throw new IllegalArgumentException(field + " entries must be non-blank strings");
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
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
        return "usage: --gateway <program.yin> --intent <intent.json> --host <gateway.json> "
                + "--trace <trace.jsonl> [--approval <approval.json> "
                + "--nonce-store <used-approvals.jsonl>]";
    }

    private static String approvalUsage() {
        return "usage: --approval-request <program.yin> --intent <intent.json> "
                + "--host <gateway.json> --out <approval.json> --approved-by <identity> "
                + "--expires-in-seconds <seconds>";
    }

    private static final class UsageFailure extends RuntimeException {
        UsageFailure(String message) { super(message); }
    }
    private static final class ApprovalFailure extends RuntimeException {
        ApprovalFailure(String message) { super(message); }
    }
}
