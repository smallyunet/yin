package org.yinwang.yin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferencePolicyRuntimeTest {
    @TempDir Path tempDir;
    Path root;
    Path host;

    @BeforeEach void setUp() throws Exception {
        root = Files.createDirectories(tempDir.resolve("workspace/notes"));
        Files.writeString(root.resolve("welcome.txt"), "hello\n", StandardCharsets.UTF_8);
        host = tempDir.resolve("host.json");
        Files.writeString(host, """
                {"version":1,"root":"workspace","tools":[
                  {"name":"read-note","kind":"read-text","capability":"notes.read"},
                  {"name":"write-note","kind":"write-text","capability":"notes.write"}
                ]}
                """, StandardCharsets.UTF_8);
    }

    @Test void readsThroughInstalledCapabilityAndRecordsTrace() throws Exception {
        Path input = request("read", "notes/welcome.txt", "");
        Path trace = tempDir.resolve("read.jsonl");

        Run run = guard(input, trace);

        assertEquals(0, run.status);
        assertEquals("CompletedRead", json(run.output).get("tag").getAsString());
        assertTrue(run.output.contains("hello\\n"), run.output);
        String recorded = Files.readString(trace);
        assertTrue(recorded.contains("\"type\":\"authorization\""), recorded);
        assertTrue(recorded.contains("\"reason\":\"read-capability-installed\""), recorded);
        assertTrue(recorded.contains("\"type\":\"tool-result\""), recorded);
    }

    @Test void writeNeedsExplicitApprovalAndDeniedRunDoesNotMutate() throws Exception {
        Path target = root.resolve("welcome.txt");
        Path input = request("write", "notes/welcome.txt", "changed\n");

        Run denied = guard(input, tempDir.resolve("denied.jsonl"));

        assertEquals(0, denied.status);
        assertEquals("approval-required", json(denied.output).get("code").getAsString());
        assertEquals("hello\n", Files.readString(target));
        assertTrue(Files.readString(tempDir.resolve("denied.jsonl"))
                .contains("\"approved\":false"));
    }

    @Test void approvedWriteCanBeReplayedWithoutRepeatingTheWrite() throws Exception {
        Path target = root.resolve("welcome.txt");
        Path trace = tempDir.resolve("approved.jsonl");
        Run approved = guard(request("write", "notes/welcome.txt", "approved\n"), trace,
                "--approve", "notes.write");
        assertEquals(0, approved.status);
        assertEquals("approved\n", Files.readString(target));
        assertEquals("CompletedWrite", json(approved.output).get("tag").getAsString());

        Files.writeString(target, "after-run\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream replayed = new ByteArrayOutputStream();
        int replayStatus = ReferencePolicyRuntime.replay(new String[]{trace.toString()},
                stream(replayed), stream(new ByteArrayOutputStream()));

        assertEquals(0, replayStatus);
        assertEquals(approved.output + "\n", replayed.toString(StandardCharsets.UTF_8));
        assertEquals("after-run\n", Files.readString(target));
    }

    @Test void policyRejectsOutsideNamespaceBeforeAuthorization() throws Exception {
        Run run = guard(request("write", "../outside.txt", "bad\n"),
                tempDir.resolve("outside.jsonl"), "--approve", "notes.write");

        assertEquals(0, run.status);
        assertEquals("policy-denied", json(run.output).get("code").getAsString());
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
        assertFalse(Files.readString(tempDir.resolve("outside.jsonl"))
                .contains("\"type\":\"authorization\""));
    }

    @Test void hostRejectsWriteDeclarationsWithoutApproval() throws Exception {
        String source = Files.readString(program()).replace(
                ":capability \"notes.write\"\n  :effect :write\n  :approval true",
                ":capability \"notes.write\"\n  :effect :write\n  :approval false");
        Path unsafe = tempDir.resolve("unsafe.yin");
        Files.writeString(unsafe, source, StandardCharsets.UTF_8);

        Run run = guard(unsafe, request("write", "notes/welcome.txt", "bad\n"),
                tempDir.resolve("unsafe.jsonl"));

        assertEquals(1, run.status);
        assertTrue(run.error.contains("reference host requires approval for writes"), run.error);
        assertFalse(Files.exists(tempDir.resolve("unsafe.jsonl")));
        assertEquals("hello\n", Files.readString(root.resolve("welcome.txt")));
    }

    @Test void replayRejectsTamperedOrIncompleteTrace() throws Exception {
        Path trace = tempDir.resolve("trace.jsonl");
        assertEquals(0, guard(request("read", "notes/welcome.txt", ""), trace).status);
        String tampered = Files.readString(trace).replace("CompletedRead", "ChangedRead");
        Files.writeString(trace, tampered, StandardCharsets.UTF_8);
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int status = ReferencePolicyRuntime.replay(new String[]{trace.toString()},
                stream(new ByteArrayOutputStream()), stream(error));

        assertEquals(1, status);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("trace hash mismatch"));
    }

    @Test void traceFilesAreNeverOverwritten() throws Exception {
        Path trace = tempDir.resolve("existing.jsonl");
        Files.writeString(trace, "keep", StandardCharsets.UTF_8);

        Run run = guard(request("read", "notes/welcome.txt", ""), trace);

        assertEquals(1, run.status);
        assertEquals("keep", Files.readString(trace));
    }

    @Test void unknownApprovalsFailBeforeTraceCreation() throws Exception {
        Path trace = tempDir.resolve("unknown-approval.jsonl");

        Run run = guard(request("read", "notes/welcome.txt", ""), trace,
                "--approve", "notes.typo");

        assertEquals(1, run.status);
        assertTrue(run.error.contains("approval does not name an installed"), run.error);
        assertFalse(Files.exists(trace));
    }

    @Test void readDeclarationsCanAlsoRequireExplicitApproval() throws Exception {
        String source = Files.readString(program()).replace(
                ":capability \"notes.read\"\n  :effect :read\n  :approval false",
                ":capability \"notes.read\"\n  :effect :read\n  :approval true");
        Path guardedRead = tempDir.resolve("guarded-read.yin");
        Files.writeString(guardedRead, source, StandardCharsets.UTF_8);

        Run denied = guard(guardedRead, request("read", "notes/welcome.txt", ""),
                tempDir.resolve("guarded-read-denied.jsonl"));
        Run approved = guard(guardedRead, request("read", "notes/welcome.txt", ""),
                tempDir.resolve("guarded-read-approved.jsonl"),
                "--approve", "notes.read");

        assertEquals("approval-required", json(denied.output).get("code").getAsString());
        assertEquals("CompletedRead", json(approved.output).get("tag").getAsString());
    }

    private Run guard(Path input, Path trace, String... extra) {
        return guard(program(), input, trace, extra);
    }

    private Run guard(Path source, Path input, Path trace, String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                source.toString(), "--input", input.toString(), "--host", host.toString(),
                "--trace", trace.toString()));
        args.addAll(java.util.List.of(extra));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = ReferencePolicyRuntime.run(args.toArray(String[]::new),
                stream(output), stream(error));
        return new Run(status, output.toString(StandardCharsets.UTF_8).stripTrailing(),
                error.toString(StandardCharsets.UTF_8).stripTrailing());
    }

    private Path request(String action, String path, String content) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("action", action);
        request.addProperty("path", path);
        request.addProperty("content", content);
        Path file = tempDir.resolve("input-" + System.nanoTime() + ".json");
        return Files.writeString(file, request.toString(), StandardCharsets.UTF_8);
    }

    private Path program() {
        return Path.of("examples/agents/tool-boundary/main.yin");
    }

    private JsonObject json(String output) {
        return JsonParser.parseString(output).getAsJsonObject();
    }

    private PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private record Run(int status, String output, String error) { }
}
