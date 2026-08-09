package org.yinwang.yin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yinwang.yin.browser.BrowserBridge;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserBridgeTest {

    @BeforeEach
    void resetSession() {
        BrowserBridge.yinReset();
    }

    @Test
    void evaluatesAndPreservesBrowserSessionState() {
        assertTrue(BrowserBridge.yinEvaluate("(define value 40)").contains("\"ok\":true"));

        String result = BrowserBridge.yinEvaluate("(+ value 2)");

        assertTrue(result.contains("\"value\":\"42\""));
        assertTrue(result.contains("\"type\":\"Int\""));
    }

    @Test
    void capturesPrintOutputAndStructuredDiagnostics() {
        String output = BrowserBridge.yinEvaluate("(print 42 \"ok\")");
        String error = BrowserBridge.yinEvaluate("(+ 1 true)");

        assertTrue(output.contains("\"output\":[\"42, \\\"ok\\\"\"]"));
        assertTrue(error.contains("\"ok\":false"));
        assertTrue(error.contains("\"code\":\"YIN0001\""));
        assertTrue(error.contains("\"line\":1"));
    }

    @Test
    void formatsSourceForTheBrowser() {
        String result = BrowserBridge.yinFormat("(+   1 2)");

        assertTrue(result.contains("\"formatted\":\"(+ 1 2)\\n\""));
    }

    @Test
    void evaluatesRecordFieldAccessInTheBrowserSession() {
        String result = BrowserBridge.yinEvaluate("""
                (record Box [value Int])
                (field (Box :value 42) :value)
                """);

        assertTrue(result.contains("\"value\":\"42\""));
        assertTrue(result.contains("\"type\":\"Int\""));
    }

    @Test
    void evaluatesTypedVectorOperationsInTheBrowserSession() {
        String result = BrowserBridge.yinEvaluate("(at (append [1] [42]) 1)");

        assertTrue(result.contains("\"value\":\"42\""));
        assertTrue(result.contains("\"type\":\"Int\""));
    }

    @Test
    void evaluatesMatchCollectionsAndInjectedInputInTheBrowserSession() {
        BrowserBridge.yinSetInput("  20 22  ");

        String result = BrowserBridge.yinEvaluate("""
                (define values
                  (map (split (trim (read-all)) " ")
                    (fun ([text String] [-> Int])
                      (match (parse-int text)
                        [(Int value) value]
                        [(Bool _) 0]))))
                (fold values 0
                  (fun ([total Int] [value Int] [-> Int]) (+ total value)))
                """);

        assertTrue(result.contains("\"value\":\"42\""));
        assertTrue(result.contains("\"type\":\"Int\""));
    }
}
