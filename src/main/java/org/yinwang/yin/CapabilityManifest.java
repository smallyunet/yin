package org.yinwang.yin;

import java.io.PrintStream;
import java.util.List;

/** Deterministic preflight description of every source-declared tool authority. */
public final class CapabilityManifest {
    private CapabilityManifest() { }

    public static String inspect(String file) {
        TypeChecker checker = new TypeChecker(file);
        checker.typecheck(file);
        return toJson(checker.tools());
    }

    public static String toJson(List<RuntimeContext.ToolDescriptor> tools) {
        StringBuilder json = new StringBuilder("{\"tools\":[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) json.append(',');
            json.append(tools.get(i).toJson());
        }
        return json.append("]}").toString();
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        if (args.length != 1) {
            error.println("usage: --capabilities <program.yin>");
            return 2;
        }
        try {
            output.println(inspect(args[0]));
            return 0;
        } catch (GeneralError failure) {
            error.println(failure);
            return 1;
        }
    }
}
