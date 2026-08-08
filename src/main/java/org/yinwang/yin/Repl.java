package org.yinwang.yin;

import org.yinwang.yin.value.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;

/** Interactive read-evaluate-print loop with multiline input and error recovery. */
public final class Repl {
    private static final String PRIMARY_PROMPT = "yin> ";
    private static final String CONTINUATION_PROMPT = "...> ";

    private final ReplSession session;
    private final BufferedReader input;
    private final PrintWriter output;
    private final boolean showPrompts;

    public Repl(Reader input, Writer output, boolean showPrompts) {
        this.session = new ReplSession();
        this.input = new BufferedReader(input);
        this.output = new PrintWriter(output, true);
        this.showPrompts = showPrompts;
    }

    public void run() throws IOException {
        StringBuilder source = new StringBuilder();
        GeneralError incompleteError = null;
        while (true) {
            prompt(source.isEmpty() ? PRIMARY_PROMPT : CONTINUATION_PROMPT);
            String line = input.readLine();
            if (line == null) {
                if (incompleteError != null) {
                    output.println(incompleteError);
                }
                return;
            }
            if (source.isEmpty() && isExitCommand(line)) {
                return;
            }

            source.append(line).append('\n');
            try {
                ReplSession.Evaluation evaluation = session.evaluate(source.toString());
                if (evaluation.value() != Value.VOID) {
                    output.println(evaluation.value());
                }
                source.setLength(0);
                incompleteError = null;
            } catch (GeneralError error) {
                if (isIncomplete(error)) {
                    incompleteError = error;
                } else {
                    output.println(error);
                    source.setLength(0);
                    incompleteError = null;
                }
            }
        }
    }

    private void prompt(String prompt) {
        if (showPrompts) {
            output.print(prompt);
            output.flush();
        }
    }

    private static boolean isExitCommand(String line) {
        String command = line.trim();
        return command.equals(":quit") || command.equals(":q");
    }

    private static boolean isIncomplete(GeneralError error) {
        return error.diagnostic.code() == Diagnostic.Code.SYNTAX
                && error.diagnostic.message().contains("unclosed delimeter till end of file");
    }
}
