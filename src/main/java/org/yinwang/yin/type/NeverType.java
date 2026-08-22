package org.yinwang.yin.type;

/** Inferred bottom type used by empty immutable collections. */
public final class NeverType extends YinType {
    @Override
    public String toString() {
        return "Never";
    }
}
