package org.yinwang.yin.json;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Call;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.RecordDef;
import org.yinwang.yin.ast.VariantDef;
import org.yinwang.yin.value.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, deterministic JSON codec for source-level Yin contracts. */
public final class JsonCodec {
    private JsonCodec() { }

    public static Value decode(Node type, Scope<Value> scope, String source) {
        Schema schema = resolve(type, scope);
        JValue json;
        try {
            JsonReader reader = new JsonReader(new StringReader(source));
            reader.setStrictness(Strictness.STRICT);
            json = read(reader, "$");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new Failure("trailing-input", "$", "unexpected content after JSON value");
            }
        } catch (Failure failure) {
            throw failure;
        } catch (Exception error) {
            throw new Failure("invalid-json", "$", clean(error.getMessage()));
        }
        return decode(schema, json, "$", scope);
    }

    public static String encode(Value value) {
        StringBuilder out = new StringBuilder();
        write(value, out, "$");
        return out.toString();
    }

    public static String schema(Node type, Scope<Value> scope) {
        StringBuilder out = new StringBuilder();
        out.append("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",");
        writeSchema(resolve(type, scope), out);
        out.append('}');
        return out.toString();
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        private final String path;
        public Failure(String code, String path, String detail) {
            super(detail == null ? "invalid JSON value" : detail);
            this.code = code;
            this.path = path;
        }
        public String code() { return code; }
        public String path() { return path; }
    }

    private sealed interface Schema permits Primitive, VectorSchema, DictSchema, SetSchema,
            OptionSchema, ResultSchema, RecordSchema, VariantSchema, UnionSchema { }
    private record Primitive(String name) implements Schema { }
    private record VectorSchema(Schema element) implements Schema { }
    private record DictSchema(Schema key, Schema value) implements Schema { }
    private record SetSchema(Schema element) implements Schema { }
    private record OptionSchema(Schema value) implements Schema { }
    private record ResultSchema(Schema ok, Schema error) implements Schema { }
    private record Field(Schema schema, Value defaultValue) { }
    private record RecordSchema(String name, Map<String, Field> fields,
                                Set<String> nominalTypes, String identity,
                                String variant) implements Schema { }
    private record VariantSchema(String name, Map<String, RecordSchema> cases) implements Schema { }
    private record UnionSchema(List<Schema> alternatives) implements Schema { }

    private sealed interface JValue permits JObject, JArray, JString, JNumber, JBool, JNull { }
    private record JObject(Map<String, JValue> values) implements JValue { }
    private record JArray(List<JValue> values) implements JValue { }
    private record JString(String value) implements JValue { }
    private record JNumber(String value) implements JValue { }
    private record JBool(boolean value) implements JValue { }
    private enum JNull implements JValue { INSTANCE }

    private static Schema resolve(Node node, Scope<Value> scope) {
        if (node instanceof Name name) {
            return switch (name.id) {
                case "Int", "Float", "Bool", "String", "Any" -> new Primitive(name.id);
                default -> resolveNamed(name.id, scope);
            };
        }
        if (node instanceof Call call && call.op instanceof Name operator) {
            List<Node> args = call.args.positional;
            return switch (operator.id) {
                case "Vector" -> new VectorSchema(resolve(args.get(0), scope));
                case "Dict" -> new DictSchema(resolve(args.get(0), scope), resolve(args.get(1), scope));
                case "Set" -> new SetSchema(resolve(args.get(0), scope));
                case "Option" -> new OptionSchema(resolve(args.get(0), scope));
                case "Result" -> new ResultSchema(resolve(args.get(0), scope), resolve(args.get(1), scope));
                case "U" -> new UnionSchema(args.stream().map(arg -> resolve(arg, scope)).toList());
                default -> throw new Failure("unsupported-type", "$", "unsupported JSON type: " + node);
            };
        }
        throw new Failure("unsupported-type", "$", "unsupported JSON type: " + node);
    }

    private static Schema resolveNamed(String name, Scope<Value> scope) {
        Value value = scope.lookup(name);
        if (value instanceof VariantDescriptor descriptor) {
            Map<String, RecordSchema> cases = new LinkedHashMap<>();
            VariantDef definition = descriptor.definition();
            for (Map.Entry<String, Scope<Object>> entry : definition.cases.entrySet()) {
                RecordConstructor constructor = (RecordConstructor) scope.lookup(entry.getKey());
                cases.put(entry.getKey(), recordSchema(entry.getKey(), entry.getValue(), constructor,
                        definition.name.id, scope));
            }
            return new VariantSchema(name, cases);
        }
        if (value instanceof RecordConstructor constructor) {
            if (constructor.definition instanceof RecordDef definition) {
                return recordSchema(name, recordFields(definition, scope), constructor, null, scope);
            }
            if (constructor.definition instanceof VariantDef definition) {
                return recordSchema(name, definition.cases.get(name), constructor,
                        definition.name.id, scope);
            }
            if (name.equals("DecodeError") || name.equals("EncodeError") || name.equals("ToolError")) {
                Map<String, Field> fields = new LinkedHashMap<>();
                fields.put("code", new Field(new Primitive("String"), null));
                fields.put("path", new Field(new Primitive("String"), null));
                fields.put("message", new Field(new Primitive("String"), null));
                return new RecordSchema(name, fields, Set.of(name), name, null);
            }
        }
        throw new Failure("unknown-type", "$", "unknown JSON contract type: " + name);
    }

    private static Scope<Object> recordFields(RecordDef definition, Scope<Value> scope) {
        Scope<Object> fields = new Scope<>();
        fields.putAll(definition.propertyForm);
        if (definition.parents != null) {
            for (Name parent : definition.parents) {
                Value value = scope.lookup(parent.id);
                if (value instanceof RecordConstructor constructor
                        && constructor.definition instanceof RecordDef parentDefinition) {
                    fields.putAll(recordFields(parentDefinition, scope));
                }
            }
        }
        return fields;
    }

    private static RecordSchema recordSchema(String name, Scope<Object> forms,
                                             RecordConstructor constructor, String variant,
                                             Scope<Value> scope) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (String field : forms.keySet()) {
            Node type = (Node) forms.lookupPropertyLocal(field, "type");
            Object fallback = constructor.properties.lookupPropertyLocal(field, "default");
            fields.put(field, new Field(resolve(type, scope), fallback instanceof Value v ? v : null));
        }
        return new RecordSchema(name, fields, constructor.nominalTypes(),
                constructor.identity(), constructor.variantName());
    }

    private static JValue read(JsonReader reader, String path) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Map<String, JValue> values = new LinkedHashMap<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (values.containsKey(name)) {
                        throw new Failure("duplicate-field", child(path, name),
                                "duplicate object field: " + name);
                    }
                    values.put(name, read(reader, child(path, name)));
                }
                reader.endObject();
                yield new JObject(values);
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                List<JValue> values = new ArrayList<>();
                while (reader.hasNext()) values.add(read(reader, path + "[" + values.size() + "]"));
                reader.endArray();
                yield new JArray(values);
            }
            case STRING -> new JString(reader.nextString());
            case NUMBER -> new JNumber(reader.nextString());
            case BOOLEAN -> new JBool(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JNull.INSTANCE; }
            default -> throw new Failure("invalid-json", path, "expected a JSON value");
        };
    }

    private static Value decode(Schema schema, JValue json, String path, Scope<Value> scope) {
        if (schema instanceof Primitive primitive) return decodePrimitive(primitive.name, json, path);
        if (schema instanceof VectorSchema vector) {
            if (!(json instanceof JArray array)) throw type(path, "array", json);
            List<Value> values = new ArrayList<>();
            for (int i = 0; i < array.values.size(); i++) {
                values.add(decode(vector.element, array.values.get(i), path + "[" + i + "]", scope));
            }
            return new Vector(values);
        }
        if (schema instanceof DictSchema dictionary) {
            JObject object = object(json, path);
            List<DictValue.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, JValue> entry : object.values.entrySet()) {
                String entryPath = child(path, entry.getKey());
                Value key = decode(dictionary.key, new JString(entry.getKey()), entryPath, scope);
                Value value = decode(dictionary.value, entry.getValue(), entryPath, scope);
                entries.add(new DictValue.Entry(key, value));
            }
            return new DictValue(entries);
        }
        if (schema instanceof SetSchema set) {
            if (!(json instanceof JArray array)) throw type(path, "array", json);
            List<Value> values = new ArrayList<>();
            for (int i = 0; i < array.values.size(); i++) {
                values.add(decode(set.element, array.values.get(i), path + "[" + i + "]", scope));
            }
            return new SetValue(values);
        }
        if (schema instanceof OptionSchema option) {
            return json == JNull.INSTANCE ? OptionValue.none()
                    : OptionValue.some(decode(option.value, json, path, scope));
        }
        if (schema instanceof ResultSchema result) {
            JObject object = object(json, path);
            String tag = string(required(object, "tag", path), child(path, "tag"));
            if (tag.equals("Ok")) {
                exactFields(object, Set.of("tag", "value"), path);
                return new ResultValue(ResultValue.Tag.OK,
                        decode(result.ok, required(object, "value", path), child(path, "value"), scope));
            }
            if (tag.equals("Err")) {
                exactFields(object, Set.of("tag", "error"), path);
                return new ResultValue(ResultValue.Tag.ERR,
                        decode(result.error, required(object, "error", path), child(path, "error"), scope));
            }
            throw new Failure("unknown-tag", child(path, "tag"), "expected Ok or Err, got: " + tag);
        }
        if (schema instanceof VariantSchema variant) {
            JObject object = object(json, path);
            String tag = string(required(object, "tag", path), child(path, "tag"));
            RecordSchema selected = variant.cases.get(tag);
            if (selected == null) throw new Failure("unknown-tag", child(path, "tag"),
                    "unknown " + variant.name + " tag: " + tag);
            return decodeRecord(selected, object, path, scope, true);
        }
        if (schema instanceof RecordSchema record) return decodeRecord(record, object(json, path), path, scope, false);
        UnionSchema union = (UnionSchema) schema;
        List<Failure> failures = new ArrayList<>();
        for (Schema alternative : union.alternatives) {
            try { return decode(alternative, json, path, scope); }
            catch (Failure failure) { failures.add(failure); }
        }
        throw new Failure("no-union-match", path, "value does not match any union member");
    }

    private static Value decodePrimitive(String type, JValue json, String path) {
        try {
            return switch (type) {
                case "Int" -> decodeInt(json, path);
                case "Float" -> decodeFloat(json, path);
                case "Bool" -> decodeBool(json, path);
                case "String" -> decodeString(json, path);
                case "Any" -> decodeAny(json, path);
                default -> throw new Failure("unsupported-type", path, "unsupported primitive: " + type);
            };
        } catch (ArithmeticException | NumberFormatException error) {
            throw new Failure("number-overflow", path, "number is outside the supported " + type + " range");
        }
    }

    private static Value decodeInt(JValue json, String path) {
        if (!(json instanceof JNumber number) || !integer(number.value)) throw type(path, "32-bit integer", json);
        return new IntValue(Math.toIntExact(Long.parseLong(number.value)));
    }
    private static Value decodeFloat(JValue json, String path) {
        if (!(json instanceof JNumber number)) throw type(path, "finite number", json);
        double value = Double.parseDouble(number.value);
        if (!Double.isFinite(value)) throw type(path, "finite number", json);
        return new FloatValue(value);
    }
    private static Value decodeBool(JValue json, String path) {
        if (!(json instanceof JBool bool)) throw type(path, "boolean", json);
        return new BoolValue(bool.value);
    }
    private static Value decodeString(JValue json, String path) {
        if (!(json instanceof JString text)) throw type(path, "string", json);
        return new StringValue(text.value);
    }

    private static Value decodeAny(JValue json, String path) {
        if (json instanceof JString text) return new StringValue(text.value);
        if (json instanceof JBool bool) return new BoolValue(bool.value);
        if (json instanceof JNumber number) {
            if (integer(number.value)) {
                try { return new IntValue(Math.toIntExact(Long.parseLong(number.value))); }
                catch (RuntimeException ignored) { }
            }
            double value = Double.parseDouble(number.value);
            if (Double.isFinite(value)) return new FloatValue(value);
            throw new Failure("number-overflow", path, "number is not finite");
        }
        if (json instanceof JArray array) {
            List<Value> values = new ArrayList<>();
            for (int i = 0; i < array.values.size(); i++) values.add(decodeAny(array.values.get(i), path + "[" + i + "]"));
            return new Vector(values);
        }
        if (json == JNull.INSTANCE) return OptionValue.none();
        JObject object = (JObject) json;
        Scope<Value> fields = new Scope<>();
        object.values.forEach((name, value) -> fields.putValue(name, decodeAny(value, child(path, name))));
        return new RecordValue(null, fields);
    }

    private static RecordValue decodeRecord(RecordSchema record, JObject object, String path,
                                            Scope<Value> scope, boolean tagged) {
        Set<String> allowed = new LinkedHashSet<>(record.fields.keySet());
        if (tagged) allowed.add("tag");
        exactFields(object, allowed, path);
        Scope<Value> values = new Scope<>();
        for (Map.Entry<String, Field> entry : record.fields.entrySet()) {
            String name = entry.getKey();
            JValue json = object.values.get(name);
            if (json == null) {
                if (entry.getValue().defaultValue != null) values.putValue(name, entry.getValue().defaultValue);
                else throw new Failure("missing-field", child(path, name), "missing required field: " + name);
            } else values.putValue(name, decode(entry.getValue().schema, json, child(path, name), scope));
        }
        return new RecordValue(record.name, values, record.nominalTypes,
                record.variant, record.identity);
    }

    private static void write(Value value, StringBuilder out, String path) {
        if (value instanceof StringValue text) { quote(text.value, out); return; }
        if (value instanceof IntValue number) { out.append(number.value); return; }
        if (value instanceof FloatValue number) {
            if (!Double.isFinite(number.value)) throw new Failure("non-finite-number", path, "JSON cannot encode non-finite numbers");
            out.append(Double.toString(number.value)); return;
        }
        if (value instanceof BoolValue bool) { out.append(bool.value); return; }
        if (value instanceof OptionValue option) {
            if (!option.present()) out.append("null"); else write(option.value(), out, path);
            return;
        }
        if (value instanceof Vector vector) {
            out.append('[');
            for (int i = 0; i < vector.size(); i++) { if (i > 0) out.append(','); write(vector.get(i), out, path + "[" + i + "]"); }
            out.append(']'); return;
        }
        if (value instanceof DictValue dictionary) {
            out.append('{');
            boolean first = true;
            for (DictValue.Entry entry : dictionary.entries()) {
                if (!(entry.key() instanceof StringValue key)) {
                    throw new Failure("non-string-key", path,
                            "JSON objects require String dictionary keys, got: " + entry.key());
                }
                if (!first) out.append(',');
                first = false;
                quote(key.value, out);
                out.append(':');
                write(entry.value(), out, child(path, key.value));
            }
            out.append('}'); return;
        }
        if (value instanceof SetValue set) {
            out.append('[');
            for (int i = 0; i < set.size(); i++) {
                if (i > 0) out.append(',');
                write(set.values().get(i), out, path + "[" + i + "]");
            }
            out.append(']'); return;
        }
        if (value instanceof ResultValue result) {
            out.append("{\"tag\":"); quote(result.tag() == ResultValue.Tag.OK ? "Ok" : "Err", out);
            String key = result.tag() == ResultValue.Tag.OK ? "value" : "error";
            out.append(','); quote(key, out); out.append(':'); write(result.payload(), out, child(path, key)); out.append('}'); return;
        }
        if (value instanceof RecordValue record) {
            out.append('{'); boolean first = true;
            if (record.variantName() != null) { out.append("\"tag\":"); quote(record.name, out); first = false; }
            for (String field : record.properties.keySet()) {
                if (!first) out.append(','); first = false; quote(field, out); out.append(':');
                write(record.properties.lookupLocal(field), out, child(path, field));
            }
            out.append('}'); return;
        }
        throw new Failure("unsupported-value", path, "value cannot be encoded as JSON: " + value);
    }

    private static void writeSchema(Schema schema, StringBuilder out) {
        if (schema instanceof Primitive primitive) {
            if (primitive.name.equals("Any")) { out.append("\"x-yin-type\":\"Any\""); return; }
            out.append("\"type\":"); quote(switch (primitive.name) {
                case "Int" -> "integer"; case "Float" -> "number"; case "Bool" -> "boolean";
                case "String" -> "string"; default -> "object";
            }, out);
            if (primitive.name.equals("Int")) out.append(",\"minimum\":-2147483648,\"maximum\":2147483647");
            return;
        }
        if (schema instanceof VectorSchema vector) { out.append("\"type\":\"array\",\"items\":{"); writeSchema(vector.element, out); out.append('}'); return; }
        if (schema instanceof DictSchema dictionary) {
            if (!acceptsObjectKey(dictionary.key)) {
                throw new Failure("non-string-key", "$",
                        "JSON object schemas require Dict String keys");
            }
            out.append("\"type\":\"object\",\"additionalProperties\":{");
            writeSchema(dictionary.value, out);
            out.append('}'); return;
        }
        if (schema instanceof SetSchema set) {
            out.append("\"type\":\"array\",\"uniqueItems\":true,\"items\":{");
            writeSchema(set.element, out);
            out.append('}'); return;
        }
        if (schema instanceof OptionSchema option) { out.append("\"anyOf\":[{\"type\":\"null\"},{"); writeSchema(option.value, out); out.append("}]"); return; }
        if (schema instanceof UnionSchema union) { writeAlternatives("anyOf", union.alternatives, out); return; }
        if (schema instanceof VariantSchema variant) { writeAlternatives("oneOf", new ArrayList<>(variant.cases.values()), out); return; }
        if (schema instanceof ResultSchema result) {
            List<Schema> cases = List.of(taggedPayload("Ok", "value", result.ok), taggedPayload("Err", "error", result.error));
            writeAlternatives("oneOf", cases, out); return;
        }
        writeRecordSchema((RecordSchema) schema, out);
    }

    private static boolean acceptsObjectKey(Schema schema) {
        if (schema instanceof Primitive primitive) {
            return primitive.name.equals("String") || primitive.name.equals("Any");
        }
        return schema instanceof UnionSchema union
                && union.alternatives.stream().anyMatch(JsonCodec::acceptsObjectKey);
    }

    private static RecordSchema taggedPayload(String tag, String field, Schema payload) {
        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put(field, new Field(payload, null));
        return new RecordSchema(tag, fields, Set.of(tag), tag, "$tagged");
    }

    private static void writeAlternatives(String keyword, List<? extends Schema> alternatives, StringBuilder out) {
        quote(keyword, out); out.append(":"); out.append('[');
        for (int i = 0; i < alternatives.size(); i++) { if (i > 0) out.append(','); out.append('{'); writeSchema(alternatives.get(i), out); out.append('}'); }
        out.append(']');
    }

    private static void writeRecordSchema(RecordSchema record, StringBuilder out) {
        out.append("\"type\":\"object\",\"properties\":{"); boolean first = true;
        if (record.variant != null) { out.append("\"tag\":{\"const\":"); quote(record.name, out); out.append('}'); first = false; }
        for (Map.Entry<String, Field> entry : record.fields.entrySet()) {
            if (!first) out.append(','); first = false; quote(entry.getKey(), out); out.append(":{"); writeSchema(entry.getValue().schema, out); out.append('}');
        }
        out.append("},\"required\":["); first = true;
        if (record.variant != null) { quote("tag", out); first = false; }
        for (Map.Entry<String, Field> entry : record.fields.entrySet()) if (entry.getValue().defaultValue == null) {
            if (!first) out.append(','); first = false; quote(entry.getKey(), out);
        }
        out.append("],\"additionalProperties\":false");
    }

    private static JObject object(JValue value, String path) { if (value instanceof JObject object) return object; throw type(path, "object", value); }
    private static JValue required(JObject object, String field, String path) { JValue value = object.values.get(field); if (value == null) throw new Failure("missing-field", child(path, field), "missing required field: " + field); return value; }
    private static String string(JValue value, String path) { if (value instanceof JString text) return text.value; throw type(path, "string", value); }
    private static void exactFields(JObject object, Set<String> allowed, String path) { for (String field : object.values.keySet()) if (!allowed.contains(field)) throw new Failure("unknown-field", child(path, field), "unknown field: " + field); }
    private static Failure type(String path, String expected, JValue actual) { return new Failure("wrong-type", path, "expected " + expected + ", got " + kind(actual)); }
    private static String kind(JValue value) { if (value instanceof JObject) return "object"; if (value instanceof JArray) return "array"; if (value instanceof JString) return "string"; if (value instanceof JNumber) return "number"; if (value instanceof JBool) return "boolean"; return "null"; }
    private static boolean integer(String value) { return value.matches("-?(0|[1-9][0-9]*)"); }
    private static String child(String path, String field) { return path + "." + field; }
    private static String clean(String message) { if (message == null) return "invalid JSON"; int line = message.indexOf(" at line "); return line < 0 ? message : message.substring(0, line); }
    private static void quote(String value, StringBuilder out) { out.append('"'); for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); switch (c) { case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t"); default -> { if (c < 0x20) { String hex = Integer.toHexString(c); out.append("\\u").append("0".repeat(4 - hex.length())).append(hex); } else out.append(c); } } } out.append('"'); }
}
