package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.util.List;

/** Selectively imports public bindings from one relative module file. */
public final class Import extends Node {
    public final Str path;
    public final List<Name> names;

    public Import(Str path, List<Name> names,
                  String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.path = path;
        this.names = List.copyOf(names);
    }

    @Override public Value interp(Scope<Value> scope) {
        if (scope.moduleRuntime == null) {
            Util.abort(this, "imports are unavailable in this runtime");
        }
        scope.moduleRuntime.importInto(this, scope);
        return Value.VOID;
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        if (scope.typeChecker == null) {
            Util.abort(this, "imports are unavailable in this type-checking context");
        }
        scope.typeChecker.importInto(this, scope);
        return Types.VOID;
    }

    @Override public String toString() {
        return "(import " + path + " [" + Node.printList(names) + "])";
    }
}
