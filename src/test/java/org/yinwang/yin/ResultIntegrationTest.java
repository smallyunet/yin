package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.type.ErrType;
import org.yinwang.yin.type.OkType;
import org.yinwang.yin.type.YinType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void constructorsProduceImmutableTaggedValuesAndPreciseTypes() throws Exception {
        Path success = program("(ok 42)");
        Path failure = program("(err \"unavailable\")");

        assertEquals("(ok 42)", interpret(success));
        assertInstanceOf(OkType.class, type(success));
        assertEquals("(Ok Int)", typecheck(success));
        assertEquals("(err \"unavailable\")", interpret(failure));
        assertInstanceOf(ErrType.class, type(failure));
        assertEquals("(Err String)", typecheck(failure));
    }

    @Test
    void resultAnnotationsAcceptCompatibleSuccessAndFailureVariants() throws Exception {
        Path source = program("""
                (define choose
                  (fun ([available Bool] [-> (Result Int String)])
                    (if available (ok 42) (err "unavailable"))))
                (define widen
                  (fun ([outcome (Result Any Any)] [-> (Result Any Any)]) outcome))
                [(choose true) (choose false) (widen (choose true))]
                """);

        assertEquals("[(ok 42) (err \"unavailable\") (ok 42)]", interpret(source));
        assertEquals("[(Result Int String) (Result Int String) (Result Any Any)]",
                typecheck(source));
    }

    @Test
    void resultAnnotationsRejectIncompatiblePayloads() throws Exception {
        Path invalidSuccess = program("""
                (define invalid
                  (fun ([-> (Result Int String)]) (ok true)))
                (invalid)
                """);
        Path invalidFailure = program("""
                (define invalid
                  (fun ([-> (Result Int String)]) (err 42)))
                (invalid)
                """);

        assertLanguageError(() -> typecheck(invalidSuccess),
                "type error in return value, expected: (Result Int String), actual: (Ok Bool)");
        assertLanguageError(() -> typecheck(invalidFailure),
                "type error in return value, expected: (Result Int String), actual: (Err Int)");
    }

    @Test
    void matchNarrowsResultPayloadsAndRequiresBothVariants() throws Exception {
        Path source = program("""
                (define describe
                  (fun ([outcome (Result Int String)] [-> String])
                    (match outcome
                      [(Ok value) (concat "value=" (to-string value))]
                      [(Err message) (concat "error=" message)])))
                [(describe (ok 42)) (describe (err "offline"))]
                """);

        assertEquals("[\"value=42\" \"error=offline\"]", interpret(source));
        assertEquals("[String String]", typecheck(source));
    }

    @Test
    void matchRejectsMissingResultVariantAndMalformedPatterns() throws Exception {
        Path missing = program("""
                (define unwrap
                  (fun ([outcome (Result Int String)])
                    (match outcome [(Ok value) value])))
                unwrap
                """);
        Path malformed = program("""
                (match (ok 42) [(Ok left right) left] [_ 0])
                """);

        assertLanguageError(() -> typecheck(missing), "non-exhaustive match for type: (Err String)");
        assertLanguageError(() -> interpret(malformed), "Ok pattern expects exactly one payload");
        assertLanguageError(() -> typecheck(malformed), "Ok pattern expects exactly one payload");
    }

    @Test
    void resultEqualityIncludesTheTagAndPayload() throws Exception {
        Path source = program("""
                [(= (ok [1 2]) (ok [1 2]))
                 (= (err "x") (err "x"))
                 (= (ok 1) (err 1))]
                """);

        assertEquals("[true true false]", interpret(source));
        assertEquals("[Bool Bool Bool]", typecheck(source));
    }

    @Test
    void resultPatternsRemainRuntimeCheckedAcrossAny() throws Exception {
        Path source = program("""
                (define describe
                  (fun ([outcome Any] [-> String])
                    (match outcome
                      [(Ok _) "ok"]
                      [(Err _) "err"]
                      [_ "other"])))
                [(describe (ok 1)) (describe (err "x")) (describe 42)]
                """);

        assertEquals("[\"ok\" \"err\" \"other\"]", interpret(source));
        assertEquals("[String String String]", typecheck(source));
    }

    @Test
    void maintainedResultProgramIsRunnableAndTyped() {
        Path source = Path.of("tests/result-outcomes.yin");

        assertEquals("42", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    private String interpret(Path source) {
        return new Interpreter(source.toString()).interp(source.toString()).toString();
    }

    private String typecheck(Path source) {
        return type(source).toString();
    }

    private YinType type(Path source) {
        return new TypeChecker(source.toString()).typecheck(source.toString());
    }

    private Path program(String source) throws Exception {
        return Files.writeString(tempDir.resolve("program-" + System.nanoTime() + ".yin"),
                source, StandardCharsets.UTF_8);
    }

    private static void assertLanguageError(ThrowingAction action, String message) {
        GeneralError error = assertThrows(GeneralError.class, action::run);
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
