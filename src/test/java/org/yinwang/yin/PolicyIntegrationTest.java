package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void orderedPolicyRulesReturnTheFirstMatchingOutcome() throws Exception {
        Path source = program("""
                (record Request [risk String] [amount Int])
                (variant Decision
                  [Approve [reason String]]
                  [Reject [reason String]]
                  [NeedsApproval [reason String]])
                (policy review
                  ([request Request] [-> Decision])
                  (when (= request.risk "blocked")
                    (Reject :reason "blocked by policy"))
                  (when (> request.amount 1000)
                    (NeedsApproval :reason "amount requires approval"))
                  (otherwise
                    (Approve :reason "within policy")))
                (review (Request :risk "blocked" :amount 5000))
                """);

        assertEquals("(record Reject [reason \"blocked by policy\"])", interpret(source));
        assertEquals("Decision", typecheck(source));
    }

    @Test
    void dottedAccessSupportsNestedImmutableRecords() throws Exception {
        Path source = program("""
                (record Account [name String])
                (record Request [account Account])
                (define request (Request :account (Account :name "Ada")))
                request.account.name
                """);

        assertEquals("\"Ada\"", interpret(source));
        assertEquals("String", typecheck(source));
    }

    @Test
    void policyRequiresAnExplicitFinalFallback() throws Exception {
        Path source = program("""
                (policy decide
                  ([value Int] [-> Int])
                  (when (> value 0) value))
                """);

        GeneralError error = assertThrows(GeneralError.class, () -> typecheck(source));
        assertTrue(error.getMessage().contains(
                "policy requires one or more when rules followed by otherwise"));
    }

    @Test
    void policyRejectsRulesAfterOtherwise() throws Exception {
        Path source = program("""
                (policy decide
                  ([value Int] [-> Int])
                  (when (> value 0) value)
                  (otherwise 0)
                  (when (< value 0) value))
                """);

        GeneralError error = assertThrows(GeneralError.class, () -> typecheck(source));
        assertTrue(error.getMessage().contains(
                "otherwise requires one outcome and must be the final policy clause"));
    }

    @Test
    void policyConditionsMustBeBoolean() throws Exception {
        Path source = program("""
                (policy decide
                  ([value Int] [-> Int])
                  (when value 1)
                  (otherwise 0))
                (decide 1)
                """);

        GeneralError error = assertThrows(GeneralError.class, () -> typecheck(source));
        assertTrue(error.getMessage().contains("test is not boolean"));
    }

    @Test
    void policyRequiresTypedParametersAndReturn() throws Exception {
        Path source = program("""
                (policy decide
                  (value)
                  (when (= value 1)
                    1)
                  (otherwise
                    0))
                """);

        GeneralError error = assertThrows(GeneralError.class, () -> typecheck(source));
        assertTrue(error.getMessage().contains(
                "policy parameters and return type must use typed descriptors"));
    }

    @Test
    void formatterKeepsPolicyRulesTopToBottom() {
        String source = "(policy decide ([value Int] [-> Int]) "
                + "(when (> value 0) value) (otherwise 0))";

        assertEquals("""
                (policy decide
                  ([value Int] [-> Int])
                  (when (> value 0)
                    value)
                  (otherwise
                    0))
                """, Formatter.format("<test>", source));
    }

    private Path program(String source) throws Exception {
        return Files.writeString(tempDir.resolve("policy-" + System.nanoTime() + ".yin"),
                source, StandardCharsets.UTF_8);
    }

    private String interpret(Path source) {
        return new Interpreter(source.toString()).interp(source.toString()).toString();
    }

    private String typecheck(Path source) {
        return new TypeChecker(source.toString()).typecheck(source.toString()).toString();
    }
}
