package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.RuntimeContext;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.json.JsonCodec;
import org.yinwang.yin.json.JsonSupport;
import org.yinwang.yin.type.ResultType;
import org.yinwang.yin.type.ToolType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.UnionType;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.ToolValue;
import org.yinwang.yin.value.Value;

/** Invokes one source-declared tool through the host authority boundary. */
public final class Invoke extends Node {
    private final Node tool;
    private final Node input;

    public Invoke(Node tool, Node input, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.tool = tool;
        this.input = input;
    }

    @Override public Value interp(Scope<Value> scope) {
        Value handle = tool.interp(scope);
        if (!(handle instanceof ToolValue)) Util.abort(tool, "invoke expects a tool");
        ToolValue typedTool = (ToolValue) handle;
        Value argument = input.interp(scope);
        String inputJson;
        try {
            inputJson = JsonCodec.encode(argument);
        } catch (JsonCodec.Failure failure) {
            return hostFailure(typedTool, "invalid-input", failure.getMessage(), "", scope);
        }

        RuntimeContext context = scope.runtimeContext;
        RuntimeContext.ToolDescriptor descriptor = typedTool.descriptor();
        RuntimeContext.ToolHandler handler = context == null ? null : context.tools().get(descriptor.name());
        if (handler == null) {
            return hostFailure(typedTool, "unavailable", "tool is not installed by the host",
                    inputJson, scope);
        }
        RuntimeContext.ToolRequest request = new RuntimeContext.ToolRequest(descriptor, inputJson);
        boolean authorized;
        try {
            authorized = context.authorizationPolicy().authorize(request);
        } catch (RuntimeException failure) {
            return hostFailure(typedTool, "authorization-failed",
                    "host authorization policy failed", inputJson, scope);
        }
        if (!authorized) {
            String code = descriptor.approvalRequired() ? "approval-required" : "unauthorized";
            return hostFailure(typedTool, code, "tool invocation was not authorized",
                    inputJson, scope);
        }

        try {
            RuntimeContext.ToolResponse response = handler.invoke(inputJson);
            if (response == null || response.json() == null) {
                return hostFailure(typedTool, "protocol-error", "tool returned no structured result",
                        inputJson, scope);
            }
            Node contract = response.error() ? typedTool.errorType() : typedTool.outputType();
            Value decoded;
            try {
                decoded = JsonCodec.decode(contract, typedTool.definitionScope(), response.json());
            } catch (JsonCodec.Failure failure) {
                return hostFailure(typedTool, "invalid-output",
                        "tool result violates its declared contract: " + failure.getMessage(),
                        inputJson, scope);
            }
            context.auditSink().accept(new RuntimeContext.ToolAuditEvent(
                    descriptor, inputJson, response.error() ? "error" : "ok", response.json()));
            return new ResultValue(response.error() ? ResultValue.Tag.ERR : ResultValue.Tag.OK, decoded);
        } catch (Exception failure) {
            return hostFailure(typedTool, "execution-failed",
                    "tool handler failed",
                    inputJson, scope);
        }
    }

    private Value hostFailure(ToolValue tool, String code, String message,
                              String inputJson, Scope<Value> scope) {
        Value error = JsonSupport.error("ToolError", code, tool.descriptor().name(), message);
        if (scope.runtimeContext != null) {
            scope.runtimeContext.auditSink().accept(new RuntimeContext.ToolAuditEvent(
                    tool.descriptor(), inputJson, code, JsonCodec.encode(error)));
        }
        return new ResultValue(ResultValue.Tag.ERR, error);
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        YinType handle = tool.typecheck(scope);
        if (!(handle instanceof ToolType typedTool)) {
            Util.abort(tool, "invoke expects a tool, got: " + handle);
        }
        YinType actual = input.typecheck(scope);
        ToolType typedTool = (ToolType) handle;
        if (!Types.subtype(actual, typedTool.input())) {
            Util.abort(input, "tool input type error. expected: " + typedTool.input() + ", actual: " + actual);
        }
        return new ResultType(typedTool.output(), UnionType.union(
                typedTool.error(), JsonSupport.errorValueType("ToolError")));
    }

    @Override public String toString() {
        return "(" + Constants.INVOKE_KEYWORD + " " + tool + " " + input + ")";
    }
}
