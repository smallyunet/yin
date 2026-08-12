package org.yinwang.yin.bytecode;

import org.yinwang.yin.DeterministicContractRuntime;
import org.yinwang.yin.Diagnostic;
import org.yinwang.yin.GeneralError;
import org.yinwang.yin.ast.Delimeter;
import org.yinwang.yin.ast.IntNum;
import org.yinwang.yin.ast.Keyword;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.Str;
import org.yinwang.yin.parser.Lexer;
import org.yinwang.yin.parser.ParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical token bytecode format and verifier for deterministic Yin contracts. */
public final class YinBytecode {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_BYTECODE_BYTES = 1_048_576;
    public static final int MAX_TOKENS = 100_000;

    private static final byte[] MAGIC = {'Y', 'B', 'C', 1};
    private static final int OPEN_PAREN = 1;
    private static final int CLOSE_PAREN = 2;
    private static final int OPEN_VECTOR = 3;
    private static final int CLOSE_VECTOR = 4;
    private static final int NAME = 5;
    private static final int KEYWORD = 6;
    private static final int INTEGER = 7;
    private static final int STRING = 8;
    private static final Set<Integer> PAYLOAD_OPS = Set.of(NAME, KEYWORD, INTEGER, STRING);
    private static final Set<String> PORTABLE_OPERATIONS = Set.of(
            "record", "variant", "policy", "when", "otherwise", "match",
            "decode-json", "read-all", "encode-json", "not", ">", "concat", "Ok", "Err");

    private YinBytecode() { }

    public static Artifact compile(String sourceName, String source) {
        List<Token> tokens = tokenize(sourceName, source);
        validateTermination(tokens);
        validateOperations(tokens);
        validateTypes(tokens);
        String normalized = render(tokens);
        DeterministicContractRuntime.checkSource(sourceName, normalized);
        byte[] programHash = digest(normalized.getBytes(StandardCharsets.UTF_8));
        return decode(encode(tokens, programHash));
    }

    public static Artifact decode(byte[] bytes) {
        if (bytes.length > MAX_BYTECODE_BYTES) failure("bytecode exceeds maximum size");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) failure("invalid bytecode magic");
            int formatVersion = input.readUnsignedShort();
            if (formatVersion != FORMAT_VERSION) failure("unsupported bytecode version: " + formatVersion);
            int contractVersion = input.readUnsignedShort();
            if (contractVersion != DeterministicContractRuntime.CONTRACT_VERSION) {
                failure("unsupported contract profile version: " + contractVersion);
            }
            int count = input.readInt();
            if (count <= 0 || count > MAX_TOKENS) failure("invalid bytecode token count: " + count);
            byte[] expectedHash = input.readNBytes(32);
            if (expectedHash.length != 32) failure("truncated bytecode program hash");

            List<Token> tokens = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int opcode = input.readUnsignedByte();
                if (opcode < OPEN_PAREN || opcode > STRING) failure("unknown bytecode opcode: " + opcode);
                String payload = "";
                if (PAYLOAD_OPS.contains(opcode)) {
                    int length = input.readInt();
                    if (length < 0 || length > MAX_BYTECODE_BYTES) failure("invalid bytecode operand length");
                    byte[] operand = input.readNBytes(length);
                    if (operand.length != length) failure("truncated bytecode operand");
                    payload = new String(operand, StandardCharsets.UTF_8);
                }
                tokens.add(new Token(opcode, payload));
            }
            if (input.read() != -1) failure("trailing bytes after bytecode program");

            validateTermination(tokens);
            validateOperations(tokens);
            validateTypes(tokens);
            String normalized = render(tokens);
            if (!MessageDigest.isEqual(expectedHash, digest(normalized.getBytes(StandardCharsets.UTF_8)))) {
                failure("bytecode program hash mismatch");
            }
            DeterministicContractRuntime.checkSource("<bytecode>", normalized);
            byte[] canonical = encode(tokens, expectedHash);
            if (!Arrays.equals(canonical, bytes)) failure("non-canonical bytecode encoding");
            return new Artifact(bytes.clone(), normalized, count,
                    hex(expectedHash), hex(digest(bytes)));
        } catch (EOFException error) {
            throw new GeneralError("truncated bytecode");
        } catch (GeneralError error) {
            throw error;
        } catch (Exception error) {
            throw new GeneralError("invalid bytecode: " + clean(error.getMessage()));
        }
    }

    private static List<Token> tokenize(String sourceName, String source) {
        try {
            Lexer lexer = Lexer.fromSource(sourceName, source);
            List<Token> tokens = new ArrayList<>();
            Node node;
            while ((node = lexer.nextToken()) != null) {
                if (node instanceof Delimeter delimiter) {
                    tokens.add(new Token(switch (delimiter.shape) {
                        case "(" -> OPEN_PAREN;
                        case ")" -> CLOSE_PAREN;
                        case "[" -> OPEN_VECTOR;
                        case "]" -> CLOSE_VECTOR;
                        default -> throw new GeneralError(node, "unsupported bytecode delimiter");
                    }, ""));
                } else if (node instanceof Name name) {
                    tokens.add(new Token(NAME, name.id));
                } else if (node instanceof Keyword keyword) {
                    tokens.add(new Token(KEYWORD, keyword.id));
                } else if (node instanceof IntNum integer) {
                    tokens.add(new Token(INTEGER, Integer.toString(integer.value)));
                } else if (node instanceof Str string) {
                    tokens.add(new Token(STRING, string.source));
                } else {
                    throw new GeneralError(node, "unsupported bytecode token: " + node);
                }
            }
            if (tokens.isEmpty()) failure("cannot compile an empty program");
            return tokens;
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "lexing error: " + error.getMessage(), error.span));
        }
    }

    private static void validateTermination(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.opcode == NAME && token.payload.equals("fun")) {
                failure("explicit fun is outside portable-bytecode-v1");
            }
            if (token.opcode == NAME && token.payload.equals("range")) {
                failure("range is outside portable-bytecode-v1");
            }
        }

        Set<String> policies = new LinkedHashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).opcode == OPEN_PAREN && name(tokens.get(i + 1), "policy")
                    && tokens.get(i + 2).opcode == NAME) {
                policies.add(tokens.get(i + 2).payload);
            }
        }
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!(tokens.get(i).opcode == OPEN_PAREN && name(tokens.get(i + 1), "policy"))) continue;
            int end = matchingClose(tokens, i);
            for (int j = i + 3; j < end; j++) {
                Token token = tokens.get(j);
                if (token.opcode == NAME && policies.contains(token.payload)) {
                    failure("policy calls are outside portable-bytecode-v1: " + token.payload);
                }
            }
            i = end;
        }
    }

    private static void validateOperations(List<Token> tokens) {
        Set<String> callable = new LinkedHashSet<>(PORTABLE_OPERATIONS);
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).opcode != OPEN_PAREN || tokens.get(i + 2).opcode != NAME) continue;
            if (name(tokens.get(i + 1), "record") || name(tokens.get(i + 1), "policy")) {
                callable.add(tokens.get(i + 2).payload);
            }
        }
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!(tokens.get(i).opcode == OPEN_PAREN && name(tokens.get(i + 1), "variant"))) continue;
            int end = matchingClose(tokens, i);
            for (int j = i + 2; j + 1 < end; j++) {
                if (tokens.get(j).opcode == OPEN_VECTOR && tokens.get(j + 1).opcode == NAME) {
                    callable.add(tokens.get(j + 1).payload);
                }
            }
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).opcode != OPEN_PAREN || tokens.get(i + 1).opcode != NAME) continue;
            String operation = tokens.get(i + 1).payload;
            if (!callable.contains(operation)) {
                failure("unsupported portable bytecode operation: " + operation);
            }
        }
    }

    private static void validateTypes(List<Token> tokens) {
        Set<String> types = new LinkedHashSet<>(Set.of("Int", "Bool", "String"));
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).opcode == OPEN_PAREN
                    && (name(tokens.get(i + 1), "record") || name(tokens.get(i + 1), "variant"))
                    && tokens.get(i + 2).opcode == NAME) {
                types.add(tokens.get(i + 2).payload);
            }
        }
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).opcode != OPEN_VECTOR || tokens.get(i + 1).opcode != NAME
                    || tokens.get(i + 2).opcode != NAME) continue;
            String type = tokens.get(i + 2).payload;
            if (!types.contains(type)) failure("unsupported portable bytecode type: " + type);
        }
    }

    private static int matchingClose(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            int opcode = tokens.get(i).opcode;
            if (opcode == OPEN_PAREN || opcode == OPEN_VECTOR) depth++;
            if (opcode == CLOSE_PAREN || opcode == CLOSE_VECTOR) depth--;
            if (depth == 0) return i;
            if (depth < 0) break;
        }
        failure("unbalanced bytecode delimiters");
        return -1;
    }

    private static boolean name(Token token, String value) {
        return token.opcode == NAME && token.payload.equals(value);
    }

    private static byte[] encode(List<Token> tokens, byte[] programHash) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.write(MAGIC);
            output.writeShort(FORMAT_VERSION);
            output.writeShort(DeterministicContractRuntime.CONTRACT_VERSION);
            output.writeInt(tokens.size());
            output.write(programHash);
            for (Token token : tokens) {
                output.writeByte(token.opcode);
                if (PAYLOAD_OPS.contains(token.opcode)) {
                    byte[] payload = token.payload.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(payload.length);
                    output.write(payload);
                }
            }
            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTECODE_BYTES) failure("compiled bytecode exceeds maximum size");
            return encoded;
        } catch (GeneralError error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("failed to encode bytecode", error);
        }
    }

    private static String render(List<Token> tokens) {
        StringBuilder source = new StringBuilder();
        for (Token token : tokens) {
            if (!source.isEmpty()) source.append(' ');
            source.append(switch (token.opcode) {
                case OPEN_PAREN -> "(";
                case CLOSE_PAREN -> ")";
                case OPEN_VECTOR -> "[";
                case CLOSE_VECTOR -> "]";
                case NAME -> token.payload;
                case KEYWORD -> ":" + token.payload;
                case INTEGER -> token.payload;
                case STRING -> "\"" + token.payload + "\"";
                default -> throw new IllegalStateException("unknown bytecode opcode");
            });
        }
        return source.append('\n').toString();
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder("sha256:");
        for (byte current : bytes) value.append(String.format("%02x", current & 0xff));
        return value.toString();
    }

    private static void failure(String message) {
        throw new GeneralError(message);
    }

    private static String clean(String message) {
        return message == null || message.isBlank() ? "malformed program" : message;
    }

    private record Token(int opcode, String payload) { }

    public record Artifact(byte[] bytes, String source, int instructionCount,
                           String programHash, String bytecodeHash) {
        public Artifact {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
