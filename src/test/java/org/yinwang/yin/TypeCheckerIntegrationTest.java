package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.type.YinType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCheckerIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void checksTheMaintainedExamplePrograms() {
        assertEquals("[Int Int Int Int Int]", typecheck("tests/array.yin").toString());
        assertEquals("[]", typecheck("tests/empty-vector.yin").toString());
        assertEquals("Int", typecheck("tests/arithmetic.yin").toString());
        assertEquals("void", typecheck("tests/expr.yin").toString());
        assertEquals("Int", typecheck("tests/function1.yin").toString());
        assertEquals("Int", typecheck("tests/recursion-direct.yin").toString());
        assertEquals("void", typecheck("tests/recursion-mutual.yin").toString());
        assertEquals("Int", typecheck("tests/record-field-access.yin").toString());
    }

    @Test
    void supportsFloatLiteralsAndMixedNumericArithmetic() throws Exception {
        Path program = program("(+ 1.5 2)");
        assertEquals("Float", typecheck(program.toString()).toString());
    }

    @Test
    void rejectsNonNumericArithmetic() throws Exception {
        Path program = program("(+ 1.5 true)");
        GeneralError error = assertThrows(GeneralError.class,
                () -> typecheck(program.toString()));
        assertTrue(error.getMessage().contains("incorrect argument types for +"));
    }

    @Test
    void checksKeywordArgumentsInTheCallerScope() throws Exception {
        Path program = program("""
                (define identity (fun ([x Int] [-> Int]) x))
                (define call-identity
                  (fun ([value Int] [-> Int])
                    (identity :x value)))
                (call-identity 7)
                """);

        assertEquals("Int", typecheck(program.toString()).toString());
    }

    @Test
    void checksRecordConstruction() throws Exception {
        Path program = program("""
                (record Point [x Int] [y Int :default 2])
                (define point (Point :x 1))
                point
                """);

        assertEquals("(record Point [x Int] [y Int])", typecheck(program.toString()).toString());
    }

    private YinType typecheck(String file) {
        return new TypeChecker(file).typecheck(file);
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
