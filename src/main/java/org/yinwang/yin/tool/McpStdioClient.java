package org.yinwang.yin.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A small lifecycle-complete MCP client for newline-delimited stdio servers. */
public final class McpStdioClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(
            PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05");

    private final Process process;
    private final BufferedWriter writer;
    private final BlockingQueue<Inbound> inbound = new LinkedBlockingQueue<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final Duration timeout;
    private final StringBuilder stderr = new StringBuilder();
    private volatile boolean closed;

    public McpStdioClient(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("MCP command must not be empty");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("MCP timeout must be positive");
        }
        this.timeout = timeout;
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
        try {
            initialize();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public Set<String> listTools() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        String cursor = null;
        do {
            JsonObject params = new JsonObject();
            if (cursor != null) params.addProperty("cursor", cursor);
            JsonObject result = request("tools/list", params);
            JsonElement tools = result.get("tools");
            if (tools == null || !tools.isJsonArray()) {
                throw new IOException("MCP tools/list result is missing tools");
            }
            for (JsonElement element : tools.getAsJsonArray()) {
                if (!element.isJsonObject()) throw new IOException("MCP tool must be an object");
                JsonElement name = element.getAsJsonObject().get("name");
                if (name == null || !name.isJsonPrimitive()
                        || !name.getAsJsonPrimitive().isString()) {
                    throw new IOException("MCP tool name must be a string");
                }
                if (!names.add(name.getAsString())) {
                    throw new IOException("MCP server listed a duplicate tool: " + name.getAsString());
                }
            }
            JsonElement next = result.get("nextCursor");
            cursor = next == null || next.isJsonNull() ? null : next.getAsString();
        } while (cursor != null && !cursor.isEmpty());
        return Set.copyOf(names);
    }

    /** Returns the MCP CallToolResult object as JSON for {@link McpToolAdapter}. */
    public String callTool(String name, String argumentsJson) throws IOException {
        JsonElement arguments;
        try {
            arguments = JsonParser.parseString(argumentsJson);
        } catch (RuntimeException invalid) {
            throw new IOException("tool arguments are not valid JSON", invalid);
        }
        if (!arguments.isJsonObject()) throw new IOException("tool arguments must be an object");
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return request("tools/call", params).toString();
    }

    private void initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "yin-action-gateway");
        client.addProperty("version", org.yinwang.yin.Constants.VERSION);
        params.add("clientInfo", client);
        JsonObject result = request("initialize", params);
        String negotiated = requiredString(result, "protocolVersion");
        if (!SUPPORTED_VERSIONS.contains(negotiated)) {
            throw new IOException("unsupported MCP protocol version: " + negotiated);
        }
        JsonElement capabilities = result.get("capabilities");
        if (capabilities == null || !capabilities.isJsonObject()
                || !capabilities.getAsJsonObject().has("tools")) {
            throw new IOException("MCP server did not advertise the tools capability");
        }
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/initialized");
        send(notification);
    }

    private JsonObject request(String method, JsonObject params) throws IOException {
        long id = requestIds.incrementAndGet();
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        send(request);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                cancel(id, "request timed out");
                throw failure("MCP request timed out: " + method);
            }
            Inbound message;
            try {
                message = inbound.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancel(id, "request interrupted");
                throw new IOException("MCP request interrupted: " + method, interrupted);
            }
            if (message == null) continue;
            if (message.failure != null) throw failure(message.failure);
            JsonObject object = message.message;
            if (!object.has("jsonrpc") || !object.get("jsonrpc").isJsonPrimitive()
                    || !"2.0".equals(object.get("jsonrpc").getAsString())) {
                throw failure("MCP response is not JSON-RPC 2.0");
            }
            if (object.has("method")) {
                handleServerMessage(object);
                continue;
            }
            if (!object.has("id") || object.get("id").getAsLong() != id) {
                throw failure("unexpected MCP response id");
            }
            if (object.has("error")) {
                JsonObject error = object.getAsJsonObject("error");
                String text = error != null && error.has("message")
                        ? error.get("message").getAsString() : "unknown JSON-RPC error";
                throw failure("MCP " + method + " failed: " + text);
            }
            JsonElement result = object.get("result");
            if (result == null || !result.isJsonObject()) {
                throw failure("MCP " + method + " response is missing an object result");
            }
            return result.getAsJsonObject();
        }
    }

    private void handleServerMessage(JsonObject message) throws IOException {
        if (!message.has("id")) return; // Server notification.
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", message.get("id"));
        if ("ping".equals(requiredString(message, "method"))) {
            response.add("result", new JsonObject());
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "client method not supported");
            response.add("error", error);
        }
        send(response);
    }

    private void cancel(long id, String reason) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("requestId", id);
            params.addProperty("reason", reason);
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "notifications/cancelled");
            notification.add("params", params);
            send(notification);
        } catch (IOException ignored) {
            // The original timeout or interruption remains the useful failure.
        }
    }

    private synchronized void send(JsonObject message) throws IOException {
        if (closed) throw new IOException("MCP client is closed");
        String line = message.toString();
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IOException("MCP message contains an embedded newline");
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private void startStdoutReader() {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonElement parsed = JsonParser.parseString(line);
                        if (!parsed.isJsonObject()) {
                            inbound.offer(Inbound.failure("MCP stdout message is not an object"));
                            return;
                        }
                        inbound.offer(Inbound.message(parsed.getAsJsonObject()));
                    } catch (RuntimeException invalid) {
                        inbound.offer(Inbound.failure("invalid JSON on MCP stdout"));
                        return;
                    }
                }
                inbound.offer(Inbound.failure("MCP server closed stdout"));
            } catch (IOException failure) {
                inbound.offer(Inbound.failure("failed to read MCP stdout: " + failure.getMessage()));
            }
        }, "yin-mcp-stdout");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void startStderrReader() {
        Thread errorThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderr) {
                        if (stderr.length() < 4096) {
                            if (!stderr.isEmpty()) stderr.append(" | ");
                            stderr.append(line, 0, Math.min(line.length(), 512));
                        }
                    }
                }
            } catch (IOException ignored) { }
        }, "yin-mcp-stderr");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    private IOException failure(String message) {
        synchronized (stderr) {
            if (!stderr.isEmpty()) message += " (server stderr: " + stderr + ")";
        }
        return new IOException(message);
    }

    private static String requiredString(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("MCP " + field + " must be a string");
        }
        return value.getAsString();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { writer.close(); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private record Inbound(JsonObject message, String failure) {
        static Inbound message(JsonObject value) { return new Inbound(value, null); }
        static Inbound failure(String value) { return new Inbound(null, value); }
    }
}
