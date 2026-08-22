package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.util.List;

/** A closed module body whose public bindings are named explicitly. */
public final class ModuleDef extends Node {
    public final Name name;
    public final List<Name> exports;
    public final Block body;

    public ModuleDef(Name name, List<Name> exports, Block body,
                     String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.name = name;
        this.exports = List.copyOf(exports);
        this.body = body;
    }

    @Override public Value interp(Scope<Value> scope) {
        Util.abort(this, "module declarations can only be loaded through import");
        return Value.VOID;
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        Util.abort(this, "module declarations can only be loaded through import");
        return Types.VOID;
    }

    @Override public String toString() {
        return "(module " + name + " [" + Node.printList(exports) + "] "
                + Node.printList(body.statements) + ")";
    }
}
