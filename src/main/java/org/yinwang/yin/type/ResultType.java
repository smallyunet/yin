package org.yinwang.yin.type;

/** Source-expressible outcome type with distinct success and failure payloads. */
public final class ResultType extends YinType {
    private final YinType ok;
    private final YinType error;

    public ResultType(YinType ok, YinType error) {
        this.ok = ok;
        this.error = error;
    }

    public YinType ok() {
        return ok;
    }

    public YinType error() {
        return error;
    }

    @Override
    public String toString() {
        return "(Result " + ok + " " + error + ")";
    }
}
