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

class StructuredContractsIntegrationTest {
    @TempDir Path tempDir;

    @Test void variantsHaveNamedConstructorsAndExhaustivePayloadNarrowing() throws Exception {
        Path source = program("""
                (variant Decision
                  [Approve [reason String]]
                  [Reject [reason String]]
                  [NeedsInput [question String]])
                (define explain
                  (fun ([decision Decision] [-> String])
                    (match decision
                      [(Approve reason) reason]
                      [(Reject reason) reason]
                      [(NeedsInput question) question])))
                [(explain (Approve :reason "safe"))
                 (explain (NeedsInput :question "amount?"))]
                """);
        assertEquals("[\"safe\" \"amount?\"]", interpret(source));
        assertEquals("[String String]", typecheck(source));
    }

    @Test void variantsRejectMissingCasesAndInvalidFields() throws Exception {
        Path missing = program("""
                (variant Choice [Yes] [No])
                (define choose (fun ([value Choice]) (match value [(Yes) 1])))
                choose
                """);
        Path invalid = program("""
                (variant Choice [Yes [reason String]] [No])
                (Yes :reason 42)
                """);
        assertError(() -> typecheck(missing), "non-exhaustive match");
        assertError(() -> typecheck(invalid), "expected: String, actual: Int");
    }

    @Test void optionIsFirstClassCovariantAndExhaustive() throws Exception {
        Path source = program("""
                (define describe
                  (fun ([value (Option Int)] [-> String])
                    (match value [(Some number) (to-string number)] [(None) "missing"])))
                [(describe (some 42)) (describe none) (= none none)]
                """);
        assertEquals("[\"42\" \"missing\" true]", interpret(source));
        assertEquals("[String String Bool]", typecheck(source));
    }

    @Test void strictJsonRoundTripsRecordsVariantsOptionsAndResults() throws Exception {
        Path source = program("""
                (record Request [name String] [limit Int :default 10] [note (Option String)])
                (variant Decision [Approve [reason String]] [Reject [reason String]])
                [(decode-json Request "{\\\"name\\\":\\\"yin\\\",\\\"note\\\":null}")
                 (encode-json (Request :name "yin" :note (some "ready")))
                 (encode-json (Approve :reason "safe"))
                 (encode-json (ok 42))]
                """);
        assertEquals("[(ok (record Request [name \"yin\"] [limit 10] [note none])) "
                + "(ok \"{\\\"name\\\":\\\"yin\\\",\\\"limit\\\":10,\\\"note\\\":\\\"ready\\\"}\") "
                + "(ok \"{\\\"tag\\\":\\\"Approve\\\",\\\"reason\\\":\\\"safe\\\"}\") "
                + "(ok \"{\\\"tag\\\":\\\"Ok\\\",\\\"value\\\":42}\")]", interpret(source));
    }

    @Test void decodeErrorsAreStructuredAndCarryPrecisePaths() throws Exception {
        Path source = program("""
                (record Request [name String] [count Int])
                [(decode-json Request "{\\\"name\\\":\\\"yin\\\",\\\"count\\\":\\\"many\\\"}")
                 (decode-json Request "{\\\"name\\\":\\\"yin\\\",\\\"count\\\":1,\\\"extra\\\":true}")
                 (decode-json Request "{\\\"name\\\":\\\"a\\\",\\\"name\\\":\\\"b\\\",\\\"count\\\":1}")]
                """);
        String value = interpret(source);
        assertTrue(value.contains("[path \"$.count\"]"), value);
        assertTrue(value.contains("[path \"$.extra\"]"), value);
        assertTrue(value.contains("duplicate-field"), value);
    }

    @Test void schemaIsDeterministicDraft202012AndClosed() throws Exception {
        Path source = program("""
                (variant Decision [Approve [reason String]] [Reject [reason String]])
                (json-schema Decision)
                """);
        String schema = interpret(source);
        assertTrue(schema.contains("https://json-schema.org/draft/2020-12/schema"), schema);
        assertTrue(schema.contains("\\\"oneOf\\\""), schema);
        assertTrue(schema.contains("\\\"const\\\":\\\"Approve\\\""), schema);
        assertTrue(schema.contains("\\\"additionalProperties\\\":false"), schema);
        assertEquals("String", typecheck(source));
    }

    @Test void optionAnnotationsAcceptSomeAndNone() throws Exception {
        Path source = program("(define keep (fun ([value (Option Any)] [-> (Option Any)]) value)) [(keep (some 1)) (keep none)]");
        assertEquals("[(some 1) none]", interpret(source));
        assertEquals("[(Option Any) (Option Any)]", typecheck(source));
    }

    @Test void optionMatchMustCoverNone() throws Exception {
        Path source = program("(define get (fun ([value (Option Int)]) (match value [(Some x) x]))) get");
        assertError(() -> typecheck(source), "non-exhaustive match for type: None");
    }

    @Test void optionPatternsDoNotMakeAnyExhaustiveWithoutFallback() throws Exception {
        Path source = program("(define get (fun ([value Any]) (match value [(Some x) x] [(None) 0]))) get");
        assertError(() -> typecheck(source), "non-exhaustive match for type: Any");
    }

    @Test void duplicateVariantCasesAreRejected() throws Exception {
        Path source = program("(variant Choice [Yes] [Yes])");
        assertError(() -> typecheck(source), "duplicated variant case: Yes");
    }

    @Test void zeroFieldVariantCasesConstructAndCompareStructurally() throws Exception {
        Path source = program("(variant Choice [Yes] [No]) [(= (Yes) (Yes)) (= (Yes) (No))]");
        assertEquals("[true false]", interpret(source));
        assertEquals("[Bool Bool]", typecheck(source));
    }

    @Test void decodeRejectsMissingRequiredFields() throws Exception {
        assertDecodeError("(record R [name String]) (decode-json R \"{}\")", "missing-field", "$.name");
    }

    @Test void decodeRejectsUnknownFields() throws Exception {
        assertDecodeError("(record R [name String]) (decode-json R \"{\\\"name\\\":\\\"x\\\",\\\"other\\\":1}\")", "unknown-field", "$.other");
    }

    @Test void decodeRejectsIntegerOverflow() throws Exception {
        assertDecodeError("(record R [count Int]) (decode-json R \"{\\\"count\\\":2147483648}\")", "number-overflow", "$.count");
    }

    @Test void decodeRejectsMalformedJson() throws Exception {
        assertDecodeError("(decode-json Int \"[\")", "invalid-json", "$");
    }

    @Test void decodeRejectsUnknownVariantTags() throws Exception {
        assertDecodeError("(variant D [Yes] [No]) (decode-json D \"{\\\"tag\\\":\\\"Maybe\\\"}\")", "unknown-tag", "$.tag");
    }

    @Test void decodeSupportsNestedResultContracts() throws Exception {
        Path source = program("(decode-json (Result Int String) \"{\\\"tag\\\":\\\"Err\\\",\\\"error\\\":\\\"offline\\\"}\")");
        assertEquals("(ok (err \"offline\"))", interpret(source));
        assertEquals("(Result (Result Int String) (record DecodeError [code String] [path String] [message String]))", typecheck(source));
    }

    @Test void decodeSupportsOptionNullAndPayload() throws Exception {
        Path source = program("[(decode-json (Option String) \"null\") (decode-json (Option String) \"\\\"ready\\\"\")]");
        assertEquals("[(ok none) (ok (some \"ready\"))]", interpret(source));
    }

    @Test void encodeReturnsStructuredFailureForUnsupportedValues() throws Exception {
        Path source = program("(encode-json (fun (x) x))");
        String value = interpret(source);
        assertTrue(value.startsWith("(err (record EncodeError"), value);
        assertTrue(value.contains("unsupported-value"), value);
    }

    @Test void decodeRequiresStringInputStaticallyAndAtRuntime() throws Exception {
        Path source = program("(decode-json Int 42)");
        assertError(() -> typecheck(source), "decode-json input must be String");
        assertError(() -> interpret(source), "decode-json input must be String");
    }

    @Test void schemaModelsOptionsResultsAndIntegerBounds() throws Exception {
        Path source = program("(json-schema (Result (Option Int) String))");
        String value = interpret(source);
        assertTrue(value.contains("\\\"anyOf\\\""), value);
        assertTrue(value.contains("\\\"minimum\\\":-2147483648"), value);
        assertTrue(value.contains("\\\"const\\\":\\\"Err\\\""), value);
    }

    @Test void jsonEncodingEscapesControlCharactersAndQuotes() throws Exception {
        Path source = program("(encode-json \"a\\n\\\"b\")");
        assertEquals("(ok \"\\\"a\\\\n\\\\\\\"b\\\"\")", interpret(source));
    }

    @Test void inheritedRecordContractsDecodeFlattenedFields() throws Exception {
        Path source = program("(record Base [id Int]) (record Child (Base) [name String]) (decode-json Child \"{\\\"name\\\":\\\"yin\\\",\\\"id\\\":42}\")");
        assertEquals("(ok (record Child [name \"yin\"] [id 42]))", interpret(source));
    }

    @Test void variantJsonDecodeAndEncodeAreStableRoundTrip() throws Exception {
        Path source = program("(variant D [Yes [reason String]] [No]) (match (decode-json D \"{\\\"tag\\\":\\\"Yes\\\",\\\"reason\\\":\\\"ok\\\"}\") [(Ok value) (encode-json value)] [(Err error) (err error)])");
        assertEquals("(ok \"{\\\"tag\\\":\\\"Yes\\\",\\\"reason\\\":\\\"ok\\\"}\")", interpret(source));
        assertEquals("(U (Result String (record EncodeError [code String] [path String] [message String])) "
                + "(Err (record DecodeError [code String] [path String] [message String])))", typecheck(source));
    }

    @Test void schemaGenerationIsByteForByteDeterministic() throws Exception {
        Path source = program("(record R [name String] [count Int :default 1]) [(= (json-schema R) (json-schema R)) (json-schema R)]");
        String value = interpret(source);
        assertTrue(value.startsWith("[true \""), value);
        assertTrue(value.contains("\\\"required\\\":[\\\"name\\\"]"), value);
    }

    @Test void maintainedStructuredAgentExampleIsRunnableAndTyped() {
        Path source = Path.of("examples/structured-agent.yin");
        RuntimeContext context = new RuntimeContext(ignored -> { },
                () -> "{\"task\":\"review\",\"confidence\":0.95}", java.util.List.of());
        assertEquals("(ok \"{\\\"tag\\\":\\\"Approve\\\",\\\"reason\\\":\\\"high confidence\\\"}\")",
                new Interpreter(source.toString()).interp(source.toString(), context).toString());
        assertEquals("(Result String (record EncodeError [code String] [path String] [message String]))",
                typecheck(source));
    }

    private Path program(String source) throws Exception { return Files.writeString(
            tempDir.resolve("contract-" + System.nanoTime() + ".yin"), source, StandardCharsets.UTF_8); }
    private String interpret(Path source) { return new Interpreter(source.toString()).interp(source.toString()).toString(); }
    private String typecheck(Path source) { YinType type = new TypeChecker(source.toString()).typecheck(source.toString()); return type.toString(); }
    private static void assertError(Action action, String text) { GeneralError error = assertThrows(GeneralError.class, action::run); assertTrue(error.getMessage().contains(text), error.getMessage()); }
    private void assertDecodeError(String source, String code, String path) throws Exception {
        String value = interpret(program(source));
        assertTrue(value.contains("[code \"" + code + "\"]"), value);
        assertTrue(value.contains("[path \"" + path + "\"]"), value);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
