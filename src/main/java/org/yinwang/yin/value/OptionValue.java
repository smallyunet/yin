package org.yinwang.yin.value;

/** Immutable present/absent value. */
public final class OptionValue extends Value {
    private final Value value;
    private OptionValue(Value value) { this.value = value; }
    public static OptionValue some(Value value) { return new OptionValue(value); }
    public static OptionValue none() { return new OptionValue(null); }
    public boolean present() { return value != null; }
    public Value value() { return value; }
    @Override public String toString() { return present() ? "(some " + value + ")" : "none"; }
}
