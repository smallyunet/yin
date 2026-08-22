package org.yinwang.yin;

import org.yinwang.yin.ast.Node;

/** Stable internal identity for a source-declared nominal type. */
public final class NominalIdentity {
    private NominalIdentity() { }

    public static String of(Node definition, String name) {
        return definition == null || definition.file == null
                ? name
                : definition.file + "::" + name;
    }
}
