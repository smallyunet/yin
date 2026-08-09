package org.yinwang.yin.ast;


import org.yinwang.yin.Scope;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

public class Str extends Node {
    public String source;
    public String value;


    public Str(String value, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.source = value;
        this.value = decode(value);
    }


    public Value interp(Scope<Value> s) {
        return new StringValue(value);
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        return Types.STRING;
    }


    public String toString() {
        return "\"" + source + "\"";
    }

    private static String decode(String source) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current != '\\' || i + 1 >= source.length()) {
                decoded.append(current);
                continue;
            }
            char escaped = source.charAt(++i);
            switch (escaped) {
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case 'u' -> {
                    if (i + 4 >= source.length()) {
                        decoded.append("\\u");
                        break;
                    }
                    String digits = source.substring(i + 1, i + 5);
                    try {
                        decoded.append((char) Integer.parseInt(digits, 16));
                        i += 4;
                    } catch (NumberFormatException error) {
                        decoded.append("\\u");
                    }
                }
                default -> decoded.append('\\').append(escaped);
            }
        }
        return decoded.toString();
    }

}
