package org.yinwang.yin.bytecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yinwang.yin.GeneralError;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YinBytecodeTest {
    @TempDir Path tempDir;

    @Test void compilationIsCanonicalAcrossWhitespace() throws Exception {
        String source = Files.readString(program());
        YinBytecode.Artifact first = YinBytecode.compile("main.yin", source);
        YinBytecode.Artifact second = YinBytecode.compile("formatted.yin", "  \n\n" + source);

        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(first.programHash(), second.programHash());
        assertEquals(first.bytecodeHash(), second.bytecodeHash());
        assertFalse(new String(first.bytes(), StandardCharsets.UTF_8).contains(source));
    }

    @Test void verifierRejectsTamperingVersionsAndTrailingBytes() throws Exception {
        byte[] original = compile();
        byte[] tampered = original.clone();
        tampered[tampered.length - 1] ^= 1;
        byte[] version = original.clone();
        version[5] = 2;
        byte[] trailing = java.util.Arrays.copyOf(original, original.length + 1);

        assertThrows(GeneralError.class, () -> YinBytecode.decode(tampered));
        assertTrue(assertThrows(GeneralError.class, () -> YinBytecode.decode(version))
                .getMessage().contains("version"));
        assertTrue(assertThrows(GeneralError.class, () -> YinBytecode.decode(trailing))
                .getMessage().contains("trailing bytes"));
    }

    @Test void portableProfileRejectsUnboundedConstructs() {
        assertRejected("(encode-json ((fun ([value Int] [-> Int]) value) 1))", "fun");
        assertRejected("(encode-json (range 0 10))", "range");
        assertRejected("(encode-json (+ 1 2))", "unsupported portable bytecode operation");
        assertRejected("(record Box [values (Vector Int)]) (encode-json 1)",
                "unsupported portable bytecode operation");
        assertRejected("""
                (policy loop ([value Int] [-> Int]) (otherwise (loop value)))
                (encode-json (loop 1))
                """, "policy calls");
    }

    @Test void compilerCliWritesChecksAndRefusesOverwrite() throws Exception {
        Path output = tempDir.resolve("contract.ybc");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout); PrintStream err = new PrintStream(stderr)) {
            assertEquals(0, YinBytecodeTool.compileCommand(
                    new String[]{program().toString(), "--output", output.toString()}, out, err));
            assertEquals(0, YinBytecodeTool.checkCommand(new String[]{output.toString()}, out, err));
            assertEquals(1, YinBytecodeTool.compileCommand(
                    new String[]{program().toString(), "--output", output.toString()}, out, err));
        }
        assertTrue(Files.size(output) > 0);
        assertTrue(stdout.toString().contains("\"profile\":\"portable-bytecode-v1\""));
        assertTrue(stdout.toString().contains("\"valid\":true"));
        assertTrue(stderr.toString().contains("already exists"));
    }

    private byte[] compile() throws Exception {
        return YinBytecode.compile("main.yin", Files.readString(program())).bytes();
    }

    private void assertRejected(String source, String expected) {
        GeneralError error = assertThrows(GeneralError.class,
                () -> YinBytecode.compile("unsafe.yin", source));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private Path program() {
        return Path.of("examples/agents/capability-decision/main.yin");
    }
}
