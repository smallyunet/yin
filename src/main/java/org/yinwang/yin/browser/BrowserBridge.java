package org.yinwang.yin.browser;

import org.teavm.jso.JSExport;
import org.yinwang.yin.Diagnostic;
import org.yinwang.yin.Formatter;
import org.yinwang.yin.GeneralError;
import org.yinwang.yin.ReplSession;
import org.yinwang.yin.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/** JavaScript exports used by the static Yin playground. */
public final class BrowserBridge {
    private static final List<String> OUTPUT = new ArrayList<>();
    private static ReplSession session = newSession();

    private BrowserBridge() {
    }

    public static void main(String[] args) {
        // TeaVM entry point. Browser calls the exported methods below.
    }

    @JSExport
    public static String yinEvaluate(String source) {
        OUTPUT.clear();
        try {
            ReplSession.Evaluation evaluation = session.evaluate(source);
            return success(evaluation.value().toString(), evaluation.type().toString(), OUTPUT);
        } catch (GeneralError error) {
            return failure(error.diagnostic, OUTPUT);
        } catch (RuntimeException error) {
            Diagnostic diagnostic = new Diagnostic(
                    Diagnostic.Code.LANGUAGE,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                    null);
            return failure(diagnostic, OUTPUT);
        }
    }

    @JSExport
    public static String yinFormat(String source) {
        try {
            return "{\"ok\":true,\"formatted\":" + quote(Formatter.format("<playground>", source)) + "}";
        } catch (GeneralError error) {
            return failure(error.diagnostic, List.of());
        }
    }

    @JSExport
    public static void yinReset() {
        OUTPUT.clear();
        session = newSession();
    }

    private static ReplSession newSession() {
        return new ReplSession(OUTPUT::add);
    }

    private static String success(String value, String type, List<String> output) {
        return "{\"ok\":true,\"value\":" + quote(value)
                + ",\"type\":" + quote(type)
                + ",\"output\":" + stringArray(output) + "}";
    }

    private static String failure(Diagnostic diagnostic, List<String> output) {
        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":false,\"output\":").append(stringArray(output));
        json.append(",\"diagnostic\":{");
        json.append("\"code\":").append(quote(diagnostic.code().id()));
        json.append(",\"message\":").append(quote(diagnostic.message()));
        SourceSpan span = diagnostic.span();
        if (span != null) {
            json.append(",\"line\":").append(span.line() + 1);
            json.append(",\"column\":").append(span.column() + 1);
            json.append(",\"start\":").append(span.start());
            json.append(",\"end\":").append(span.end());
        }
        json.append("}}");
        return json.toString();
    }

    private static String stringArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(values.get(i)));
        }
        return json.append(']').toString();
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (current < 0x20) {
                        json.append("\\u00");
                        String hex = Integer.toHexString(current);
                        if (hex.length() == 1) {
                            json.append('0');
                        }
                        json.append(hex);
                    } else {
                        json.append(current);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
