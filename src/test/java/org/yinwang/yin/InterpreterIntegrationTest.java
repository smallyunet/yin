package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.value.Value;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void runsTheMaintainedExamplePrograms() {
        assertEquals("[1 2 3 4 5]", interpret("tests/array.yin").toString());
        assertEquals("[]", interpret("tests/empty-vector.yin").toString());
        assertEquals("5", interpret("tests/arithmetic.yin").toString());
        assertEquals("-1", interpret("tests/function1.yin").toString());
        assertEquals("120", interpret("tests/recursion-direct.yin").toString());
        assertEquals("void", interpret("tests/recursion-mutual.yin").toString());
        assertEquals("42", interpret("tests/record-field-access.yin").toString());
    }

    @Test
    void printAcceptsMultipleArguments() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertEquals("void", interpret("tests/expr.yin").toString());
        } finally {
            System.setOut(original);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\"世界\""));
        assertTrue(text.contains("42, \"ok\", true"));
    }

    @Test
    void evaluatesKeywordArgumentsInTheCallerScope() throws Exception {
        Path program = program("""
                (define identity (fun ([x Int] [-> Int]) x))
                (define call-identity
                  (fun ([value Int] [-> Int])
                    (identity :x value)))
                (call-identity 7)
                """);

        assertEquals("7", interpret(program.toString()).toString());
    }

    @Test
    void initializesRecordFieldsFromKeywordArgumentsAndDefaults() throws Exception {
        Path program = program("""
                (record Point [x Int] [y Int :default 2])
                (define point (Point :x 1))
                point
                """);

        assertEquals("(record Point [x 1] [y 2])", interpret(program.toString()).toString());
    }

    @Test
    void reportsWrongFunctionArityAsALanguageError() throws Exception {
        Path program = program("""
                (define identity (fun (x) x))
                (identity 1 2)
                """);

        GeneralError error = assertThrows(GeneralError.class,
                () -> interpret(program.toString()));
        assertTrue(error.getMessage().contains("wrong number of arguments"));
    }

    @Test
    void emptyProgramEvaluatesToVoid() throws Exception {
        assertEquals("void", interpret(program("").toString()).toString());
    }

    @Test
    void rejectsNonBooleanConditionsAsLanguageErrors() throws Exception {
        GeneralError error = assertThrows(GeneralError.class,
                () -> interpret(program("(if 1 2 3)").toString()));
        assertTrue(error.getMessage().contains("test is not boolean"));
    }

    @Test
    void rejectsIntegerDivisionByZeroAsALanguageError() throws Exception {
        GeneralError error = assertThrows(GeneralError.class,
                () -> interpret(program("(/ 1 0)").toString()));
        assertTrue(error.getMessage().contains("division by zero"));
    }

    private Value interpret(String file) {
        return new Interpreter(file).interp(file);
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
