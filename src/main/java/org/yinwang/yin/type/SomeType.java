package org.yinwang.yin.type;

/** Precise type of a present optional value. */
public final class SomeType extends YinType {
    private final YinType value;
    public SomeType(YinType value) { this.value = value; }
    public YinType value() { return value; }
    @Override public String toString() { return "(Some " + value + ")"; }
}
