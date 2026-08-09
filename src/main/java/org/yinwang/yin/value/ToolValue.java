package org.yinwang.yin.value;

import org.yinwang.yin.RuntimeContext;
import org.yinwang.yin.Scope;
import org.yinwang.yin.ast.Node;

/** Runtime handle for a source-declared, host-implemented tool. */
public final class ToolValue extends Value {
    private final Node inputType;
    private final Node outputType;
    private final Node errorType;
    private final RuntimeContext.ToolDescriptor descriptor;
    private final Scope<Value> definitionScope;

    public ToolValue(Node inputType, Node outputType, Node errorType,
                     RuntimeContext.ToolDescriptor descriptor, Scope<Value> definitionScope) {
        this.inputType = inputType;
        this.outputType = outputType;
        this.errorType = errorType;
        this.descriptor = descriptor;
        this.definitionScope = definitionScope;
    }

    public Node inputType() { return inputType; }
    public Node outputType() { return outputType; }
    public Node errorType() { return errorType; }
    public RuntimeContext.ToolDescriptor descriptor() { return descriptor; }
    public Scope<Value> definitionScope() { return definitionScope; }

    @Override public String toString() { return "<tool " + descriptor.name() + ">"; }
}
