package org.yinwang.yin.value;

import org.yinwang.yin.ast.VariantDef;

/** Runtime schema handle used by type-directed JSON operations. */
public final class VariantDescriptor extends Value {
    private final VariantDef definition;
    public VariantDescriptor(VariantDef definition) { this.definition = definition; }
    public VariantDef definition() { return definition; }
    @Override public String toString() { return "(variant " + definition.name.id + ")"; }
}
