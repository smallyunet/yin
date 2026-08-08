package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplTest {

    @Test
    void parserAcceptsInMemorySourceWithVirtualLocations() throws Exception {
        var program = Parser.parseSource("<memory>", "(+ 1 2)");

        assertEquals("<memory>", program.file);
        assertEquals("(seq (+ 1 2))", program.toString());
    }

    @Test
    void parserReportsErrorsAgainstTheVirtualSource() {
        ParserException error = assertThrows(ParserException.class,
                () -> Parser.parseSource("<memory>", ")"));

        assertEquals("<memory>", error.span.file());
    }

    @Test
    void sessionPersistsDefinitionsAndAllowsInteractiveRedefinition() {
        ReplSession session = new ReplSession();

        session.evaluate("(define value 40)");
        assertEquals("42", session.evaluate("(+ value 2)").value().toString());
        assertEquals("Int", session.evaluate("(+ value 2)").type().toString());
        session.evaluate("(define value 5)");
        assertEquals("5", session.evaluate("value").value().toString());
    }

    @Test
    void redefinitionDoesNotRewriteAnExistingClosuresLexicalEnvironment() {
        ReplSession session = new ReplSession();
        session.evaluate("""
                (define value 1)
                (define captured (fun () value))
                """);

        session.evaluate("(define value 2)");

        assertEquals("1", session.evaluate("(captured)").value().toString());
        assertEquals("2", session.evaluate("value").value().toString());
    }

    @Test
    void failedTypeChecksDoNotExecuteRuntimeSideEffects() {
        ReplSession session = new ReplSession();
        session.evaluate("(define value 0)");

        assertThrows(GeneralError.class,
                () -> session.evaluate("(+ (set! value 1) true)"));

        assertEquals("0", session.evaluate("value").value().toString());
    }

    @Test
    void replSupportsMultilineInputAndRecoversAfterErrors() throws Exception {
        String input = """
                (define add-two
                  (fun (value)
                    (+ value 2)))
                (add-two true)
                (add-two 40)
                :quit
                """;
        StringWriter output = new StringWriter();

        new Repl(new StringReader(input), output, false).run();

        String transcript = output.toString();
        assertTrue(transcript.contains("[YIN0001]"));
        assertTrue(transcript.endsWith("42\n"));
    }

    @Test
    void replReportsIncompleteInputAtEndOfStream() throws Exception {
        StringWriter output = new StringWriter();

        new Repl(new StringReader("(+ 1\n"), output, false).run();

        assertTrue(output.toString().contains("[YIN1001]"));
        assertTrue(output.toString().contains("unclosed delimeter"));
    }
}
