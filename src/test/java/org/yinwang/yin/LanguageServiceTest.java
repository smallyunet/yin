package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.yinwang.yin.lsp.LanguageService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageServiceTest {
    private final LanguageService service = new LanguageService();

    @Test
    void diagnosesUnsavedSyntaxAndTypeErrors() {
        List<Diagnostic> syntax = service.diagnose("file:///syntax.yin", "(+ 1");
        List<Diagnostic> type = service.diagnose("file:///type.yin", "(+ 1 true)");

        assertEquals(1, syntax.size());
        assertEquals(Diagnostic.Code.SYNTAX, syntax.get(0).code());
        assertTrue(syntax.get(0).sourceSpan().isPresent());
        assertEquals(1, type.size());
        assertEquals(Diagnostic.Code.LANGUAGE, type.get(0).code());
        assertTrue(type.get(0).message().contains("incorrect argument types for +"));
    }

    @Test
    void validReplacementSourceClearsDiagnostics() {
        assertTrue(service.diagnose("file:///valid.yin", "(+ 1 2)").isEmpty());
    }

    @Test
    void formatsUnsavedSourceWithTheCanonicalFormatter() {
        assertEquals("(+ 1 2)\n", service.format("file:///format.yin", "(+   1 2)"));
    }
}
