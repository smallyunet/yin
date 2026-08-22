package org.yinwang.yin;

import org.junit.jupiter.api.Test;
import org.yinwang.yin.type.YinType;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigValidatorDemoTest {
    private static final Path PROGRAM = Path.of("examples/config-validator/main.yin");

    @Test void validConfigIsNormalizedByTheMultiFileProgram() {
        Result result = run("{\"host\":\"localhost\",\"port\":\"8080\"}");
        assertEquals(0, result.status);
        assertEquals("{\"tag\":\"Valid\",\"config\":{\"host\":\"localhost\","
                + "\"port\":\"8080\",\"mode\":\"development\"}}\n", result.output);
        assertEquals("", result.error);
    }

    @Test void incompleteConfigReturnsAClosedTypedDiagnostic() {
        Result result = run("{\"host\":\"localhost\"}");
        assertEquals(0, result.status);
        assertEquals("{\"tag\":\"Invalid\",\"missing\":[\"port\"],\"message\":null}\n",
                result.output);
    }

    @Test void malformedInputBecomesProgramData() {
        Result result = run("{");
        assertEquals(0, result.status);
        assertEquals("{\"tag\":\"Invalid\",\"missing\":[],"
                + "\"message\":\"End of input\"}\n",
                result.output);
    }

    @Test void exampleHasOneCrossModuleStaticType() {
        YinType type = new TypeChecker(PROGRAM.toString()).typecheck(PROGRAM.toString());
        assertEquals("(Result String (record EncodeError [code String] [path String] [message String]))",
                type.toString());
    }

    private Result run(String input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = Interpreter.runJson(new String[]{PROGRAM.toString()},
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                () -> input);
        return new Result(status, output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int status, String output, String error) { }
}
