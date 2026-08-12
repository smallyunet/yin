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
        assertTrue(html.contains("data-example=\"results\""));
        assertTrue(html.contains("data-example=\"contracts\""));
        assertTrue(html.contains("data-example=\"quicksort\""));
        assertTrue(html.contains("data-example=\"structuredAgent\""));
        assertTrue(html.contains("data-example=\"agentReview\""));
        assertTrue(html.contains("data-example=\"typedTool\""));
        assertTrue(html.contains("data-example=\"web3Guard\""));
        assertTrue(html.contains("Guarded tool runtime"));
        assertTrue(html.contains("yin --replay run.jsonl"));
        assertTrue(html.contains("<label for=\"input-editor\">JSON input</label>"));
        assertTrue(html.contains("id=\"input-editor\""));
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
        assertTrue(app.contains("origin.x"));
        assertTrue(app.contains("(policy decide"));
        assertTrue(app.contains("(otherwise"));
        assertTrue(app.contains("(at extended 3)"));
        assertTrue(app.contains("(match (parse-int text)"));
        assertTrue(app.contains("[-> (Result Int String)]"));
        assertTrue(app.contains("[(Err message)"));
        assertTrue(app.contains("(decode-json Request"));
        for (String program : List.of(
                "examples/algorithms/quicksort.yin",
                "examples/agents/structured-agent.yin",
                "examples/agents/agent-review/main.yin",
                "examples/agents/typed-tool.yin",
                "examples/web3/transaction-guard/main.yin")) {
            String source = Files.readString(Path.of(program), StandardCharsets.UTF_8).strip();
            assertTrue(app.contains(source), program + " must stay synchronized with the playground");
        }
        String agentInput = Files.readString(
                Path.of("examples/agents/agent-review/inputs/approve.json"),
                StandardCharsets.UTF_8).strip();
        String web3Input = Files.readString(
                Path.of("examples/web3/transaction-guard/inputs/approve.json"),
                StandardCharsets.UTF_8).strip();
        assertTrue(app.contains(agentInput));
        assertTrue(app.contains(web3Input));
        assertTrue(app.contains("Object.hasOwn(exampleInputs, activeExample)"));
        assertTrue(app.contains("}, 1500)"));
        assertTrue(app.contains("worker.terminate()"));
        assertTrue(worker.contains("yinSetInput(input)"));
    }
}
