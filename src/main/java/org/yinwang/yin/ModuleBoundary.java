package org.yinwang.yin;

import org.yinwang.yin.ast.Import;
import org.yinwang.yin.ast.ModuleDef;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Security boundary for profiles whose digest covers exactly one source file. */
public final class ModuleBoundary {
    private ModuleBoundary() { }

    public static void requireSingleFile(String sourceName, String source, String profile) {
        Node program;
        try {
            program = Parser.parseSource(sourceName, source);
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + error.getMessage(), error.span));
        }
        inspect(program, profile, new IdentityHashMap<>());
    }

    private static void inspect(Object value, String profile,
                                IdentityHashMap<Object, Boolean> seen) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value.getClass().isEnum()
                || seen.put(value, true) != null) return;
        if (value instanceof Import || value instanceof ModuleDef) {
            Util.abort((Node) value,
                    "modules are unavailable in " + profile
                            + " until dependency digests are bound to the execution envelope");
        }
        if (value instanceof Node node) {
            for (Class<?> type = node.getClass(); type != null && Node.class.isAssignableFrom(type);
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        inspect(field.get(node), profile, seen);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("failed to inspect module boundary", error);
                    }
                }
            }
            return;
        }
        if (value instanceof Scope<?> scope) {
            inspect(scope.table, profile, seen);
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                inspect(key, profile, seen);
                inspect(item, profile, seen);
            });
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> inspect(item, profile, seen));
        }
    }
}
