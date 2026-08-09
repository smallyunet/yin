package org.yinwang.yin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoSiteTest {

    @Test
    void demoContainsEveryRequiredStaticAsset() {
        for (String asset : List.of(
                "site/index.html",
                "site/styles.css",
                "site/app.js",
                "site/worker.js",
                "site/favicon.svg",
                "site/.nojekyll")) {
            assertTrue(Files.isRegularFile(Path.of(asset)), asset);
        }
    }

    @Test
    void pageExposesThePlaygroundAndLanguageTour() throws Exception {
        String html = Files.readString(Path.of("site/index.html"), StandardCharsets.UTF_8);

        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("id=\"playground\""));
        assertTrue(html.contains("id=\"code-editor\""));
        assertTrue(html.contains("id=\"features\""));
        assertTrue(html.contains("data-example=\"records\""));
        assertTrue(html.contains("data-example=\"vectors\""));
        assertTrue(html.contains("data-example=\"programs\""));
        assertTrue(html.contains("aria-live=\"polite\""));
        assertFalse(html.contains("href=\"/"), "project-page assets must remain path-relative");
        assertFalse(html.contains("src=\"/"), "project-page assets must remain path-relative");
    }

    @Test
    void demoInterfaceContainsNoChineseCopy() throws Exception {
        for (String asset : List.of("site/index.html", "site/app.js")) {
            String content = Files.readString(Path.of(asset), StandardCharsets.UTF_8);
            assertFalse(content.matches("(?s).*\\p{IsHan}.*"), asset + " must remain English-only");
        }
    }

    @Test
    void workerLoadsTheGeneratedRuntimeAndTheUiEnforcesATimeLimit() throws Exception {
        String worker = Files.readString(Path.of("site/worker.js"), StandardCharsets.UTF_8);
        String app = Files.readString(Path.of("site/app.js"), StandardCharsets.UTF_8);

        assertTrue(worker.contains("importScripts(\"runtime/yin.js\")"));
        assertTrue(worker.contains("yinEvaluate(source)"));
        assertTrue(app.contains("new Worker(\"worker.js\")"));
        assertTrue(app.contains("(field origin :x)"));
        assertTrue(app.contains("(at extended 3)"));
        assertTrue(app.contains("(match (parse-int text)"));
        assertTrue(app.contains("}, 1500)"));
        assertTrue(app.contains("worker.terminate()"));
    }
}
