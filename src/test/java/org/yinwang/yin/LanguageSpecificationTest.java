package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.type.PrimitiveFunctionType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.UnionType;
import org.yinwang.yin.type.VectorType;
import org.yinwang.yin.type.YinType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageSpecificationTest {

    @TempDir
    Path tempDir;

    @Test
    void positionalArgumentsEvaluateFromLeftToRight() throws Exception {
        Path source = program("""
                (define counter 0)
                (define next (fun () (set! counter (+ counter 1)) counter))
                (define encode (fun (left right) (+ (* left 10) right)))
                (encode (next) (next))
                """);

        assertEquals("12", interpret(source));
    }

    @Test
    void keywordArgumentsEvaluateInSourceOrder() throws Exception {
        Path source = program("""
                (define counter 0)
                (define next (fun () (set! counter (+ counter 1)) counter))
                (define encode (fun (left right) (+ (* left 10) right)))
                (encode :right (next) :left (next))
                """);

        assertEquals("21", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    @Test
    void recordArgumentsEvaluateInSourceOrderButStoreInDeclarationOrder() throws Exception {
        Path source = program("""
                (define counter 0)
                (define next (fun () (set! counter (+ counter 1)) counter))
                (record Pair [left Int] [right Int])
                (Pair :right (next) :left (next))
                """);

        assertEquals("(record Pair [left 2] [right 1])", interpret(source));
        assertEquals("(record Pair [left Int] [right Int])", typecheck(source));
    }

    @Test
    void callOperatorEvaluatesBeforeItsArguments() throws Exception {
        Path source = program("""
                (define state 0)
                (define make-operator
                  (fun ()
                    (set! state 1)
                    (fun (value) (+ (* state 10) value))))
                (define argument (fun () (set! state 2) 3))
                ((make-operator) (argument))
                """);

        assertEquals("23", interpret(source));
    }

    @Test
    void conditionalsEvaluateOnlyTheSelectedRuntimeBranch() throws Exception {
        Path source = program("(if true 1 (/ 1 0))");

        assertEquals("1", interpret(source));
        assertEquals("Int", typecheck(source));
    }

    @Test
    void annotatedRequiredKeywordArgumentsCannotBeOmitted() throws Exception {
        Path source = program("""
                (define identity (fun ([value Int] [-> Int]) value))
                (identity)
                """);

        assertLanguageError(() -> typecheck(source), "argument not supplied for: value");
    }

    @Test
    void anyIsTheTopTypeIncludingAtReturnPositions() throws Exception {
        Path source = program("""
                (define widen (fun ([value Int] [-> Any]) value))
                (widen 1)
                """);

        assertEquals("Any", typecheck(source));
    }

    @Test
    void unionAnnotationsAcceptMembersAndRejectOtherTypes() throws Exception {
        Path integer = program("""
                (define numeric (fun ([value (U Int Float)]) value))
                (numeric 1)
                """);
        Path floating = program("""
                (define numeric (fun ([value (U Int Float)]) value))
                (numeric 1.5)
                """);
        Path invalid = program("""
                (define numeric (fun ([value (U Int Float)]) value))
                (numeric true)
                """);

        assertEquals("Int", typecheck(integer));
        assertEquals("Float", typecheck(floating));
        assertLanguageError(() -> typecheck(invalid), "type error. expected: (U Int Float), actual: Bool");
    }

    @Test
    void emptyUnionTypesAreRejected() throws Exception {
        Path source = program("""
                (define impossible (fun ([value (U)]) value))
                impossible
                """);

        assertLanguageError(() -> typecheck(source), "union type requires at least one member");
    }

    @Test
    void computedTypeExpressionsOutsideUnionAreRejected() throws Exception {
        Path source = program("(define invalid (fun ([value (+ 1 2)]) value))");

        assertLanguageError(() -> typecheck(source), "unsupported type expression: (+ 1 2)");
    }

    @Test
    void standaloneDeclareFormsAreRejectedInsteadOfBecomingNoOps() throws Exception {
        Path source = program("(declare [value Int])");

        assertLanguageError(() -> typecheck(source), "standalone declare forms are unsupported");
    }

    @Test
    void unknownDescriptorPropertiesAreRejected() throws Exception {
        Path source = program("(define invalid (fun ([value Int :mutable true]) value))");

        assertLanguageError(() -> typecheck(source), "unsupported descriptor property: :mutable");
    }

    @Test
    void returnDescriptorMustBeLast() throws Exception {
        Path source = program("(define invalid (fun ([-> Int] [value Int]) value))");

        assertLanguageError(() -> typecheck(source), "return descriptor must be last");
    }

    @Test
    void vectorTypesAreStructurallyEquivalent() throws Exception {
        Path valid = program("""
                (define pair [1 2])
                (set! pair [3 4])
                pair
                """);
        Path invalid = program("""
                (define pair [1 2])
                (set! pair [3 true])
                """);

        assertEquals("[Int Int]", typecheck(valid));
        assertLanguageError(() -> typecheck(invalid), "assignment type error");
    }

    @Test
    void recordInheritanceDefinesNominalSubtyping() throws Exception {
        Path source = program("""
                (record Parent [value Int])
                (record Child (Parent) [name String])
                (define as-parent (fun ([item Parent] [-> Parent]) item))
                (as-parent (Child :value 1 :name "child"))
                """);

        assertTrue(typecheck(source).startsWith("(record Parent"));
        assertEquals("(record Child [name \"child\"] [value 1])", interpret(source));
    }

    @Test
    void unrelatedRecordTypesAreNotSubtypes() throws Exception {
        Path source = program("""
                (record Expected [value Int])
                (record Other [value Int])
                (define accept (fun ([item Expected]) item))
                (accept (Other :value 1))
                """);

        assertLanguageError(() -> typecheck(source), "type error. expected: (record Expected");
    }

    @Test
    void typeEquivalenceIsStructuralForVectorsAndOrderIndependentForUnions() {
        YinType firstVector = new VectorType(List.of(Types.INT, Types.STRING));
        YinType secondVector = new VectorType(List.of(Types.INT, Types.STRING));
        YinType differentVector = new VectorType(List.of(Types.STRING, Types.INT));
        YinType firstUnion = UnionType.union(Types.INT, Types.STRING);
        YinType reversedUnion = UnionType.union(Types.STRING, Types.INT);
        YinType addition = PrimitiveFunctionType.arithmetic("+");
        YinType subtraction = PrimitiveFunctionType.arithmetic("-");

        assertTrue(Types.equivalent(firstVector, secondVector));
        assertFalse(Types.equivalent(firstVector, differentVector));
        assertTrue(Types.equivalent(firstUnion, reversedUnion));
        assertFalse(Types.equivalent(addition, subtraction));
    }

    private String interpret(Path source) {
        return new Interpreter(source.toString()).interp(source.toString()).toString();
    }

    private String typecheck(Path source) {
        return new TypeChecker(source.toString()).typecheck(source.toString()).toString();
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private void assertLanguageError(ThrowingAction action, String expected) {
        GeneralError error = assertThrows(GeneralError.class, action::run);
        assertTrue(error.getMessage().contains(expected),
                () -> "expected error containing '" + expected + "', got: " + error);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
