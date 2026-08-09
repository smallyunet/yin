package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReviewDemoTest {
    @TempDir Path tempDir;

    @Test void maintainedFixturesCoverEveryDecisionAndBoundaryPath() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("approve.json", "{\"tag\":\"Approve\",\"requestId\":\"req-approve\",\"reason\":\"within automatic policy\"}");
        expected.put("reject.json", "{\"tag\":\"Reject\",\"requestId\":\"req-reject\",\"reason\":\"risk policy blocked this request\"}");
        expected.put("needs-input.json", "{\"tag\":\"NeedsInput\",\"requestId\":\"req-needs-input\",\"question\":\"provide transfer approval context\"}");
        expected.put("approve-transfer.json", "{\"tag\":\"Approve\",\"requestId\":\"req-transfer\",\"reason\":\"transfer context accepted\"}");
        expected.put("missing-field.json", "{\"tag\":\"Reject\",\"requestId\":\"invalid-request\",\"reason\":\"missing-field at $.amount: missing required field: amount\"}");
        expected.put("wrong-type.json", "{\"tag\":\"Reject\",\"requestId\":\"invalid-request\",\"reason\":\"wrong-type at $.amount: expected 32-bit integer, got string\"}");
        expected.put("unknown-field.json", "{\"tag\":\"Reject\",\"requestId\":\"invalid-request\",\"reason\":\"unknown-field at $.debug: unknown field: debug\"}");

        for (Map.Entry<String, String> fixture : expected.entrySet()) {
            Path input = Path.of("examples/agent-review/inputs", fixture.getKey());
            Run result = runJson(Path.of("examples/agent-review/main.yin"),
                    Files.readString(input, StandardCharsets.UTF_8));
            assertEquals(0, result.status, fixture.getKey() + " stderr: " + result.error);
            assertEquals(fixture.getValue() + "\n", result.output, fixture.getKey());
            assertEquals("", result.error, fixture.getKey());
        }
    }

    @Test void demoProgramIsStaticallyTypedAsJsonBoundary() {
        String type = new TypeChecker("examples/agent-review/main.yin")
                .typecheck("examples/agent-review/main.yin").toString();
        assertEquals("(Result String (record EncodeError [code String] [path String] [message String]))", type);
    }

    @Test void jsonModeKeepsProgramLogsOffStandardOutput() throws Exception {
        Path program = Files.writeString(tempDir.resolve("logged.yin"), """
                (seq
                  (print "trace")
                  (encode-json (ok 42)))
                """, StandardCharsets.UTF_8);

        Run result = runJson(program, "");

        assertEquals(0, result.status);
        assertEquals("{\"tag\":\"Ok\",\"value\":42}\n", result.output);
        assertEquals("\"trace\"\n", result.error);
    }

    @Test void jsonModeEncodesErrPayloadAndReturnsFailureStatus() throws Exception {
        Path program = Files.writeString(tempDir.resolve("failure.yin"),
                "(err \"unavailable\")", StandardCharsets.UTF_8);

        Run result = runJson(program, "");

        assertEquals(1, result.status);
        assertEquals("\"unavailable\"\n", result.output);
        assertEquals("", result.error);
    }

    @Test void jsonModeRejectsNonBoundaryResultsWithoutPollutingOutput() throws Exception {
        Path program = Files.writeString(tempDir.resolve("invalid.yin"), "42", StandardCharsets.UTF_8);

        Run result = runJson(program, "");

        assertEquals(1, result.status);
        assertEquals("", result.output);
        assertTrue(result.error.contains("--json expects String or (Result String E)"), result.error);
    }

    private static Run runJson(Path program, String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = Interpreter.runJson(
                new String[]{program.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                () -> input);
        return new Run(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Run(int status, String output, String error) { }
}
