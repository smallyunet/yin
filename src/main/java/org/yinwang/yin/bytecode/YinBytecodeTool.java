package org.yinwang.yin.bytecode;

import org.yinwang.yin.DeterministicContractRuntime;
import org.yinwang.yin.Diagnostic;
import org.yinwang.yin.GeneralError;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compiler-side CLI for portable Yin bytecode. Execution belongs to the Rust Yin VM. */
public final class YinBytecodeTool {
    private YinBytecodeTool() { }

    public static int compileCommand(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 3 || !args[1].equals("--output")) {
            error.println("usage: --contract-compile <program.yin> --output <program.ybc>");
            return 2;
        }
        try {
            Path target = Path.of(args[2]);
            if (Files.exists(target)) throw new GeneralError("bytecode output already exists: " + target);
            YinBytecode.Artifact artifact = YinBytecode.compile(
                    args[0], Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
            Files.write(target, artifact.bytes());
            output.println(describe(artifact));
            return 0;
        } catch (GeneralError failure) {
            error.println(failure);
            return 1;
        } catch (Exception failure) {
            error.println(new GeneralError(new Diagnostic(
                    Diagnostic.Code.IO, "failed to compile bytecode: " + args[0], null)));
            return 1;
        }
    }

    public static int checkCommand(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 1) {
            error.println("usage: --bytecode-check <program.ybc>");
            return 2;
        }
        try {
            output.println(describe(YinBytecode.decode(Files.readAllBytes(Path.of(args[0])))));
            return 0;
        } catch (GeneralError failure) {
            error.println(failure);
            return 1;
        } catch (Exception failure) {
            error.println(new GeneralError(new Diagnostic(
                    Diagnostic.Code.IO, "failed to read bytecode file: " + args[0], null)));
            return 1;
        }
    }

    private static String describe(YinBytecode.Artifact artifact) {
        return "{\"bytecodeVersion\":" + YinBytecode.FORMAT_VERSION
                + ",\"contractVersion\":" + DeterministicContractRuntime.CONTRACT_VERSION
                + ",\"profile\":\"portable-bytecode-v1\""
                + ",\"instructionCount\":" + artifact.instructionCount()
                + ",\"programHash\":\"" + artifact.programHash() + "\""
                + ",\"bytecodeHash\":\"" + artifact.bytecodeHash() + "\""
                + ",\"valid\":true}";
    }
}
