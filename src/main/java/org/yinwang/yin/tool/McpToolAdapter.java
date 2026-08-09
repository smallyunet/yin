package org.yinwang.yin.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yinwang.yin.RuntimeContext;

/**
 * Transport-neutral adapter for MCP CallToolResult payloads.
 *
 * <p>The source declaration remains the trusted authority and type contract.
 * Server annotations are deliberately not used for authorization.</p>
 */
public final class McpToolAdapter {
    private McpToolAdapter() { }

    @FunctionalInterface
    public interface Caller {
        String call(String toolName, String argumentsJson) throws Exception;
    }

    public static RuntimeContext.ToolHandler handler(String remoteToolName, Caller caller) {
        return inputJson -> adapt(remoteToolName, caller.call(remoteToolName, inputJson));
    }

    static RuntimeContext.ToolResponse adapt(String toolName, String payload) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(payload);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid MCP result for " + toolName + ": "
                    + invalid.getMessage(), invalid);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("MCP result for " + toolName + " must be an object");
        }
        JsonObject result = parsed.getAsJsonObject();
        boolean error = result.has("isError") && result.get("isError").getAsBoolean();
        JsonElement structured = result.get("structuredContent");
        if (structured == null || structured.isJsonNull()) {
            structured = legacyText(result.getAsJsonArray("content"));
        }
        if (structured == null) {
            throw new IllegalArgumentException(
                    "MCP result for " + toolName + " has no structuredContent");
        }
        return new RuntimeContext.ToolResponse(error, structured.toString());
    }

    private static JsonElement legacyText(JsonArray content) {
        if (content == null) return null;
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("type") && block.get("type").getAsString().equals("text")
                    && block.has("text")) {
                try {
                    return JsonParser.parseString(block.get("text").getAsString());
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
