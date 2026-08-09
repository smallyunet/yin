package org.yinwang.yin.type;

import org.yinwang.yin.RuntimeContext;

/** Static signature and authority metadata for a host-injected tool. */
public final class ToolType extends YinType {
    private final YinType input;
    private final YinType output;
    private final YinType error;
    private final RuntimeContext.ToolDescriptor descriptor;

    public ToolType(YinType input, YinType output, YinType error,
                    RuntimeContext.ToolDescriptor descriptor) {
        this.input = input;
        this.output = output;
        this.error = error;
        this.descriptor = descriptor;
    }

    public YinType input() { return input; }
    public YinType output() { return output; }
    public YinType error() { return error; }
    public RuntimeContext.ToolDescriptor descriptor() { return descriptor; }

    @Override public String toString() {
        return "(Tool " + input + " " + output + " " + error + ")";
    }
}
