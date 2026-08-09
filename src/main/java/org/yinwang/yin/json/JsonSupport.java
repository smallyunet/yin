package org.yinwang.yin.json;

import org.yinwang.yin.Scope;
import org.yinwang.yin.type.RecordType;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.RecordConstructor;
import org.yinwang.yin.value.RecordValue;
import org.yinwang.yin.value.StringValue;
import org.yinwang.yin.value.Value;

import java.util.Set;

/** Shared structured error contracts for JSON boundaries. */
public final class JsonSupport {
    private JsonSupport() { }

    public static void installRuntime(Scope<Value> scope) {
        scope.putValue("DecodeError", errorConstructor("DecodeError"));
        scope.putValue("EncodeError", errorConstructor("EncodeError"));
    }

    public static void installTypes(Scope<YinType> scope) {
        scope.putValue("DecodeError", errorType("DecodeError"));
        scope.putValue("EncodeError", errorType("EncodeError"));
    }

    public static RecordType errorType(String name) {
        Scope<YinType> fields = new Scope<>();
        fields.putType("code", org.yinwang.yin.type.Types.STRING);
        fields.putType("path", org.yinwang.yin.type.Types.STRING);
        fields.putType("message", org.yinwang.yin.type.Types.STRING);
        return new RecordType(name, null, fields, Set.of(name));
    }

    public static org.yinwang.yin.type.RecordValueType errorValueType(String name) {
        Scope<YinType> fields = new Scope<>();
        fields.putValue("code", org.yinwang.yin.type.Types.STRING);
        fields.putValue("path", org.yinwang.yin.type.Types.STRING);
        fields.putValue("message", org.yinwang.yin.type.Types.STRING);
        return new org.yinwang.yin.type.RecordValueType(name, fields, Set.of(name));
    }

    public static RecordValue error(String name, String code, String path, String message) {
        Scope<Value> fields = new Scope<>();
        fields.putValue("code", new StringValue(code));
        fields.putValue("path", new StringValue(path));
        fields.putValue("message", new StringValue(message));
        return new RecordValue(name, fields, Set.of(name));
    }

    private static RecordConstructor errorConstructor(String name) {
        Scope<Value> fields = new Scope<>();
        fields.putProperties("code", java.util.Map.of());
        fields.putProperties("path", java.util.Map.of());
        fields.putProperties("message", java.util.Map.of());
        return new RecordConstructor(name, null, fields, Set.of(name));
    }
}
