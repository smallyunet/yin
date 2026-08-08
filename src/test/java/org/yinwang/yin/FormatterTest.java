package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatterTest {

    @TempDir
    Path tempDir;

    @Test
    void formatsFunctionsAndPreservesComments() {
        String source = "-- keep me\n(define   add-two (fun (x) (+ x 2))) -- result\n";

        assertEquals("""
                -- keep me
                (define add-two
                  (fun (x)
                    (+ x 2))) -- result
                """, Formatter.format("<test>", source));
    }

    @Test
    void formattingIsIdempotent() {
        String source = """
                (define fact
                  (fun ([x Int] [-> Int])
                    (if (= x 0) 1 (* x (fact (- x 1))))))
                """;

        String once = Formatter.format("<test>", source);

        assertEquals(once, Formatter.format("<test>", once));
    }

    @Test
    void inlineCommentsInsideFormsCannotConsumeFollowingCode() {
        String formatted = Formatter.format("<test>", "(print 1 -- first\n 2)");

        assertEquals("""
                (print
                  1 -- first
                  2)
                """, formatted);
        assertEquals(formatted, Formatter.format("<test>", formatted));
    }

    @Test
    void formattingPreservesProgramBehavior() throws Exception {
        String source = "(define value 40)\n(+  value  2)\n";
        Path before = write("before.yin", source);
        Path after = write("after.yin", Formatter.format("<test>", source));

        assertEquals(new Interpreter(before.toString()).interp(before.toString()).toString(),
                new Interpreter(after.toString()).interp(after.toString()).toString());
        assertEquals(new TypeChecker(before.toString()).typecheck(before.toString()).toString(),
                new TypeChecker(after.toString()).typecheck(after.toString()).toString());
    }

    @Test
    void rejectsInvalidProgramsInsteadOfRewritingThem() {
        GeneralError error = assertThrows(GeneralError.class,
                () -> Formatter.format("<test>", "(+ 1"));

        assertEquals(Diagnostic.Code.SYNTAX, error.diagnostic.code());
    }

    @Test
    void checkAndWriteModesShareTheCanonicalResult() throws Exception {
        Path source = write("format.yin", "(+   1 2)");
        StringWriter errors = new StringWriter();

        int checkBefore = Formatter.run(new String[]{"--check", source.toString()},
                writer(), new PrintWriter(errors, true));
        int write = Formatter.run(new String[]{"--write", source.toString()},
                writer(), writer());
        int checkAfter = Formatter.run(new String[]{"--check", source.toString()},
                writer(), writer());

        assertEquals(1, checkBefore);
        assertTrue(errors.toString().contains("would reformat"));
        assertEquals(0, write);
        assertEquals(0, checkAfter);
        assertEquals("(+ 1 2)\n", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void maintainedProgramsUseCanonicalFormatting() throws Exception {
        List<Path> programs;
        try (var files = Files.list(Path.of("tests"))) {
            programs = files
                    .filter(path -> path.getFileName().toString().endsWith(".yin"))
                    .sorted()
                    .toList();
        }

        for (Path program : programs) {
            String source = Files.readString(program, StandardCharsets.UTF_8);
            assertEquals(source, Formatter.format(program.toString(), source), program.toString());
        }
    }

    private Path write(String name, String source) throws Exception {
        return Files.writeString(tempDir.resolve(name), source, StandardCharsets.UTF_8);
    }

    private PrintWriter writer() {
        return new PrintWriter(new StringWriter(), true);
    }
}
