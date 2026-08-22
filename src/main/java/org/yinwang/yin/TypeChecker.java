package org.yinwang.yin;


import org.yinwang.yin.ast.Block;
import org.yinwang.yin.ast.Declare;
import org.yinwang.yin.ast.Node;
import org.yinwang.yin.ast.Import;
import org.yinwang.yin.ast.ModuleDef;
import org.yinwang.yin.ast.Name;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.type.FunctionType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

public class TypeChecker {

    public String file;
    public Set<FunctionType> uncalled = new HashSet<>();
    public Set<FunctionType> callStack = new HashSet<>();
    private final List<RuntimeContext.ToolDescriptor> tools = new ArrayList<>();
    private final Map<Path, TypeModule> moduleCache = new LinkedHashMap<>();
    private final Map<String, Path> moduleNames = new LinkedHashMap<>();
    private final Deque<Path> loadingModules = new ArrayDeque<>();


    public TypeChecker(String file) {
        this.file = file;
    }


    public YinType typecheck(String file) {
        return typecheckProgram(parseFile(file));
    }

    public YinType typecheckSource(String sourceName, String source) {
        Node program;
        try {
            program = Parser.parseSource(sourceName, source);
        } catch (ParserException e) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + e.getMessage(), e.span));
        }
        return typecheckProgram(program);
    }

    private Node parseFile(String file) {
        try {
            return Parser.parse(file);
        } catch (ParserException e) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + e.getMessage(), e.span));
        }
    }

    private YinType typecheckProgram(Node program) {
        resetTopLevelState();
        Scope<YinType> s = Scope.buildInitTypeScope(this);
        YinType ret = program.typecheck(s);

        finishChecks(s);

        return ret;
    }

    public void registerTool(RuntimeContext.ToolDescriptor descriptor, Node location) {
        for (RuntimeContext.ToolDescriptor existing : tools) {
            if (existing.name().equals(descriptor.name())) {
                if (!existing.equals(descriptor)) {
                    Util.abort(location, "conflicting tool declaration: " + descriptor.name());
                }
                return;
            }
        }
        tools.add(descriptor);
    }

    public List<RuntimeContext.ToolDescriptor> tools() {
        return List.copyOf(tools);
    }


    public YinType typecheckTopLevel(Block program, Scope<YinType> scope) {
        resetTopLevelState();
        YinType result = Types.VOID;
        for (Node statement : program.statements) {
            result = statement.typecheck(scope);
        }
        finishChecks(scope);
        return result;
    }

    public void importInto(Import declaration, Scope<YinType> target) {
        TypeModule module = loadModule(ModuleRuntime.resolve(declaration), declaration);
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

    private TypeModule loadModule(Path path, Import declaration) {
        TypeModule existing = moduleCache.get(path);
        if (existing != null) return existing;
        if (loadingModules.contains(path)) {
            Util.abort(declaration, "circular module import: " + moduleCycle(path));
        }
        loadingModules.addLast(path);
        try {
            ModuleDef module = parseModule(path, declaration);
            Path previous = moduleNames.putIfAbsent(module.name.id, path);
            if (previous != null && !previous.equals(path)) {
                Util.abort(module.name, "duplicate module name " + module.name.id
                        + " in " + previous + " and " + path);
            }

            Scope<YinType> builtins = Scope.buildInitTypeScope(this);
            Scope<YinType> moduleScope = new Scope<>(builtins);
            YinType result = Types.VOID;
            for (Node statement : module.body.statements) {
                result = statement.typecheck(moduleScope);
            }

            Map<String, Map<String, Object>> exports = new LinkedHashMap<>();
            for (Name name : module.exports) {
                Map<String, Object> binding = moduleScope.lookupAllProps(name.id);
                if (binding == null || binding.get("value") == null) {
                    Util.abort(name, "module " + module.name.id
                            + " exports an undefined binding: " + name.id);
                }
                exports.put(name.id, new LinkedHashMap<>(binding));
            }
            TypeModule loaded = new TypeModule(module.name.id, exports, result);
            moduleCache.put(path, loaded);
            return loaded;
        } finally {
            loadingModules.removeLast();
        }
    }

    private static ModuleDef parseModule(Path path, Import declaration) {
        Node parsed;
        try {
            parsed = Parser.parse(path.toString());
        } catch (ParserException error) {
            throw new GeneralError(new Diagnostic(
                    Diagnostic.Code.SYNTAX, "parsing error: " + error.getMessage(), error.span));
        }
        if (!(parsed instanceof Block document)
                || document.statements.size() != 1
                || !(document.statements.get(0) instanceof ModuleDef module)) {
            Util.abort(declaration,
                    "imported file must contain exactly one module declaration: " + path);
        }
        return (ModuleDef) ((Block) parsed).statements.get(0);
    }

    private String moduleCycle(Path repeated) {
        StringBuilder result = new StringBuilder();
        boolean include = false;
        for (Path path : loadingModules) {
            if (path.equals(repeated)) include = true;
            if (include) {
                if (!result.isEmpty()) result.append(" -> ");
                result.append(path.getFileName());
            }
        }
        return result.append(" -> ").append(repeated.getFileName()).toString();
    }

    private void resetTopLevelState() {
        uncalled.clear();
        callStack.clear();
        tools.clear();
        moduleCache.clear();
        moduleNames.clear();
        loadingModules.clear();
    }

    private record TypeModule(String name, Map<String, Map<String, Object>> exports,
                              YinType result) { }


    private void finishChecks(Scope<YinType> scope) {

        while (!uncalled.isEmpty()) {
            List<FunctionType> toRemove = new ArrayList<>(uncalled);
            for (FunctionType ft : toRemove) {
                invokeUncalled(ft, scope);
            }
            uncalled.removeAll(toRemove);
        }
    }


    public void invokeUncalled(FunctionType fun, Scope<YinType> s) {
        Scope<YinType> funScope = new Scope<>(fun.environment);
        if (fun.properties != null) {
            Declare.mergeType(fun.properties, funScope);
        }

        callStack.add(fun);
        YinType actual = fun.function.body.typecheck(funScope);
        callStack.remove(fun);

        Object retNode = fun.properties == null
                ? null
                : fun.properties.lookupPropertyLocal(Constants.RETURN_ARROW, "type");

        if (retNode == null) {
            return;
        }
        if (!(retNode instanceof Node)) {
            Util.abort("illegal return type: " + retNode);
        }

        YinType expected = ((Node) retNode).typecheck(funScope);
        if (!Types.subtype(actual, expected)) {
            Util.abort(fun.function, "type error in return value, expected: " + expected + ", actual: " + actual);
        }
    }


    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java -cp yin.jar org.yinwang.yin.TypeChecker <program.yin>");
            System.exit(2);
        }

        try {
            TypeChecker tc = new TypeChecker(args[0]);
            YinType result = tc.typecheck(args[0]);
            Util.msg(result.toString());
        } catch (GeneralError error) {
            System.err.println(error);
            System.exit(1);
        }
    }

}
