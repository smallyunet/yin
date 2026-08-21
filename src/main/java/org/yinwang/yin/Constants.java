package org.yinwang.yin;


import java.util.Arrays;
import java.util.List;

public class Constants {

    public static final String VERSION = "0.17.0";

    // delimiters and delimeter pairs
    public static final String LINE_COMMENT = "--";

    public static final String PAREN_BEGIN = "(";
    public static final String PAREN_END = ")";

    public static final String CURLY_BEGIN = "{";
    public static final String CURLY_END = "}";

    public static final String SQUARE_BEGIN = "[";
    public static final String SQUARE_END = "]";

    public static final String RETURN_ARROW = "->";

    public static final String STRING_START = "\"";
    public static final String STRING_END = "\"";
    public static final String STRING_ESCAPE = "\\";


    // keywords
    public static final String SEQ_KEYWORD = "seq";
    public static final String FUN_KEYWORD = "fun";
    public static final String IF_KEYWORD = "if";
    public static final String DEF_KEYWORD = "define";
    public static final String ASSIGN_KEYWORD = "set!";
    public static final String RECORD_KEYWORD = "record";
    public static final String VARIANT_KEYWORD = "variant";
    public static final String FIELD_KEYWORD = "field";
    public static final String DECLARE_KEYWORD = "declare";
    public static final String UNION_KEYWORD = "U";
    public static final String VECTOR_TYPE_KEYWORD = "Vector";
    public static final String FUNCTION_TYPE_KEYWORD = "Fn";
    public static final String RESULT_TYPE_KEYWORD = "Result";
    public static final String OPTION_TYPE_KEYWORD = "Option";
    public static final String MATCH_KEYWORD = "match";
    public static final String DECODE_JSON_KEYWORD = "decode-json";
    public static final String ENCODE_JSON_KEYWORD = "encode-json";
    public static final String JSON_SCHEMA_KEYWORD = "json-schema";
    public static final String TOOL_KEYWORD = "tool";
    public static final String INVOKE_KEYWORD = "invoke";
    public static final String POLICY_KEYWORD = "policy";
    public static final String WHEN_KEYWORD = "when";
    public static final String OTHERWISE_KEYWORD = "otherwise";
    public static final String OK_PATTERN = "Ok";
    public static final String ERR_PATTERN = "Err";

    public static List<Character> IDENT_CHARS =
            Arrays.asList('~', '!', '@', '#', '$', '%', '^', '&', '*', '-', '_', '=', '+', '|',
                    ':', ';', ',', '<', '>', '?', '/', '.');


}
