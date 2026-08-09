package org.yinwang.yin.type;

/** Source-expressible optional value type. */
public final class OptionType extends YinType {
    private final YinType value;
    public OptionType(YinType value) { this.value = value; }
    public YinType value() { return value; }
    @Override public String toString() { return "(Option " + value + ")"; }
}
