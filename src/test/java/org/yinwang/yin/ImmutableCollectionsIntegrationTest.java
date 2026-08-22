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

class ImmutableCollectionsIntegrationTest {
    @TempDir Path tempDir;

    @Test void dictionariesAreImmutableOrderedAndSafelyAccessible() throws Exception {
        Path source = program("""
                (define original (dict "name" "yin" "version" "0.19"))
                (define updated (dict/put original "status" "experimental"))
                [(dict/get original "status")
                 (dict/get updated "status")
                 (dict/keys updated)
                 (dict/values updated)
                 (dict/size original)
                 (dict/size updated)]
                """);

        assertEquals("[none (some \"experimental\") [\"name\" \"version\" \"status\"] "
                + "[\"yin\" \"0.19\" \"experimental\"] 2 3]", interpret(source));
        assertEquals("[(Option String) (Option String) (Vector String) (Vector String) Int Int]",
                typecheck(source));
    }

    @Test void dictionaryUpdatePreservesPositionAndRemoveIsPersistent() throws Exception {
        Path source = program("""
                (define original (dict "a" 1 "b" 2))
                (define updated (dict/put original "a" 10))
                (define removed (dict/remove updated "b"))
                [original updated removed (dict/keys updated)]
                """);

        assertEquals("[(dict \"a\" 1 \"b\" 2) (dict \"a\" 10 \"b\" 2) "
                + "(dict \"a\" 10) [\"a\" \"b\"]]", interpret(source));
    }

    @Test void emptyCollectionsUseBottomTypesAndFitTypedAnnotations() throws Exception {
        Path source = program("""
                (define empty-dict
                  (fun ([-> (Dict String Int)]) (dict)))
                (define empty-set
                  (fun ([-> (Set String)]) (set)))
                [(empty-dict) (empty-set)]
                """);

        assertEquals("[(dict) (set)]", interpret(source));
        assertEquals("[(Dict String Int) (Set String)]", typecheck(source));
    }

    @Test void dictionaryLookupRequiresACompatibleKeyType() throws Exception {
        Path source = program("(dict/get (dict \"answer\" 42) true)");
        assertError(() -> typecheck(source), "dict/get key is incompatible with String, got: Bool");
    }

    @Test void dictionaryConstructorRejectsUnpairedArguments() throws Exception {
        Path source = program("(dict \"answer\")");
        assertError(() -> interpret(source), "dict expects key/value pairs");
        assertError(() -> typecheck(source), "dict expects key/value pairs");
    }

    @Test void keysAndSetMembersRequireTotalStructuralEquality() throws Exception {
        Path dictionary = program("(dict (fun (value) value) 1)");
        Path set = program("(set (fun (value) value))");
        assertError(() -> interpret(dictionary), "dict key requires structurally comparable values");
        assertError(() -> typecheck(dictionary), "dict key requires a structurally comparable type");
        assertError(() -> interpret(set), "set requires structurally comparable values");
        assertError(() -> typecheck(set), "set requires a structurally comparable type");
    }

    @Test void setsDeduplicateAndProvidePersistentAlgebra() throws Exception {
        Path source = program("""
                (define left (set 1 2 2 3))
                (define right (set 3 4))
                [left
                 (set/add left 4)
                 (set/remove left 2)
                 (set/union left right)
                 (set/intersection left right)
                 (set/difference left right)
                 (set/contains left 2)
                 (set/size left)
                 (set/values left)]
                """);

        assertEquals("[(set 1 2 3) (set 1 2 3 4) (set 1 3) (set 1 2 3 4) "
                + "(set 3) (set 1 2) true 3 [1 2 3]]", interpret(source));
        assertEquals("[(Set Int) (Set Int) (Set Int) (Set Int) (Set Int) (Set Int) "
                + "Bool Int (Vector Int)]", typecheck(source));
    }

    @Test void dictionaryAndSetEqualityIgnoreInsertionOrder() throws Exception {
        Path source = program("""
                [(= (dict "a" 1 "b" 2) (dict "b" 2 "a" 1))
                 (= (set 1 2 3) (set 3 2 1))]
                """);
        assertEquals("[true true]", interpret(source));
        assertEquals("[Bool Bool]", typecheck(source));
    }

    @Test void jsonRoundTripsStringDictionariesAndSetsDeterministically() throws Exception {
        Path source = program("""
                [(decode-json (Dict String Int) "{\\\"one\\\":1,\\\"two\\\":2}")
                 (decode-json (Set String) "[\\\"a\\\",\\\"b\\\",\\\"a\\\"]")
                 (encode-json (dict "one" 1 "two" 2))
                 (encode-json (set "a" "b"))
                 (json-schema (Dict String Int))
                 (json-schema (Set String))]
                """);

        String value = interpret(source);
        assertTrue(value.startsWith("[(ok (dict \"one\" 1 \"two\" 2)) (ok (set \"a\" \"b\"))"), value);
        assertTrue(value.contains("\\\"additionalProperties\\\":{\\\"type\\\":\\\"integer\\\""), value);
        assertTrue(value.contains("\\\"uniqueItems\\\":true"), value);
    }

    @Test void jsonEncodingRejectsNonStringDictionaryKeysAsAResult() throws Exception {
        String value = interpret(program("(encode-json (dict 1 \"one\"))"));
        assertTrue(value.startsWith("(err (record EncodeError"), value);
        assertTrue(value.contains("non-string-key"), value);
    }

    @Test void jsonSchemaRejectsDictionaryKeyTypesThatCannotRepresentObjectNames() throws Exception {
        Path source = program("(json-schema (Dict Int String))");
        assertError(() -> typecheck(source),
                "json-schema requires a dictionary key type that accepts String, got: Int");
        assertError(() -> interpret(source),
                "json-schema non-string-key: JSON object schemas require Dict String keys");
    }

    private Path program(String source) throws Exception {
        return Files.writeString(tempDir.resolve("collections-" + System.nanoTime() + ".yin"),
                source, StandardCharsets.UTF_8);
    }

    private String interpret(Path source) {
        return new Interpreter(source.toString()).interp(source.toString()).toString();
    }

    private String typecheck(Path source) {
        YinType type = new TypeChecker(source.toString()).typecheck(source.toString());
        return type.toString();
    }

    private static void assertError(Action action, String expected) {
        GeneralError error = assertThrows(GeneralError.class, action::run);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
}
