package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.lsp.LanguageService;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleIntegrationTest {
    @TempDir Path tempDir;

    @Test void selectivelyImportsFunctionsRecordsAndValues() throws Exception {
        write("math.yin", """
                (module math [Point double origin]
                  (record Point [x Int] [y Int])
                  (define double (fun ([value Int] [-> Int]) (* value 2)))
                  (define origin (Point :x 0 :y 0)))
                """);
        Path main = write("main.yin", """
                (import "./math.yin" [Point double origin])
                (define point (Point :x 2 :y 3))
                [(double 21) origin.x point.y]
                """);

        assertEquals("[42 0 3]", interpret(main));
        assertEquals("[Int Int Int]", typecheck(main));
    }

    @Test void resolvesTransitiveImportsRelativeToEachModule() throws Exception {
        write("shared/base.yin", """
                (module base [increment]
                  (define increment (fun ([value Int] [-> Int]) (+ value 1))))
                """);
        write("feature/value.yin", """
                (module value [answer]
                  (import "../shared/base.yin" [increment])
                  (define answer (increment 41)))
                """);
        Path main = write("main.yin", """
                (import "./feature/value.yin" [answer])
                answer
                """);

        assertEquals("42", interpret(main));
        assertEquals("Int", typecheck(main));
    }

    @Test void evaluatesOneModuleOnlyOncePerProgram() throws Exception {
        write("counter.yin", """
                (module counter [next]
                  (define count 0)
                  (define next
                    (fun ([-> Int])
                      (set! count (+ count 1))
                      count)))
                """);
        write("left.yin", """
                (module left [left]
                  (import "./counter.yin" [next])
                  (define left (fun ([-> Int]) (next))))
                """);
        write("right.yin", """
                (module right [right]
                  (import "./counter.yin" [next])
                  (define right (fun ([-> Int]) (next))))
                """);
        Path main = write("main.yin", """
                (import "./left.yin" [left])
                (import "./right.yin" [right])
                [(left) (right)]
                """);

        assertEquals("[1 2]", interpret(main));
        assertEquals("[Int Int]", typecheck(main));
    }

    @Test void rejectsPrivateMissingAndConflictingBindings() throws Exception {
        write("library.yin", """
                (module library [public]
                  (define public 1)
                  (define private 2))
                """);
        Path privateImport = write("private-main.yin",
                "(import \"./library.yin\" [private])\nprivate\n");
        Path conflict = write("conflict-main.yin", """
                (define public 0)
                (import "./library.yin" [public])
                public
                """);

        assertLanguageError(() -> interpret(privateImport), "does not export: private");
        assertLanguageError(() -> typecheck(privateImport), "does not export: private");
        assertLanguageError(() -> interpret(conflict), "import conflicts with an existing binding");
        assertLanguageError(() -> typecheck(conflict), "import conflicts with an existing binding");
    }

    @Test void rejectsUndefinedExportsAndNonModuleImports() throws Exception {
        write("broken.yin", "(module broken [missing] (define present 1))\n");
        write("plain.yin", "(define value 1)\n");
        Path undefined = write("undefined-main.yin",
                "(import \"./broken.yin\" [missing])\n");
        Path plain = write("plain-main.yin",
                "(import \"./plain.yin\" [value])\n");

        assertLanguageError(() -> typecheck(undefined), "exports an undefined binding: missing");
        assertLanguageError(() -> interpret(undefined), "exports an undefined binding: missing");
        assertLanguageError(() -> typecheck(plain), "must contain exactly one module declaration");
    }

    @Test void reportsCircularImportsWithTheDependencyChain() throws Exception {
        write("a.yin", """
                (module a [a]
                  (import "./b.yin" [b])
                  (define a b))
                """);
        write("b.yin", """
                (module b [b]
                  (import "./a.yin" [a])
                  (define b a))
                """);
        Path main = write("main.yin", "(import \"./a.yin\" [a])\na\n");

        assertLanguageError(() -> typecheck(main), "circular module import: a.yin -> b.yin -> a.yin");
        assertLanguageError(() -> interpret(main), "circular module import: a.yin -> b.yin -> a.yin");
    }

    @Test void dependencyTypeErrorsRetainTheDependencySourceSpan() throws Exception {
        Path dependency = write("bad.yin", """
                (module bad [value]
                  (define value (+ 1 true)))
                """);
        Path main = write("main.yin", "(import \"./bad.yin\" [value])\nvalue\n");

        GeneralError error = assertThrows(GeneralError.class, () -> typecheck(main));
        assertEquals(dependency.toRealPath().toString(),
                error.diagnostic.sourceSpan().orElseThrow().file());
    }

    @Test void languageServiceResolvesImportsFromFileUris() throws Exception {
        write("values.yin", "(module values [answer] (define answer 42))\n");
        Path main = tempDir.resolve("main.yin");
        String source = "(import \"./values.yin\" [answer])\n(+ answer 1)\n";

        assertTrue(new LanguageService().diagnose(main.toUri().toString(), source).isEmpty());
    }

    @Test void sameNamedTypesFromDifferentModulesRemainNominallyDistinct() throws Exception {
        write("a.yin", """
                (module a [make-a]
                  (record Item [value Int])
                  (define make-a (fun ([-> Item]) (Item :value 1))))
                """);
        write("b.yin", """
                (module b [read-b]
                  (record Item [value Int])
                  (define read-b (fun ([item Item] [-> Int]) item.value)))
                """);
        Path main = write("main.yin", """
                (import "./a.yin" [make-a])
                (import "./b.yin" [read-b])
                (read-b (make-a))
                """);

        assertLanguageError(() -> typecheck(main), "type error. expected:");
    }

    @Test void parserRejectsInvalidModuleAndImportContracts() throws Exception {
        Path absolute = write("absolute.yin",
                "(import \"/tmp/value.yin\" [value])\n");
        Path duplicate = write("duplicate.yin",
                "(module duplicate [value value] (define value 1))\n");
        Path entryModule = write("entry.yin",
                "(module entry [value] (define value 1))\n");

        assertLanguageError(() -> typecheck(absolute), "import path must be relative");
        assertLanguageError(() -> typecheck(duplicate), "duplicate module export: value");
        assertLanguageError(() -> interpret(entryModule),
                "module declarations can only be loaded through import");
    }

    @Test void digestBoundSecurityProfilesRejectModulesExplicitly() throws Exception {
        write("value.yin", "(module value [answer] (define answer 42))\n");
        Path main = write("main.yin", "(import \"./value.yin\" [answer])\nanswer\n");
        String source = Files.readString(main, StandardCharsets.UTF_8);

        assertLanguageError(
                () -> DeterministicContractRuntime.checkSource(main.toString(), source),
                "modules is outside deterministic-policy-v1");
        assertLanguageError(
                () -> ModuleBoundary.requireSingleFile(main.toString(), source, "--gateway"),
                "dependency digests are bound");
    }

    private Path write(String relative, String source) throws Exception {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static String interpret(Path source) {
        Value value = new Interpreter(source.toString()).interp(source.toString());
        return value.toString();
    }

    private static String typecheck(Path source) {
        YinType type = new TypeChecker(source.toString()).typecheck(source.toString());
        return type.toString();
    }

    private static void assertLanguageError(Runnable action, String message) {
        GeneralError error = assertThrows(GeneralError.class, action::run);
        assertTrue(error.getMessage().contains(message), error.toString());
    }
}
