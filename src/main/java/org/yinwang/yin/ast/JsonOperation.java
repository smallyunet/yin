package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.json.JsonCodec;
import org.yinwang.yin.json.JsonSupport;
import org.yinwang.yin.type.ResultType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

/** Type-directed strict JSON boundary forms. */
public final class JsonOperation extends Node {
    public enum Kind {
        DECODE("decode-json"), ENCODE("encode-json"), SCHEMA("json-schema");
        private final String sourceName;
        Kind(String sourceName) { this.sourceName = sourceName; }
        public String sourceName() { return sourceName; }
    }

    private final Kind kind;
    private final Node type;
    private final Node value;

    public JsonOperation(Kind kind, Node type, Node value,
                         String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.kind = kind;
        this.type = type;
        this.value = value;
    }

    @Override public Value interp(Scope<Value> scope) {
        if (kind == Kind.SCHEMA) {
            return new StringValue(JsonCodec.schema(type, scope));
        }
        if (kind == Kind.ENCODE) {
            try {
                return new ResultValue(ResultValue.Tag.OK,
                        new StringValue(JsonCodec.encode(value.interp(scope))));
            } catch (JsonCodec.Failure failure) {
                return new ResultValue(ResultValue.Tag.ERR, JsonSupport.error(
                        "EncodeError", failure.code(), failure.path(), failure.getMessage()));
            }
        }
        Value source = value.interp(scope);
        if (!(source instanceof StringValue text)) {
            Util.abort(value, "decode-json input must be String");
        }
        try {
            return new ResultValue(ResultValue.Tag.OK,
                    JsonCodec.decode(type, scope, ((StringValue) source).value));
        } catch (JsonCodec.Failure failure) {
            return new ResultValue(ResultValue.Tag.ERR, JsonSupport.error(
                    "DecodeError", failure.code(), failure.path(), failure.getMessage()));
        }
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        if (kind == Kind.ENCODE) {
            value.typecheck(scope);
            return new ResultType(Types.STRING, JsonSupport.errorValueType("EncodeError"));
        }
        YinType target = type.typecheck(scope);
        if (kind == Kind.SCHEMA) return Types.STRING;
        YinType source = value.typecheck(scope);
        if (!org.yinwang.yin.type.Types.subtype(source, Types.STRING)) {
            Util.abort(value, "decode-json input must be String, got: " + source);
        }
        return new ResultType(target, JsonSupport.errorValueType("DecodeError"));
    }

    @Override public String toString() {
        return switch (kind) {
            case DECODE -> "(decode-json " + type + " " + value + ")";
            case ENCODE -> "(encode-json " + value + ")";
            case SCHEMA -> "(json-schema " + type + ")";
        };
    }
}
