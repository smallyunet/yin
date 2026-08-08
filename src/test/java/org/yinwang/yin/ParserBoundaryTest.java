package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsARecordWithNoFields() throws Exception {
        Path program = program("""
                (record Empty)
                (Empty)
                """);

        assertEquals("(record Empty)", new Interpreter(program.toString())
                .interp(program.toString()).toString());
        assertEquals("(record Empty)", new TypeChecker(program.toString())
                .typecheck(program.toString()).toString());
    }

    @Test
    void reportsUnclosedDelimitersWithoutTerminatingTheJvm() throws Exception {
        Path program = program("(+ 1 2");

        GeneralError error = assertThrows(GeneralError.class,
                () -> new Interpreter(program.toString()).interp(program.toString()));
        assertTrue(error.getMessage().contains("unclosed delimeter"));
    }

    @Test
    void reportsMissingFilesAsLanguageErrors() {
        Path missing = tempDir.resolve("missing.yin");

        GeneralError error = assertThrows(GeneralError.class,
                () -> new Interpreter(missing.toString()).interp(missing.toString()));
        assertTrue(error.getMessage().contains("failed to read file"));
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
