package org.yinwang.yin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.yinwang.yin.lsp.YinLanguageServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageServerIntegrationTest {
    private static final Gson GSON = new Gson();

    @Test
    void initializesWithFullSyncAndFormattingCapabilities() throws Exception {
        List<JsonObject> output = run(
                request(1, "initialize", new JsonObject()),
                request(2, "shutdown", new JsonObject()),
                notification("exit", new JsonObject()));

        JsonObject initialize = output.get(0).getAsJsonObject("result");
        JsonObject capabilities = initialize.getAsJsonObject("capabilities");
        assertEquals(1, capabilities.getAsJsonObject("textDocumentSync").get("change").getAsInt());
        assertTrue(capabilities.get("documentFormattingProvider").getAsBoolean());
        assertEquals("0.8.0", initialize.getAsJsonObject("serverInfo").get("version").getAsString());
        assertTrue(output.get(1).has("result"), output.get(1)::toString);
        assertTrue(output.get(1).get("result").isJsonNull());
    }

    @Test
    void publishesAndClearsDiagnosticsForFullDocumentChanges() throws Exception {
        String uri = "file:///workspace/main.yin";
        JsonObject openDocument = new JsonObject();
        openDocument.addProperty("uri", uri);
        openDocument.addProperty("languageId", "yin");
        openDocument.addProperty("version", 1);
        openDocument.addProperty("text", "(+ 1 true)");
        JsonObject open = new JsonObject();
        open.add("textDocument", openDocument);

        JsonObject changedDocument = new JsonObject();
        changedDocument.addProperty("uri", uri);
        changedDocument.addProperty("version", 2);
        JsonArray changes = new JsonArray();
        JsonObject replacement = new JsonObject();
        replacement.addProperty("text", "(+ 1 2)");
        changes.add(replacement);
        JsonObject change = new JsonObject();
        change.add("textDocument", changedDocument);
        change.add("contentChanges", changes);

        List<JsonObject> output = run(
                notification("textDocument/didOpen", open),
                notification("textDocument/didChange", change),
                notification("exit", new JsonObject()));

        JsonArray first = output.get(0).getAsJsonObject("params").getAsJsonArray("diagnostics");
        JsonArray second = output.get(1).getAsJsonObject("params").getAsJsonArray("diagnostics");
        assertEquals(1, first.size());
        assertEquals("YIN0001", first.get(0).getAsJsonObject().get("code").getAsString());
        assertTrue(second.isEmpty());
    }

    @Test
    void returnsOneWholeDocumentFormattingEdit() throws Exception {
        String uri = "file:///workspace/format.yin";
        JsonObject document = new JsonObject();
        document.addProperty("uri", uri);
        document.addProperty("languageId", "yin");
        document.addProperty("version", 1);
        document.addProperty("text", "(+   1 2)");
        JsonObject open = new JsonObject();
        open.add("textDocument", document);

        JsonObject formatting = new JsonObject();
        JsonObject formattingDocument = new JsonObject();
        formattingDocument.addProperty("uri", uri);
        formatting.add("textDocument", formattingDocument);

        List<JsonObject> output = run(
                notification("textDocument/didOpen", open),
                request(7, "textDocument/formatting", formatting),
                notification("exit", new JsonObject()));

        assertTrue(output.get(0).getAsJsonObject("params").getAsJsonArray("diagnostics").isEmpty());
        JsonArray edits = output.get(1).getAsJsonArray("result");
        assertEquals(1, edits.size());
        assertEquals("(+ 1 2)\n", edits.get(0).getAsJsonObject().get("newText").getAsString());
        assertFalse(edits.get(0).getAsJsonObject().getAsJsonObject("range").entrySet().isEmpty());
    }

    private List<JsonObject> run(JsonObject... messages) throws Exception {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (JsonObject message : messages) {
            input.write(frame(message));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new YinLanguageServer(new ByteArrayInputStream(input.toByteArray()), output).run();

        return decode(output.toByteArray());
    }

    private JsonObject request(int id, String method, JsonObject params) {
        JsonObject request = notification(method, params);
        request.addProperty("id", id);
        return request;
    }

    private JsonObject notification(String method, JsonObject params) {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        notification.add("params", params);
        return notification;
    }

    private byte[] frame(JsonObject message) {
        byte[] payload = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] framed = new byte[header.length + payload.length];
        System.arraycopy(header, 0, framed, 0, header.length);
        System.arraycopy(payload, 0, framed, header.length, payload.length);
        return framed;
    }

    private List<JsonObject> decode(byte[] bytes) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        List<JsonObject> messages = new ArrayList<>();
        while (input.available() > 0) {
            int contentLength = -1;
            String line;
            while (!(line = readLine(input)).isEmpty()) {
                if (line.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                }
            }
            byte[] payload = input.readNBytes(contentLength);
            messages.add(JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject());
        }
        return messages;
    }

    private String readLine(ByteArrayInputStream input) {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0 && value != '\n') {
            if (value != '\r') {
                line.append((char) value);
            }
        }
        return line.toString();
    }
}
