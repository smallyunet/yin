package org.yinwang.yin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGatewayRuntimeTest {
    @TempDir Path tempDir;

    @Test void executesARequestBoundTypedToolThroughARealMcpStdioProcess() throws Exception {
        Fixture fixture = fixture();
        Path approval = tempDir.resolve("approval.json");
        Path nonceStore = tempDir.resolve("used.jsonl");
        Path trace = tempDir.resolve("trace.jsonl");

        Result approvalResult = approvalRequest(fixture, approval, "human@example.com");
        assertEquals(0, approvalResult.status, approvalResult.error);
        assertEquals(approval.toString() + "\n", approvalResult.output);

        Result executed = run(fixture, approval, nonceStore, trace);
        assertEquals(0, executed.status, executed.error);
        assertTrue(executed.output.contains("\"tag\":\"Completed\""), executed.output);
        assertTrue(executed.output.contains("\"ticketId\":\"T-1\""), executed.output);

        String protocol = Files.readString(fixture.protocolLog);
        assertTrue(protocol.contains("initialize"), protocol);
        assertTrue(protocol.contains("notifications/initialized"), protocol);
        assertTrue(protocol.contains("tools/list"), protocol);
        assertTrue(protocol.contains("tools/call"), protocol);
        assertEquals(1, Files.readAllLines(fixture.actions).size());
        assertTrue(Files.readString(trace).contains("\"reason\":\"request-bound-approval\""));

        ByteArrayOutputStream replayed = new ByteArrayOutputStream();
        int replayStatus = ReferencePolicyRuntime.replay(new String[]{trace.toString()},
                new PrintStream(replayed, true, StandardCharsets.UTF_8), System.err);
        assertEquals(0, replayStatus);
        assertEquals(executed.output, replayed.toString(StandardCharsets.UTF_8));
    }

    @Test void consumesApprovalNonceBeforeExecutionAndRejectsReuse() throws Exception {
        Fixture fixture = fixture();
        Path approval = tempDir.resolve("approval.json");
        Path nonceStore = tempDir.resolve("used.jsonl");
        assertEquals(0, approvalRequest(fixture, approval, "reviewer").status);
        assertEquals(0, run(fixture, approval, nonceStore, tempDir.resolve("first.jsonl")).status);

        Result reused = run(fixture, approval, nonceStore, tempDir.resolve("second.jsonl"));
        assertEquals(0, reused.status, reused.error);
        assertTrue(reused.output.contains("approval-required"), reused.output);
        assertEquals(1, Files.readAllLines(fixture.actions).size());
        assertTrue(Files.readString(tempDir.resolve("second.jsonl"))
                .contains("\"reason\":\"approval-already-used\""));
    }

    @Test void bindsApprovalToProgramIntentArgumentsHostAndExpiry() throws Exception {
        Fixture fixture = fixture();
        Path approval = tempDir.resolve("approval.json");
        Path nonceStore = tempDir.resolve("used.jsonl");
        assertEquals(0, approvalRequest(fixture, approval, "reviewer").status);

        String changed = Files.readString(fixture.intent).replace("Create v0.17", "Tampered title");
        Files.writeString(fixture.intent, changed, StandardCharsets.UTF_8);
        Result mismatch = run(fixture, approval, nonceStore, tempDir.resolve("mismatch.jsonl"));
        assertEquals(0, mismatch.status, mismatch.error);
        assertTrue(Files.readString(tempDir.resolve("mismatch.jsonl"))
                .contains("\"reason\":\"approval-input-mismatch\""));
        assertTrue(Files.notExists(fixture.actions));

        Fixture expiring = fixture("expiring");
        Path expired = tempDir.resolve("expired.json");
        assertEquals(0, approvalRequest(expiring, expired, "reviewer", 1).status);
        Thread.sleep(1100);
        Result expiry = run(expiring, expired, tempDir.resolve("expired-nonces.jsonl"),
                tempDir.resolve("expired-trace.jsonl"));
        assertEquals(0, expiry.status, expiry.error);
        assertTrue(Files.readString(tempDir.resolve("expired-trace.jsonl"))
                .contains("\"reason\":\"approval-expired\""));
    }

    @Test void canonicalJsonSortsNestedObjectKeysWithoutReorderingArrays() {
        JsonElement value = JsonParser.parseString("{\"z\":[{\"b\":2,\"a\":1}],\"a\":true}");
        assertEquals("{\"a\":true,\"z\":[{\"a\":1,\"b\":2}]}",
                ActionGatewayRuntime.canonical(value));
    }

    @Test void policyRejectionDoesNotStartTheMcpServerOrConsumeApproval() throws Exception {
        Fixture fixture = fixture("blocked");
        Files.writeString(fixture.intent,
                Files.readString(fixture.intent).replace("Create v0.17", "BLOCK"),
                StandardCharsets.UTF_8);
        Path approval = tempDir.resolve("blocked-approval.json");
        Path nonceStore = tempDir.resolve("blocked-used.jsonl");
        assertEquals(0, approvalRequest(fixture, approval, "reviewer").status);

        Result blocked = run(fixture, approval, nonceStore, tempDir.resolve("blocked-trace.jsonl"));
        assertEquals(0, blocked.status, blocked.error);
        assertTrue(blocked.output.contains("policy-denied"), blocked.output);
        assertTrue(Files.notExists(fixture.protocolLog));
        assertTrue(Files.notExists(fixture.actions));
        assertTrue(Files.notExists(nonceStore));
    }

    @Test void maintainedGatewayExampleIsTypedAndDeclaresTheClosedWriteCapability() {
        Path source = Path.of("examples/agents/action-gateway/main.yin");
        String type = new TypeChecker(source.toString()).typecheck(source.toString()).toString();
        assertTrue(type.contains("Result String"), type);
        assertTrue(type.contains("EncodeError"), type);
        String manifest = CapabilityManifest.inspect(source.toString());
        assertTrue(manifest.contains("\"name\":\"create-ticket\""), manifest);
        assertTrue(manifest.contains("\"capability\":\"tickets.write\""), manifest);
        assertTrue(manifest.contains("\"approvalRequired\":true"), manifest);
    }

    private Result approvalRequest(Fixture fixture, Path approval, String approvedBy) {
        return approvalRequest(fixture, approval, approvedBy, 60);
    }

    private Result approvalRequest(Fixture fixture, Path approval, String approvedBy, long seconds) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = ActionGatewayRuntime.approvalRequest(new String[]{
                        fixture.program.toString(), "--intent", fixture.intent.toString(),
                        "--host", fixture.host.toString(), "--out", approval.toString(),
                        "--approved-by", approvedBy, "--expires-in-seconds", Long.toString(seconds)},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private Result run(Fixture fixture, Path approval, Path nonceStore, Path trace) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = ActionGatewayRuntime.run(new String[]{
                        fixture.program.toString(), "--intent", fixture.intent.toString(),
                        "--host", fixture.host.toString(), "--trace", trace.toString(),
                        "--approval", approval.toString(), "--nonce-store", nonceStore.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private Fixture fixture() throws Exception { return fixture("default"); }

    private Fixture fixture(String name) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Path program = Files.writeString(root.resolve("gateway.yin"), PROGRAM,
                StandardCharsets.UTF_8);
        Path intent = Files.writeString(root.resolve("intent.json"), INTENT,
                StandardCharsets.UTF_8);
        Path protocol = root.resolve("protocol.jsonl");
        Path actions = root.resolve("actions.jsonl");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        JsonObject host = JsonParser.parseString("""
                {"version":1,"timeoutMillis":5000,"servers":[{
                  "name":"tickets",
                  "command":[],
                  "cwd":".",
                  "tools":[{
                    "name":"create-ticket",
                    "remoteName":"create_ticket",
                    "capability":"tickets.write",
                    "effect":"write",
                    "approvalRequired":true
                  }]
                }]}
                """).getAsJsonObject();
        for (String part : List.of(java, "-cp", classpath, FixtureServer.class.getName(),
                protocol.toString(), actions.toString())) {
            host.getAsJsonArray("servers").get(0).getAsJsonObject()
                    .getAsJsonArray("command").add(part);
        }
        Path hostPath = Files.writeString(root.resolve("host.json"), host.toString(),
                StandardCharsets.UTF_8);
        return new Fixture(program, intent, hostPath, protocol, actions);
    }

    private record Fixture(Path program, Path intent, Path host, Path protocolLog, Path actions) { }
    private record Result(int status, String output, String error) { }

    /** Standalone process fixture: it exercises the actual newline-delimited MCP stdio lifecycle. */
    public static final class FixtureServer {
        private FixtureServer() { }

        public static void main(String[] args) throws Exception {
            Path protocol = Path.of(args[0]);
            Path actions = Path.of(args[1]);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    System.in, StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                         System.out, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    String method = message.has("method") ? message.get("method").getAsString() : "response";
                    Files.writeString(protocol, method + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (!message.has("id")) continue;
                    JsonObject response = new JsonObject();
                    response.addProperty("jsonrpc", "2.0");
                    response.add("id", message.get("id"));
                    JsonObject result = new JsonObject();
                    switch (method) {
                        case "initialize" -> {
                            result.addProperty("protocolVersion", "2025-11-25");
                            JsonObject capabilities = new JsonObject();
                            capabilities.add("tools", new JsonObject());
                            result.add("capabilities", capabilities);
                            JsonObject server = new JsonObject();
                            server.addProperty("name", "yin-test-ticket-server");
                            server.addProperty("version", "1");
                            result.add("serverInfo", server);
                        }
                        case "tools/list" -> {
                            JsonObject tool = new JsonObject();
                            tool.addProperty("name", "create_ticket");
                            JsonObject schema = new JsonObject();
                            schema.addProperty("type", "object");
                            tool.add("inputSchema", schema);
                            com.google.gson.JsonArray tools = new com.google.gson.JsonArray();
                            tools.add(tool);
                            result.add("tools", tools);
                        }
                        case "tools/call" -> {
                            JsonObject arguments = message.getAsJsonObject("params")
                                    .getAsJsonObject("arguments");
                            Files.writeString(actions, arguments + System.lineSeparator(),
                                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                                    StandardOpenOption.APPEND);
                            JsonObject receipt = new JsonObject();
                            receipt.addProperty("ticketId", "T-1");
                            receipt.addProperty("repository",
                                    arguments.get("repository").getAsString());
                            result.add("structuredContent", receipt);
                            result.addProperty("isError", false);
                        }
                        default -> throw new IllegalArgumentException("unexpected method: " + method);
                    }
                    response.add("result", result);
                    output.write(response.toString());
                    output.newLine();
                    output.flush();
                }
            }
        }
    }

    private static final String INTENT = """
            {
              "requestId": "req-017",
              "actor": "human@example.com",
              "agent": "release-agent",
              "server": "tickets",
              "tool": "create_ticket",
              "capability": "tickets.write",
              "effect": "write",
              "resource": "repo:smallyunet/yin",
              "arguments": {
                "repository": "smallyunet/yin",
                "title": "Create v0.17",
                "body": "Request-bound MCP execution"
              }
            }
            """;

    private static final String PROGRAM = """
            (record TicketArguments [repository String] [title String] [body String])
            (record ActionIntent
              [requestId String]
              [actor String]
              [agent String]
              [server String]
              [tool String]
              [capability String]
              [effect String]
              [resource String]
              [arguments TicketArguments])
            (record TicketReceipt [ticketId String] [repository String])
            (variant TicketFailure [RemoteFailure [message String]])
            (variant Decision [Permit [reason String]] [Block [reason String]])
            (variant GatewayResult
              [Completed [ticketId String] [repository String]]
              [GatewayFailure [code String] [message String]])
            (tool create-ticket TicketArguments TicketReceipt TicketFailure
              :capability "tickets.write"
              :effect :write
              :approval true
              :idempotent false
              :open-world false)
            (policy authorize
              ([intent ActionIntent] [-> Decision])
              (when (not (= intent.server "tickets"))
                (Block :reason "server is not allowed"))
              (when (not (= intent.tool "create_ticket"))
                (Block :reason "tool is not allowed"))
              (when (not (= intent.capability "tickets.write"))
                (Block :reason "capability is not allowed"))
              (when (not (= intent.effect "write"))
                (Block :reason "effect is not allowed"))
              (when (= intent.arguments.title "BLOCK")
                (Block :reason "request was blocked by policy"))
              (otherwise (Permit :reason "ticket request accepted")))
            (define execute
              (fun ([intent ActionIntent] [-> (Result String EncodeError)])
                (match (authorize intent)
                  [(Block reason)
                    (encode-json (GatewayFailure :code "policy-denied" :message reason))]
                  [(Permit _)
                    (match (invoke create-ticket intent.arguments)
                      [(Ok receipt)
                        (encode-json
                          (Completed
                            :ticketId receipt.ticketId
                            :repository receipt.repository))]
                      [(Err error)
                        (match error
                          [(RemoteFailure message)
                            (encode-json
                              (GatewayFailure :code "remote-failure" :message message))]
                          [(ToolError code _ message)
                            (encode-json
                              (GatewayFailure :code code :message message))])])])))
            (match (decode-json ActionIntent (read-all))
              [(Ok intent) (execute intent)]
              [(Err error)
                (encode-json
                  (GatewayFailure :code error.code :message error.message))])
            """;
}
