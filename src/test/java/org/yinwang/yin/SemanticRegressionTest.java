package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.value.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsUnboundNamesAtRuntimeAndDuringTypeChecking() throws Exception {
        Path program = program("missing");

        assertLanguageError(() -> interpret(program), "unbound variable: missing");
        assertLanguageError(() -> typecheck(program), "unbound variable: missing");
    }

    @Test
    void rejectsRedefinitionInTheSameScope() throws Exception {
        Path program = program("""
                (define answer 1)
                (define answer 2)
                """);

        assertLanguageError(() -> interpret(program), "trying to redefine name: answer");
        assertLanguageError(() -> typecheck(program), "trying to redefine name: answer");
    }

    @Test
    void assignmentUpdatesTheNearestLexicalBinding() throws Exception {
        Path program = program("""
                (define counter 1)
                (define increment
                  (fun ()
                    (set! counter (+ counter 1))))
                (increment)
                counter
                """);

        assertEquals("2", interpret(program).toString());
        assertEquals("Int", typecheck(program).toString());
    }

    @Test
    void rejectsAssignmentToAnUndefinedName() throws Exception {
        Path program = program("(set! missing 1)");

        assertLanguageError(() -> interpret(program), "assigned name was not defined: missing");
        assertLanguageError(() -> typecheck(program), "assigned name was not defined: missing");
    }

    @Test
    void rejectsAssignmentThatChangesAStaticType() throws Exception {
        Path program = program("""
                (define answer 1)
                (set! answer true)
                """);

        assertLanguageError(() -> typecheck(program), "assignment type error");
    }

    @Test
    void closuresCaptureTheirLexicalEnvironment() throws Exception {
        Path program = program("""
                (define make-adder
                  (fun (x)
                    (fun (y) (+ x y))))
                (define add-two (make-adder 2))
                (add-two 5)
                """);

        assertEquals("7", interpret(program).toString());
        assertEquals("Int", typecheck(program).toString());
    }

    @Test
    void innerDefinitionsShadowOuterBindings() throws Exception {
        Path program = program("""
                (define x 1)
                (define local (fun () (define x 2) x))
                (local)
                x
                """);

        assertEquals("1", interpret(program).toString());
        assertEquals("Int", typecheck(program).toString());
    }

    @Test
    void functionDefaultsAreEvaluatedWhenTheFunctionIsDefined() throws Exception {
        Path program = program("""
                (define initial 1)
                (define choose (fun ([value Int :default initial] [-> Int]) value))
                (set! initial 2)
                (choose)
                """);

        assertEquals("1", interpret(program).toString());
        assertEquals("Int", typecheck(program).toString());
    }

    @Test
    void rejectsMissingAndExtraFunctionKeywords() throws Exception {
        Path missing = program("""
                (define pair (fun (left right) left))
                (pair :left 1)
                """);
        Path extra = program("""
                (define identity (fun (value) value))
                (identity :value 1 :other 2)
                """);

        assertLanguageError(() -> interpret(missing), "argument not supplied for: right");
        assertLanguageError(() -> typecheck(missing), "argument not supplied for: right");
        assertLanguageError(() -> interpret(extra), "extra keyword arguments: [other]");
        assertLanguageError(() -> typecheck(extra), "extra keyword arguments: [other]");
    }

    @Test
    void rejectsDuplicateAndMixedFunctionArguments() throws Exception {
        Path duplicate = program("""
                (define identity (fun (value) value))
                (identity :value 1 :value 2)
                """);
        Path mixed = program("""
                (define pair (fun (left right) left))
                (pair 1 :right 2)
                """);

        assertLanguageError(() -> interpret(duplicate), "duplicated keyword: :value");
        assertLanguageError(() -> typecheck(duplicate), "duplicated keyword: :value");
        assertLanguageError(() -> interpret(mixed), "mix positional and keyword arguments not allowed");
        assertLanguageError(() -> typecheck(mixed), "mix positional and keyword arguments not allowed");
    }

    @Test
    void rejectsDuplicateDescriptorProperties() throws Exception {
        Path program = program("(record Point [x Int :default 1 :default 2])");

        assertLanguageError(() -> interpret(program), "duplicated keyword: :default");
        assertLanguageError(() -> typecheck(program), "duplicated keyword: :default");
    }

    @Test
    void recordInheritanceIncludesParentFields() throws Exception {
        Path program = program("""
                (record Position [x Int :default 1])
                (record NamedPosition (Position) [name String])
                (NamedPosition :name "origin")
                """);

        assertEquals("(record NamedPosition [name \"origin\"] [x 1])", interpret(program).toString());
        assertEquals("(record NamedPosition [name String] [x Int])", typecheck(program).toString());
    }

    @Test
    void interpreterAndTypeCheckerRejectInheritedFieldConflicts() throws Exception {
        Path program = program("""
                (record Parent [value Int])
                (record Child (Parent) [value Int])
                """);

        assertLanguageError(() -> interpret(program), "conflicting field value inherited from parent Parent");
        assertLanguageError(() -> typecheck(program), "conflicting field value inherited from parent Parent");
    }

    @Test
    void rejectsNonRecordParents() throws Exception {
        Path program = program("""
                (define NotARecord 1)
                (record Child (NotARecord))
                """);

        assertLanguageError(() -> interpret(program), "parent is not a record");
        assertLanguageError(() -> typecheck(program), "parent is not a record");
    }

    @Test
    void recordDefaultsAreEvaluatedWhenTheRecordIsDefined() throws Exception {
        Path program = program("""
                (define initial 1)
                (record Box [value Int :default initial])
                (set! initial 2)
                (Box)
                """);

        assertEquals("(record Box [value 1])", interpret(program).toString());
        assertEquals("(record Box [value Int])", typecheck(program).toString());
    }

    @Test
    void supportsVectorDestructuringForDefinitionAndAssignment() throws Exception {
        Path define = program("""
                (define [left right] [1 2])
                (+ left right)
                """);
        Path assign = program("""
                (define left 0)
                (define right 0)
                (set! [left right] [3 4])
                (+ left right)
                """);

        assertEquals("3", interpret(define).toString());
        assertEquals("Int", typecheck(define).toString());
        assertEquals("7", interpret(assign).toString());
        assertEquals("Int", typecheck(assign).toString());
    }

    @Test
    void rejectsDestructuringWithTheWrongVectorSize() throws Exception {
        Path program = program("(define [left right] [1])");

        assertLanguageError(() -> interpret(program), "different sizes");
        assertLanguageError(() -> typecheck(program), "different sizes");
    }

    @Test
    void conditionalTypeIsTheUnionOfItsBranches() throws Exception {
        Path program = program("(if true 1 \"one\")");

        String type = typecheck(program).toString();
        assertTrue(type.startsWith("(U "));
        assertTrue(type.contains("Int"));
        assertTrue(type.contains("String"));
    }

    @Test
    void booleanPrimitivesRequireBooleanArguments() throws Exception {
        Path valid = program("(and true (not false))");
        Path invalid = program("(or true 1)");

        assertEquals("true", interpret(valid).toString());
        assertEquals("Bool", typecheck(valid).toString());
        assertLanguageError(() -> interpret(invalid), "incorrect argument types for or");
        assertLanguageError(() -> typecheck(invalid), "incorrect argument types for or");
    }

    @Test
    void primitiveArityErrorsAreLanguageErrors() throws Exception {
        Path program = program("(+ 1)");

        assertLanguageError(() -> interpret(program), "incorrect number of arguments for primitive +");
        assertLanguageError(() -> typecheck(program), "incorrect number of arguments for primitive +");
    }

    @Test
    void parserDiagnosticsCarryLineAndColumn() throws Exception {
        Path program = program("\n(+ 1 2");

        GeneralError error = assertThrows(GeneralError.class, () -> interpret(program));
        assertTrue(error.getMessage().contains("2:1"));
        assertTrue(error.getMessage().contains("unclosed delimeter"));
    }

    @Test
    void lexerRejectsRunawayStringsAndMalformedNumbers() throws Exception {
        Path runaway = program("\"unterminated");
        Path malformedNumber = program("12abc");

        assertLanguageError(() -> interpret(runaway), "runaway string");
        assertLanguageError(() -> typecheck(runaway), "runaway string");
        assertLanguageError(() -> interpret(malformedNumber), "incorrect number format: 12abc");
        assertLanguageError(() -> typecheck(malformedNumber), "incorrect number format: 12abc");
    }

    private Value interpret(Path file) {
        return new Interpreter(file.toString()).interp(file.toString());
    }

    private Value typecheck(Path file) {
        return new TypeChecker(file.toString()).typecheck(file.toString());
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private void assertLanguageError(ThrowingAction action, String message) {
        GeneralError error = assertThrows(GeneralError.class, action::run);
        assertTrue(error.getMessage().contains(message),
                () -> "expected error containing '" + message + "', got: " + error);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
