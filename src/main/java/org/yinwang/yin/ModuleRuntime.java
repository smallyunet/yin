package org.yinwang.yin;

import org.yinwang.yin.ast.Block;
import org.yinwang.yin.ast.Import;
import org.yinwang.yin.ast.ModuleDef;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.value.Value;

import java.net.URI;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-execution module loader with isolated scopes and single evaluation. */
public final class ModuleRuntime {
    private final RuntimeContext context;
    private final Map<Path, RuntimeModule> cache = new LinkedHashMap<>();
    private final Map<String, Path> moduleNames = new LinkedHashMap<>();
    private final Deque<Path> loading = new ArrayDeque<>();

    public ModuleRuntime(RuntimeContext context) {
        this.context = context;
    }

    public void importInto(Import declaration, Scope<Value> target) {
        RuntimeModule module = load(resolve(declaration), declaration);
        for (Name name : declaration.names) {
            Map<String, Object> binding = module.exports.get(name.id);
            if (binding == null) {
                Util.abort(name, "module " + module.name + " does not export: " + name.id);
            }
            if (target.containsKey(name.id)) {
                Util.abort(name, "import conflicts with an existing binding: " + name.id);
            }
            target.putProperties(name.id, binding);
        }
    }

    private RuntimeModule load(Path path, Import declaration) {
        RuntimeModule existing = cache.get(path);
        if (existing != null) return existing;
        if (loading.contains(path)) {
            Util.abort(declaration, "circular module import: " + cycle(path));
        }

        loading.addLast(path);
        try {
            ModuleDef module = parseModule(path, declaration);
            Path previous = moduleNames.putIfAbsent(module.name.id, path);
            if (previous != null && !previous.equals(path)) {
                Util.abort(module.name, "duplicate module name " + module.name.id
                        + " in " + previous + " and " + path);
            }

            Scope<Value> builtins = Scope.buildInitScope(context);
            builtins.moduleRuntime = this;
            Scope<Value> moduleScope = new Scope<>(builtins);
            for (Node statement : module.body.statements) {
                statement.interp(moduleScope);
            }

            Map<String, Map<String, Object>> exports = collectExports(module, moduleScope);
            RuntimeModule loaded = new RuntimeModule(module.name.id, exports);
            cache.put(path, loaded);
            return loaded;
        } finally {
            loading.removeLast();
        }
    }

    private static ModuleDef parseModule(Path path, Import declaration) {
        try {
            Node parsed = Parser.parse(path.toString());
            if (!(parsed instanceof Block document)
                    || document.statements.size() != 1
                    || !(document.statements.get(0) instanceof ModuleDef module)) {
                Util.abort(declaration,
                        "imported file must contain exactly one module declaration: " + path);
            }
            return (ModuleDef) ((Block) parsed).statements.get(0);
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + error.getMessage(), error.span));
        }
    }

    private static Map<String, Map<String, Object>> collectExports(
            ModuleDef module, Scope<Value> scope) {
        Map<String, Map<String, Object>> exports = new LinkedHashMap<>();
        for (Name name : module.exports) {
            Map<String, Object> binding = scope.lookupAllProps(name.id);
            if (binding == null || binding.get("value") == null) {
                Util.abort(name, "module " + module.name.id
                        + " exports an undefined binding: " + name.id);
            }
            exports.put(name.id, new LinkedHashMap<>(binding));
        }
        return exports;
    }

    static Path resolve(Import declaration) {
        try {
            Path requested = Path.of(declaration.path.value);
            if (requested.isAbsolute()) {
                Util.abort(declaration.path, "import path must be relative");
            }
            if (!declaration.path.value.endsWith(".yin")) {
                Util.abort(declaration.path, "import path must end with .yin");
            }
            Path importer = sourcePath(declaration.file);
            Path parent = importer.getParent();
            if (parent == null) {
                Util.abort(declaration, "cannot resolve import without a source directory");
            }
            Path resolved = parent.resolve(requested).normalize().toAbsolutePath();
            try {
                return resolved.toRealPath();
            } catch (IOException error) {
                Util.abort(declaration.path, "failed to read imported module: " + resolved);
                return resolved;
            }
        } catch (IllegalArgumentException error) {
            Util.abort(declaration.path, "invalid import path: " + declaration.path.value);
            throw error;
        }
    }

    private static Path sourcePath(String source) {
        if (source != null && source.startsWith("file:")) {
            return Path.of(URI.create(source)).toAbsolutePath().normalize();
        }
        return Path.of(source).toAbsolutePath().normalize();
    }

    private String cycle(Path repeated) {
        StringBuilder result = new StringBuilder();
        boolean include = false;
        for (Path path : loading) {
            if (path.equals(repeated)) include = true;
            if (include) {
                if (!result.isEmpty()) result.append(" -> ");
                result.append(path.getFileName());
            }
        }
        return result.append(" -> ").append(repeated.getFileName()).toString();
    }

    private record RuntimeModule(String name, Map<String, Map<String, Object>> exports) { }
}
