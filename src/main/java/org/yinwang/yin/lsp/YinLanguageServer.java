package org.yinwang.yin.lsp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yinwang.yin.Constants;
import org.yinwang.yin.Diagnostic;
import org.yinwang.yin.GeneralError;
import org.yinwang.yin.SourceSpan;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Language Server Protocol implementation over stdio. */
public final class YinLanguageServer {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int JSON_RPC_PARSE_ERROR = -32700;
    private static final int JSON_RPC_METHOD_NOT_FOUND = -32601;
    private static final int JSON_RPC_INTERNAL_ERROR = -32603;

    private final InputStream input;
    private final OutputStream output;
    private final LanguageService languageService;
    private final Map<String, String> documents = new LinkedHashMap<>();
    private boolean running = true;

    public YinLanguageServer(InputStream input, OutputStream output) {
        this(input, output, new LanguageService());
    }

    YinLanguageServer(InputStream input, OutputStream output, LanguageService languageService) {
        this.input = new BufferedInputStream(input);
        this.output = output;
        this.languageService = languageService;
    }

    public void run() throws IOException {
        while (running) {
            String payload = readMessage();
            if (payload == null) {
                return;
            }
            try {
                handle(JsonParser.parseString(payload).getAsJsonObject());
            } catch (RuntimeException error) {
                sendError(JsonNull.INSTANCE, JSON_RPC_PARSE_ERROR,
                        error.getMessage() == null ? "invalid JSON-RPC message" : error.getMessage());
            }
        }
    }

    private void handle(JsonObject message) throws IOException {
        String method = string(message, "method");
        JsonElement id = message.get("id");
        JsonObject params = object(message, "params");

        try {
            switch (method) {
                case "initialize" -> sendResult(id, initializeResult());
                case "initialized" -> {
                }
                case "shutdown" -> {
                    sendResult(id, JsonNull.INSTANCE);
                }
                case "exit" -> running = false;
                case "textDocument/didOpen" -> didOpen(params);
                case "textDocument/didChange" -> didChange(params);
                case "textDocument/didClose" -> didClose(params);
                case "textDocument/formatting" -> format(id, params);
                default -> {
                    if (id != null) {
                        sendError(id, JSON_RPC_METHOD_NOT_FOUND, "method not supported: " + method);
                    }
                }
            }
        } catch (GeneralError error) {
            if (id != null) {
                sendError(id, JSON_RPC_INTERNAL_ERROR, error.diagnostic.message());
            }
        } catch (RuntimeException error) {
            if (id != null) {
                sendError(id, JSON_RPC_INTERNAL_ERROR,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
    }

    private JsonObject initializeResult() {
        JsonObject sync = new JsonObject();
        sync.addProperty("openClose", true);
        sync.addProperty("change", 1);

        JsonObject capabilities = new JsonObject();
        capabilities.add("textDocumentSync", sync);
        capabilities.addProperty("documentFormattingProvider", true);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "Yin Language Server");
        serverInfo.addProperty("version", Constants.VERSION);

        JsonObject result = new JsonObject();
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private void didOpen(JsonObject params) throws IOException {
        JsonObject document = object(params, "textDocument");
        String uri = string(document, "uri");
        String text = string(document, "text");
        documents.put(uri, text);
        publishDiagnostics(uri, text);
    }

    private void didChange(JsonObject params) throws IOException {
        JsonObject document = object(params, "textDocument");
        String uri = string(document, "uri");
        JsonArray changes = params.getAsJsonArray("contentChanges");
        if (changes == null || changes.isEmpty()) {
            return;
        }
        JsonObject latest = changes.get(changes.size() - 1).getAsJsonObject();
        String text = string(latest, "text");
        documents.put(uri, text);
        publishDiagnostics(uri, text);
    }

    private void didClose(JsonObject params) throws IOException {
        String uri = string(object(params, "textDocument"), "uri");
        documents.remove(uri);
        sendDiagnostics(uri, new JsonArray());
    }

    private void format(JsonElement id, JsonObject params) throws IOException {
        String uri = string(object(params, "textDocument"), "uri");
        String source = documents.get(uri);
        if (source == null) {
            sendResult(id, new JsonArray());
            return;
        }
        String formatted = languageService.format(uri, source);
        JsonObject edit = new JsonObject();
        edit.add("range", range(0, 0, endLine(source), endColumn(source)));
        edit.addProperty("newText", formatted);
        JsonArray edits = new JsonArray();
        edits.add(edit);
        sendResult(id, edits);
    }

    private void publishDiagnostics(String uri, String source) throws IOException {
        JsonArray diagnostics = new JsonArray();
        for (Diagnostic diagnostic : languageService.diagnose(uri, source)) {
            diagnostics.add(toLspDiagnostic(diagnostic, source));
        }
        sendDiagnostics(uri, diagnostics);
    }

    private void sendDiagnostics(String uri, JsonArray diagnostics) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        params.add("diagnostics", diagnostics);
        sendNotification("textDocument/publishDiagnostics", params);
    }

    private JsonObject toLspDiagnostic(Diagnostic diagnostic, String source) {
        SourceSpan span = diagnostic.span();
        JsonObject result = new JsonObject();
        if (span == null) {
            result.add("range", range(0, 0, 0, 1));
        } else {
            Position end = positionAt(source, Math.max(span.start() + 1, span.end()));
            result.add("range", range(span.line(), span.column(), end.line(), end.column()));
        }
        result.addProperty("severity", 1);
        result.addProperty("code", diagnostic.code().id());
        result.addProperty("source", "yin");
        result.addProperty("message", diagnostic.message());
        return result;
    }

    private void sendResult(JsonElement id, JsonElement result) throws IOException {
        if (id == null) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result == null ? JsonNull.INSTANCE : result);
        write(response);
    }

    private void sendError(JsonElement id, int code, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        write(response);
    }

    private void sendNotification(String method, JsonObject params) throws IOException {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        notification.add("params", params);
        write(notification);
    }

    private synchronized void write(JsonObject message) throws IOException {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        output.write(("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    private String readMessage() throws IOException {
        int contentLength = -1;
        String line;
        while ((line = readHeaderLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator > 0 && line.substring(0, separator).equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(separator + 1).trim());
            }
        }
        if (line == null) {
            return null;
        }
        if (contentLength < 0) {
            throw new IOException("missing Content-Length header");
        }
        byte[] payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new IOException("unexpected end of JSON-RPC message");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private String readHeaderLine() throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return line.isEmpty() ? null : line.toString();
            }
            if (value == '\n') {
                if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }
                return line.toString();
            }
            line.append((char) value);
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonObject object = parent == null ? null : parent.getAsJsonObject(name);
        return object == null ? new JsonObject() : object;
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static JsonObject range(int startLine, int startColumn, int endLine, int endColumn) {
        JsonObject range = new JsonObject();
        range.add("start", position(startLine, startColumn));
        range.add("end", position(endLine, endColumn));
        return range;
    }

    private static JsonObject position(int line, int column) {
        JsonObject position = new JsonObject();
        position.addProperty("line", Math.max(0, line));
        position.addProperty("character", Math.max(0, column));
        return position;
    }

    private static Position positionAt(String source, int offset) {
        int bounded = Math.max(0, Math.min(offset, source.length()));
        int line = 0;
        int column = 0;
        for (int i = 0; i < bounded; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    private static int endLine(String source) {
        return positionAt(source, source.length()).line();
    }

    private static int endColumn(String source) {
        return positionAt(source, source.length()).column();
    }

    private record Position(int line, int column) {
    }
}
