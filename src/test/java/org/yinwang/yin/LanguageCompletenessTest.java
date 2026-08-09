package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageCompletenessTest {
    @TempDir
    Path tempDir;

    @Test
    void homogeneousVectorAnnotationsAcceptCompatibleExactVectors() throws Exception {
        Path source = program("""
                (define sum
                  (fun ([items (Vector Int)] [-> Int])
                    (fold items 0
                      (fun ([total Int] [value Int] [-> Int])
                        (+ total value)))))
                (sum [10 20 12])
                """);

        assertEquals("42", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    @Test
    void homogeneousVectorAnnotationsRejectIncompatibleElements() throws Exception {
        Path source = program("""
                (define sum (fun ([items (Vector Int)] [-> Int]) 0))
                (sum [1 "two"])
                """);

        assertLanguageError(() -> typecheck(source), "expected: (Vector Int)");
    }

    @Test
    void declaredFunctionTypesSupportHigherOrderFunctions() throws Exception {
        Path source = program("""
                (define apply
                  (fun ([function (Fn [Int] Int)] [value Int] [-> Int])
                    (function value)))
                (apply (fun ([value Int] [-> Int]) (+ value 1)) 41)
                """);

        assertEquals("42", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    @Test
    void collectionFunctionsRemainImmutableAndPreciselyTyped() throws Exception {
        Path source = program("""
                (define source [1 2 3 4])
                (define doubled
                  (map source (fun ([value Int] [-> Int]) (* value 2))))
                (define selected
                  (filter doubled (fun ([value Int] [-> Bool]) (> value 4))))
                [(fold selected 0
                   (fun ([total Int] [value Int] [-> Int]) (+ total value)))
                 source
                 (reverse (slice doubled 1 3))]
                """);

        assertEquals("[14 [1 2 3 4] [6 4]]", interpret(source));
        assertEquals("[Int [Int Int Int Int] [Int Int]]", typecheck(source));
    }

    @Test
    void rangeAndContainsProvideGeneralIterationBuildingBlocks() throws Exception {
        Path source = program("[(range 1 5) (contains (range 1 5) 3)]");

        assertEquals("[[1 2 3 4] true]", interpret(source));
        assertEquals("[(Vector Int) Bool]", typecheck(source));
    }

    @Test
    void stringOperationsAndStructuralEqualityHandleTextData() throws Exception {
        Path source = program("""
                (define words (split (trim "  yin makes text useful  ") " "))
                [(join "-" words)
                 (substring "language" 0 4)
                 (string-length "语言")
                 (= words ["yin" "makes" "text" "useful"])]
                """);

        assertEquals("[\"yin-makes-text-useful\" \"lang\" 2 true]", interpret(source));
        assertEquals("[String String Int Bool]", typecheck(source));
    }

    @Test
    void matchNarrowsPrimitiveUnionMembersAndIsExhaustive() throws Exception {
        Path source = program("""
                (match (parse-int "41")
                  [(Int value) (+ value 1)]
                  [(Bool _) 0])
                """);

        assertEquals("42", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    @Test
    void matchDestructuresNominalRecordsAndVectors() throws Exception {
        Path source = program("""
                (record Point [x Int] [y Int])
                (record Problem [message String])
                (define describe
                  (fun ([value (U Point Problem)] [-> String])
                    (match value
                      [(Point x y) (concat (to-string x) (to-string y))]
                      [(Problem message) message])))
                [(describe (Point :x 4 :y 2))
                 (match [20 22] [[left right] (+ left right)])]
                """);

        assertEquals("[\"42\" 42]", interpret(source));
        assertEquals("[String Int]", typecheck(source));
    }

    @Test
    void matchRejectsNonExhaustiveBranches() throws Exception {
        Path source = program("""
                (define classify
                  (fun ([value (U Int String)])
                    (match value [(Int number) number])))
                classify
                """);

        assertLanguageError(() -> typecheck(source), "non-exhaustive match for type: String");
    }

    @Test
    void matchRejectsDuplicateBindings() throws Exception {
        Path source = program("(match [1 2] [[value value] value])");

        assertLanguageError(() -> typecheck(source), "duplicate binding in pattern: value");
    }

    @Test
    void injectedArgumentsAndInputAreAvailableWithoutGlobalIo() throws Exception {
        Path source = program("""
                [(at args 0) (trim (read-all))]
                """);
        List<String> output = new ArrayList<>();
        RuntimeContext context = new RuntimeContext(
                output::add, () -> "  input text\n", List.of("first"));

        Value result = new Interpreter(source.toString()).interp(source.toString(), context);

        assertEquals("[\"first\" \"input text\"]", result.toString());
        assertEquals("[String String]", typecheck(source));
        assertTrue(output.isEmpty());
    }

    @Test
    void invalidCollectionCallbacksAreRejectedStatically() throws Exception {
        Path source = program("""
                (map [1 2] (fun ([value String] [-> String]) value))
                """);

        assertLanguageError(() -> typecheck(source), "expected: String, actual: Int");
    }

    @Test
    void quicksortExampleIsRunnableAndTyped() {
        assertEquals("[1 2 3 4 5 6 7 8 9]", interpret(Path.of("examples/quicksort.yin")));
        assertEquals("(Vector Int)", typecheck(Path.of("examples/quicksort.yin")));
    }

    @Test
    void parseValuesExampleConsumesProgramArguments() {
        Path source = Path.of("examples/parse-values.yin");
        RuntimeContext context = new RuntimeContext(
                ignored -> { }, () -> "", List.of("10", "bad", "32"));

        Value result = new Interpreter(source.toString()).interp(source.toString(), context);

        assertEquals("42", result.toString());
        assertEquals("Int", typecheck(source));
    }

    @Test
    void wordCountExampleReadsARealUtf8TextFile() throws Exception {
        Path input = Files.writeString(tempDir.resolve("input.txt"),
                "yin makes programs\nsmall and useful", StandardCharsets.UTF_8);
        Path source = Path.of("examples/wc.yin");
        List<String> output = new ArrayList<>();
        RuntimeContext context = new RuntimeContext(
                output::add, () -> "", List.of(input.toString()));

        Value result = new Interpreter(source.toString()).interp(source.toString(), context);

        assertEquals("void", result.toString());
        assertEquals(List.of("\"lines\", 2", "\"words\", 6", "\"characters\", 35"), output);
        assertEquals("void", typecheck(source));
    }

    private String interpret(Path source) {
        return new Interpreter(source.toString()).interp(source.toString()).toString();
    }

    private String typecheck(Path source) {
        YinType type = new TypeChecker(source.toString()).typecheck(source.toString());
        return type.toString();
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
