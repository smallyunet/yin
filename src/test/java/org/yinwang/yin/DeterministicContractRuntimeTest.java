package org.yinwang.yin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicContractRuntimeTest {
    @TempDir Path tempDir;

    @Test void checksTheMaintainedCapabilityDecisionContract() {
        JsonObject result = json(DeterministicContractRuntime.check(program().toString()));

        assertEquals(1, result.get("contractVersion").getAsInt());
        assertEquals("deterministic-policy-v1", result.get("profile").getAsString());
        assertTrue(result.get("programHash").getAsString().startsWith("sha256:"));
        assertTrue(result.get("valid").getAsBoolean());
    }

    @Test void evaluatesTheSameSourceAndInputReproducibly() throws Exception {
        String input = Files.readString(
                Path.of("examples/agents/capability-decision/inputs/approve.json"));

        String first = DeterministicContractRuntime.evaluate(program().toString(), input);
        String second = DeterministicContractRuntime.evaluate(program().toString(), input);

        assertEquals(first, second);
        JsonObject envelope = json(first);
        JsonObject approved = runFixture("approve.json");
        assertEquals("Approve", approved.get("tag").getAsString());
        assertEquals("wallet.swap", envelope.getAsJsonObject("result")
                .get("capability").getAsString());
        assertTrue(envelope.get("inputHash").getAsString().startsWith("sha256:"));
        assertTrue(envelope.get("resultHash").getAsString().startsWith("sha256:"));
    }

    @Test void preservesApprovalAndRejectionAsStructuredResults() throws Exception {
        JsonObject approval = runFixture("needs-approval.json");
        JsonObject rejection = runFixture("reject.json");

        assertEquals("NeedsApproval", approval.get("tag").getAsString());
        assertEquals("Reject", rejection.get("tag").getAsString());
    }

    @Test void rejectsEffectsAndUnstableValueTypes() throws Exception {
        assertRejected("(print \"unsafe\")", "print");
        assertRejected("(read-text \"secret\")", "read-text");
        assertRejected("(define value 1) (set! value 2) (encode-json value)", "set!");
        assertRejected("(encode-json 1.5)", "Float literals");
        assertRejected("(record Box [value Any]) (encode-json (Box :value 1))", "Any");
    }

    @Test void rejectsToolAuthorityInsideThePureDecisionProfile() throws Exception {
        assertRejected("""
                (record Request [amount Int])
                (tool assess
                  Request Request String
                  :capability "risk.read"
                  :effect :read
                  :approval false
                  :idempotent true
                  :open-world false)
                (encode-json (Request :amount 1))
                """, "tool declarations");
    }

    @Test void requiresAJsonTextResult() throws Exception {
        Path source = source("42");

        GeneralError error = assertThrows(GeneralError.class,
                () -> DeterministicContractRuntime.evaluate(source.toString(), "{}"));

        assertTrue(error.getMessage().contains("must return JSON text"));
    }

    private JsonObject runFixture(String name) throws Exception {
        String input = Files.readString(
                Path.of("examples/agents/capability-decision/inputs").resolve(name));
        JsonObject result = json(DeterministicContractRuntime.evaluate(program().toString(), input))
                .getAsJsonObject("result");
        String expected = Files.readString(
                Path.of("examples/agents/capability-decision/expected").resolve(name)).strip();
        assertEquals(expected, result.toString(), name);
        return result;
    }

    private void assertRejected(String body, String expected) throws Exception {
        GeneralError error = assertThrows(GeneralError.class,
                () -> DeterministicContractRuntime.check(source(body).toString()));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private Path source(String body) throws Exception {
        return Files.writeString(tempDir.resolve("contract-" + System.nanoTime() + ".yin"),
                body, StandardCharsets.UTF_8);
    }

    private Path program() {
        return Path.of("examples/agents/capability-decision/main.yin");
    }

    private JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
