package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.Vector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void staticTypesAreNotRuntimeValues() {
        assertFalse(Value.class.isAssignableFrom(YinType.class));
        assertInstanceOf(YinType.class, Types.INT);
        assertFalse(Value.class.isInstance(Types.INT));
    }

    @Test
    void typeNamesExistOnlyInTheStaticEnvironment() throws Exception {
        Path source = program("Int");

        assertEquals("Int", new TypeChecker(source.toString()).typecheck(source.toString()).toString());
        GeneralError error = assertThrows(GeneralError.class,
                () -> new Interpreter(source.toString()).interp(source.toString()));
        assertTrue(error.getMessage().contains("unbound variable: Int"));
    }

    @Test
    void languageDiagnosticsExposeAStableCodeAndSourceSpan() throws Exception {
        Path source = program("\nmissing");

        GeneralError error = assertThrows(GeneralError.class,
                () -> new Interpreter(source.toString()).interp(source.toString()));
        Diagnostic diagnostic = error.diagnostic;
        SourceSpan span = diagnostic.sourceSpan().orElseThrow();

        assertEquals(Diagnostic.Code.LANGUAGE, diagnostic.code());
        assertEquals(source.toRealPath().toString(), span.file());
        assertEquals(1, span.line());
        assertEquals(0, span.column());
        assertEquals(1, span.start());
        assertEquals(8, span.end());
        assertTrue(error.toString().contains("[YIN0001]"));
    }

    @Test
    void syntaxDiagnosticsRetainTheirFileAndOffendingRange() throws Exception {
        Path source = program("`");

        GeneralError error = assertThrows(GeneralError.class,
                () -> new TypeChecker(source.toString()).typecheck(source.toString()));
        SourceSpan span = error.diagnostic.sourceSpan().orElseThrow();

        assertEquals(Diagnostic.Code.SYNTAX, error.diagnostic.code());
        assertEquals(source.toRealPath().toString(), span.file());
        assertEquals(0, span.start());
        assertEquals(1, span.end());
        assertTrue(error.toString().contains("[YIN1001]"));
    }

    @Test
    void missingFilesProduceStructuredIoDiagnostics() throws Exception {
        Path missing = tempDir.resolve("missing.yin");

        GeneralError error = assertThrows(GeneralError.class,
                () -> new Interpreter(missing.toString()).interp(missing.toString()));

        assertEquals(Diagnostic.Code.IO, error.diagnostic.code());
        assertEquals(missing.toFile().getCanonicalPath(),
                error.diagnostic.sourceSpan().orElseThrow().file());
        assertTrue(error.toString().contains("[YIN1002]"));
    }

    @Test
    void dottedFieldSyntaxUsesTheImmutableFieldAccessCore() throws Exception {
        Path source = program("""
                (record Point [x Int])
                (define point (Point :x 42))
                point.x
                """);

        assertEquals("42", new Interpreter(source.toString())
                .interp(source.toString()).toString());
        assertEquals("Int", new TypeChecker(source.toString())
                .typecheck(source.toString()).toString());
    }

    @Test
    void vectorValuesOwnAnImmutableSnapshotOfTheirElements() {
        List<Value> source = new ArrayList<>(List.of(new IntValue(1)));
        Vector vector = new Vector(source);

        source.add(new IntValue(2));

        assertEquals(1, vector.size());
        assertThrows(UnsupportedOperationException.class,
                () -> vector.values().add(new IntValue(3)));
    }

    private Path program(String source) throws Exception {
        Path file = tempDir.resolve("program-" + System.nanoTime() + ".yin");
        return Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
