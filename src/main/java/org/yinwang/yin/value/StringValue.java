package org.yinwang.yin.value;


public class StringValue extends Value {
    public String value;


    public StringValue(String value) {
        this.value = value;
    }


    public String toString() {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(current);
            }
        }
        return escaped.append('\"').toString();
    }

}
