package org.yinwang.yin;

import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic, comment-preserving formatter for supported Yin source. */
public final class Formatter {
    private static final int LINE_WIDTH = 88;
    private static final int INDENT_WIDTH = 2;

    private Formatter() {
    }

    public static String format(String sourceName, String source) {
        validate(sourceName, source);
        List<Element> document = new ConcreteParser(source).parseDocument();
        StringBuilder output = new StringBuilder();
        for (Element element : document) {
            if (element instanceof Comment comment && comment.inline() && !endsInNewline(output)) {
                output.append(' ').append(comment.text());
            } else {
                if (!output.isEmpty() && !endsInNewline(output)) {
                    output.append('\n');
                }
                output.append(render(element, 0));
            }
        }
        if (!output.isEmpty() && !endsInNewline(output)) {
            output.append('\n');
        }
        return output.toString();
    }

    public static int run(String[] args, PrintWriter output, PrintWriter error) {
        Mode mode = Mode.PRINT;
        int firstFile = 0;
        if (args.length > 0 && args[0].equals("--check")) {
            mode = Mode.CHECK;
            firstFile = 1;
        } else if (args.length > 0 && args[0].equals("--write")) {
            mode = Mode.WRITE;
            firstFile = 1;
        }

        List<String> files = Arrays.asList(args).subList(firstFile, args.length);
        if (files.isEmpty() || (mode == Mode.PRINT && files.size() != 1)) {
            error.println("usage: --format [--check | --write] <program.yin>...");
            return 2;
        }

        int status = 0;
        for (String file : files) {
            try {
                Path path = Path.of(file);
                String source = Files.readString(path, StandardCharsets.UTF_8);
                String formatted = format(path.toString(), source);
                if (mode == Mode.PRINT) {
                    output.print(formatted);
                } else if (mode == Mode.CHECK && !source.equals(formatted)) {
                    error.println("would reformat: " + file);
                    status = 1;
                } else if (mode == Mode.WRITE && !source.equals(formatted)) {
                    Files.writeString(path, formatted, StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                error.println("failed to format " + file + ": " + exception.getMessage());
                status = 1;
            } catch (GeneralError exception) {
                error.println(exception);
                status = 1;
            }
        }
        output.flush();
        error.flush();
        return status;
    }

    private static void validate(String sourceName, String source) {
        try {
            Parser.parseSource(sourceName, source);
        } catch (ParserException exception) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX,
                    "parsing error: " + exception.getMessage(),
                    exception.span));
        }
    }

    private static String render(Element element, int indent) {
        if (element instanceof Atom atom) {
            return atom.text();
        }
        if (element instanceof Comment comment) {
            return comment.text();
        }

        Form form = (Form) element;
        String flat = flatten(form);
        if (flat != null && indent + flat.length() <= LINE_WIDTH && !requiresMultiline(form)) {
            return flat;
        }
        if (form.elements().isEmpty()) {
            return Character.toString(form.open()) + form.close();
        }

        int childIndent = indent + INDENT_WIDTH;
        int prefixLength = prefixLength(form);
        StringBuilder output = new StringBuilder();
        output.append(form.open());

        int rendered = 0;
        boolean lineCommentAtEnd = false;
        for (Element child : form.elements()) {
            if (child instanceof Comment comment && comment.inline() && rendered > 0) {
                output.append(' ').append(comment.text());
                lineCommentAtEnd = true;
                continue;
            }

            boolean inPrefix = !lineCommentAtEnd
                    && rendered < prefixLength
                    && flatten(child) != null;
            boolean toolMetadataValue = head(form).equals("tool")
                    && rendered >= 6 && rendered % 2 == 0;
            if (rendered == 0 || inPrefix || toolMetadataValue) {
                if (rendered > 0) {
                    output.append(' ');
                }
            } else {
                output.append('\n').append(" ".repeat(childIndent));
            }
            output.append(render(child, childIndent));
            rendered++;
            lineCommentAtEnd = child instanceof Comment;
        }
        if (lineCommentAtEnd) {
            output.append('\n').append(" ".repeat(indent));
        }
        output.append(form.close());
        return output.toString();
    }

    private static String flatten(Element element) {
        if (element instanceof Atom atom) {
            return atom.text();
        }
        if (element instanceof Comment) {
            return null;
        }
        Form form = (Form) element;
        List<String> children = new ArrayList<>();
        for (Element child : form.elements()) {
            String flat = flatten(child);
            if (flat == null) {
                return null;
            }
            children.add(flat);
        }
        return form.open() + String.join(" ", children) + form.close();
    }

    private static boolean requiresMultiline(Form form) {
        String head = head(form);
        if (head.equals("fun")) {
            return form.elements().size() > 2;
        }
        if ((head.equals("define") || head.equals("set!")) && form.elements().size() > 2) {
            Element value = form.elements().get(2);
            return value instanceof Form valueForm && head(valueForm).equals("fun");
        }
        if (head.equals("otherwise")) {
            return form.elements().size() > 1;
        }
        return (head.equals("seq") || head.equals("match") || head.equals("variant")
                || head.equals("tool") || head.equals("policy") || head.equals("when"))
                && form.elements().size() > 2;
    }

    private static int prefixLength(Form form) {
        String head = head(form);
        if (head.equals("define") || head.equals("set!") || head.equals("fun") || head.equals("if")) {
            return 2;
        }
        if (head.equals("record")) {
            if (form.elements().size() > 2
                    && form.elements().get(2) instanceof Form parents
                    && parents.open() == '(') {
                return 3;
            }
            return 2;
        }
        if (head.equals("variant") || head.equals("match") || head.equals("tool")
                || head.equals("policy")) {
            return head.equals("tool") ? 5 : 2;
        }
        if (head.equals("when")) {
            return 2;
        }
        if (head.equals("otherwise")) {
            return 1;
        }
        return 1;
    }

    private static String head(Form form) {
        if (form.open() == '(' && !form.elements().isEmpty()
                && form.elements().get(0) instanceof Atom atom) {
            return atom.text();
        }
        return "";
    }

    private static boolean endsInNewline(StringBuilder output) {
        return !output.isEmpty() && output.charAt(output.length() - 1) == '\n';
    }

    public static void main(String[] args) {
        int status = run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (status != 0) {
            System.exit(status);
        }
    }

    private enum Mode {
        PRINT,
        CHECK,
        WRITE
    }

    private sealed interface Element permits Atom, Comment, Form {
    }

    private record Atom(String text) implements Element {
    }

    private record Comment(String text, boolean inline) implements Element {
    }

    private record Form(char open, char close, List<Element> elements) implements Element {
    }

    private static final class ConcreteParser {
        private final String source;
        private int offset;
        private boolean lineHasContent;

        private ConcreteParser(String source) {
            this.source = source;
        }

        private List<Element> parseDocument() {
            return parseElements('\0');
        }

        private List<Element> parseElements(char closing) {
            List<Element> elements = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (offset >= source.length() || (closing != '\0' && source.charAt(offset) == closing)) {
                    if (closing != '\0') {
                        offset++;
                        lineHasContent = true;
                    }
                    return elements;
                }

                char current = source.charAt(offset);
                if (source.startsWith("--", offset)) {
                    elements.add(scanComment());
                } else if (current == '(' || current == '[') {
                    offset++;
                    lineHasContent = true;
                    char expected = current == '(' ? ')' : ']';
                    elements.add(new Form(current, expected, parseElements(expected)));
                } else if (current == '"') {
                    elements.add(new Atom(scanString()));
                    lineHasContent = true;
                } else {
                    elements.add(new Atom(scanAtom()));
                    lineHasContent = true;
                }
            }
        }

        private void skipWhitespace() {
            while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
                if (source.charAt(offset) == '\n') {
                    lineHasContent = false;
                }
                offset++;
            }
        }

        private Comment scanComment() {
            boolean inline = lineHasContent;
            int start = offset;
            while (offset < source.length() && source.charAt(offset) != '\n') {
                offset++;
            }
            lineHasContent = true;
            return new Comment(source.substring(start, offset).stripTrailing(), inline);
        }

        private String scanString() {
            int start = offset++;
            while (offset < source.length()) {
                char current = source.charAt(offset++);
                if (current == '\\' && offset < source.length()) {
                    offset++;
                } else if (current == '"') {
                    break;
                }
            }
            return source.substring(start, offset);
        }

        private String scanAtom() {
            int start = offset;
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (Character.isWhitespace(current) || current == '(' || current == ')'
                        || current == '[' || current == ']') {
                    break;
                }
                offset++;
            }
            return source.substring(start, offset);
        }
    }
}
