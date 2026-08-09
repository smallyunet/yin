package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.tool.McpToolAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolIntegrationTest {
    @TempDir Path tempDir;

    @Test void typedToolSuccessIsDecodedAndAudited() throws Exception {
        Path source = program(contract(false) + "\n(invoke assess-risk (RiskRequest :amount 42))");
        List<RuntimeContext.ToolAuditEvent> audit = new ArrayList<>();
        RuntimeContext context = context(
                Map.of("assess-risk", input -> RuntimeContext.ToolResponse.success(
                        "{\"score\":7,\"reason\":\"within policy\"}")),
                request -> true, audit);

        assertEquals("(ok (record RiskAssessment [score 7] [reason \"within policy\"]))",
                interpret(source, context));
        assertEquals(1, audit.size());
        assertEquals("ok", audit.get(0).status());
        assertEquals("{\"amount\":42}", audit.get(0).inputJson());
        assertTrue(typecheck(source).startsWith("(Result (record RiskAssessment"));
        assertTrue(typecheck(source).contains("ToolError"));
    }

    @Test void declaredBusinessErrorsRemainTypedResults() throws Exception {
        Path source = program(contract(false) + "\n(invoke assess-risk (RiskRequest :amount 42))");
        RuntimeContext context = context(Map.of("assess-risk", input ->
                RuntimeContext.ToolResponse.failure(
                        "{\"tag\":\"Offline\",\"message\":\"upstream unavailable\"}")),
                request -> true, new ArrayList<>());

        String result = interpret(source, context);
        assertTrue(result.startsWith("(err (record Offline"), result);
        assertTrue(result.contains("upstream unavailable"), result);
    }

    @Test void missingToolsAndInvalidOutputsBecomeToolErrors() throws Exception {
        Path source = program(contract(false) + "\n(invoke assess-risk (RiskRequest :amount 42))");
        String missing = interpret(source, context(Map.of(), request -> false, new ArrayList<>()));
        String invalid = interpret(source, context(Map.of("assess-risk", input ->
                RuntimeContext.ToolResponse.success("{\"score\":\"high\",\"reason\":\"x\"}")),
                request -> true, new ArrayList<>()));
        String unauthorized = interpret(source, context(Map.of("assess-risk", input ->
                RuntimeContext.ToolResponse.success("{\"score\":1,\"reason\":\"x\"}")),
                request -> false, new ArrayList<>()));

        assertTrue(missing.contains("[code \"unavailable\"]"), missing);
        assertTrue(invalid.contains("[code \"invalid-output\"]"), invalid);
        assertTrue(unauthorized.contains("[code \"unauthorized\"]"), unauthorized);
    }

    @Test void approvalIsEnforcedBeforeHostInvocation() throws Exception {
        Path source = program(contract(true) + "\n(invoke assess-risk (RiskRequest :amount 42))");
        AtomicInteger calls = new AtomicInteger();
        RuntimeContext.ToolHandler handler = input -> {
            calls.incrementAndGet();
            return RuntimeContext.ToolResponse.success("{\"score\":1,\"reason\":\"ok\"}");
        };
        String denied = interpret(source, context(Map.of("assess-risk", handler),
                request -> false, new ArrayList<>()));
        assertTrue(denied.contains("approval-required"), denied);
        assertEquals(0, calls.get());

        String approved = interpret(source, context(Map.of("assess-risk", handler),
                request -> request.descriptor().capability().equals("risk.write"), new ArrayList<>()));
        assertTrue(approved.startsWith("(ok "), approved);
        assertEquals(1, calls.get());

        String policyFailure = interpret(source, context(Map.of("assess-risk", handler),
                request -> { throw new IllegalStateException("secret policy detail"); },
                new ArrayList<>()));
        assertTrue(policyFailure.contains("authorization-failed"), policyFailure);
        assertFalse(policyFailure.contains("secret policy detail"), policyFailure);
        assertEquals(1, calls.get());
    }

    @Test void staticCheckerRejectsWrongInputAndUnsafeDestructiveDeclarations() throws Exception {
        Path wrongInput = program(contract(false) + "\n(invoke assess-risk 42)");
        GeneralError wrong = assertThrows(GeneralError.class, () -> typecheck(wrongInput));
        assertTrue(wrong.getMessage().contains("tool input type error"), wrong.getMessage());

        Path destructive = program(contract(false).replace(":effect :read", ":effect :destructive"));
        GeneralError unsafe = assertThrows(GeneralError.class, () -> typecheck(destructive));
        assertTrue(unsafe.getMessage().contains("destructive tools must require approval"),
                unsafe.getMessage());

        Path functionContract = program("""
                (tool invalid (Fn [Int] Int) Int String
                  :capability "invalid" :effect :read :approval false
                  :idempotent true :open-world false)
                """);
        GeneralError unsupported = assertThrows(GeneralError.class,
                () -> typecheck(functionContract));
        assertTrue(unsupported.getMessage().contains("tool contract is not JSON-encodable"),
                unsupported.getMessage());

        Path nestedFunction = program("""
                (record InvalidInput [callback (Fn [Int] Int)])
                (tool invalid InvalidInput Int String
                  :capability "invalid" :effect :read :approval false
                  :idempotent true :open-world false)
                """);
        GeneralError nested = assertThrows(GeneralError.class,
                () -> typecheck(nestedFunction));
        assertTrue(nested.getMessage().contains("tool contract is not JSON-encodable"),
                nested.getMessage());
    }

    @Test void capabilityManifestIsDeterministicAndComplete() throws Exception {
        Path source = program(contract(false));
        String manifest = CapabilityManifest.inspect(source.toString());

        assertEquals(manifest, CapabilityManifest.inspect(source.toString()));
        assertTrue(manifest.contains("\"name\":\"assess-risk\""), manifest);
        assertTrue(manifest.contains("\"capability\":\"risk.read\""), manifest);
        assertTrue(manifest.contains("\"approvalRequired\":false"), manifest);
        assertTrue(manifest.contains("\"openWorld\":false"), manifest);
    }

    @Test void capabilitiesCliPrintsThePreflightManifest() throws Exception {
        Path source = program(contract(false));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            Interpreter.main(new String[]{"--capabilities", source.toString()});
        } finally {
            System.setOut(original);
        }
        assertEquals(CapabilityManifest.inspect(source.toString()) + "\n",
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test void mcpAdapterUsesStructuredContentAndDoesNotAuthorizeFromAnnotations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RuntimeContext.ToolHandler handler = McpToolAdapter.handler("remote-risk", (name, input) -> {
            calls.incrementAndGet();
            assertEquals("remote-risk", name);
            assertEquals("{\"amount\":42}", input);
            return "{\"structuredContent\":{\"score\":3,\"reason\":\"mcp\"},"
                    + "\"isError\":false}";
        });
        RuntimeContext.ToolResponse response = handler.invoke("{\"amount\":42}");

        assertFalse(response.error());
        assertEquals("{\"score\":3,\"reason\":\"mcp\"}", response.json());
        assertEquals(1, calls.get());
    }

    @Test void mcpAdapterSupportsStructuredErrorsAndLegacyJsonText() throws Exception {
        RuntimeContext.ToolHandler error = McpToolAdapter.handler("remote", (name, input) ->
                "{\"isError\":true,\"structuredContent\":{\"tag\":\"Offline\","
                        + "\"message\":\"down\"}}");
        RuntimeContext.ToolHandler legacy = McpToolAdapter.handler("remote", (name, input) ->
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"score\\\":2,"
                        + "\\\"reason\\\":\\\"legacy\\\"}\"}]}");

        assertTrue(error.invoke("{}").error());
        assertEquals("{\"score\":2,\"reason\":\"legacy\"}", legacy.invoke("{}").json());
    }

    @Test void maintainedTypedToolExampleIsRunnableTypedAndInspectable() {
        Path source = Path.of("examples/agents/typed-tool.yin");
        RuntimeContext context = context(Map.of("assess-risk", input ->
                        RuntimeContext.ToolResponse.success(
                                "{\"score\":7,\"reason\":\"host accepted\"}")),
                request -> request.descriptor().capability().equals("risk.read"),
                new ArrayList<>());

        assertEquals("\"host accepted\"", interpret(source, context));
        assertEquals("String", typecheck(source));
        assertTrue(CapabilityManifest.inspect(source.toString()).contains("\"risk.read\""));
    }

    private String contract(boolean approval) {
        return """
                (record RiskRequest [amount Int])
                (record RiskAssessment [score Int] [reason String])
                (variant RiskFailure [Offline [message String]] [Rejected [message String]])
                (tool assess-risk RiskRequest RiskAssessment RiskFailure
                  :capability "%s"
                  :effect :read
                  :approval %s
                  :idempotent true
                  :open-world false)
                """.formatted(approval ? "risk.write" : "risk.read", approval);
    }

    private RuntimeContext context(Map<String, RuntimeContext.ToolHandler> tools,
                                   RuntimeContext.AuthorizationPolicy approvals,
                                   List<RuntimeContext.ToolAuditEvent> audit) {
        return new RuntimeContext(ignored -> { }, () -> "", List.of(), path -> "",
                tools, approvals, audit::add);
    }

    private Path program(String source) throws Exception {
        return Files.writeString(tempDir.resolve("tool-" + System.nanoTime() + ".yin"),
                source, StandardCharsets.UTF_8);
    }
    private String interpret(Path source, RuntimeContext context) {
        return new Interpreter(source.toString()).interp(source.toString(), context).toString();
    }
    private String typecheck(Path source) {
        return new TypeChecker(source.toString()).typecheck(source.toString()).toString();
    }
}
