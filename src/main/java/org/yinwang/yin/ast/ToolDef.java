package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.RuntimeContext;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.ToolType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.ToolValue;
import org.yinwang.yin.value.Value;

/** Declares a typed tool contract without granting or implementing authority. */
public final class ToolDef extends Node {
    public final Name name;
    public final Node inputType;
    public final Node outputType;
    public final Node errorType;
    public final String capability;
    public final RuntimeContext.Effect effect;
    public final boolean approvalRequired;
    public final boolean idempotent;
    public final boolean openWorld;

    public ToolDef(Name name, Node inputType, Node outputType, Node errorType,
                   String capability, RuntimeContext.Effect effect,
                   boolean approvalRequired, boolean idempotent, boolean openWorld,
                   String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.name = name;
        this.inputType = inputType;
        this.outputType = outputType;
        this.errorType = errorType;
        this.capability = capability;
        this.effect = effect;
        this.approvalRequired = approvalRequired;
        this.idempotent = idempotent;
        this.openWorld = openWorld;
    }

    public RuntimeContext.ToolDescriptor descriptor() {
        return new RuntimeContext.ToolDescriptor(name.id, inputType.toString(), outputType.toString(),
                errorType.toString(), capability, effect, approvalRequired, idempotent, openWorld);
    }

    @Override public Value interp(Scope<Value> scope) {
        if (scope.lookupLocal(name.id) != null) Util.abort(name, "duplicate tool name: " + name.id);
        ToolValue value = new ToolValue(inputType, outputType, errorType, descriptor(), scope);
        scope.putValue(name.id, value);
        return Value.VOID;
    }

    @Override public YinType typecheck(Scope<YinType> scope) {
        if (scope.lookupLocal(name.id) != null) Util.abort(name, "duplicate tool name: " + name.id);
        YinType input = inputType.typecheck(scope);
        YinType output = outputType.typecheck(scope);
        YinType error = errorType.typecheck(scope);
        requireJsonContract(input, inputType);
        requireJsonContract(output, outputType);
        requireJsonContract(error, errorType);
        ToolType tool = new ToolType(input, output, error, descriptor());
        scope.putValue(name.id, tool);
        if (scope.typeChecker != null) scope.typeChecker.registerTool(descriptor(), this);
        return Types.VOID;
    }

    private static void requireJsonContract(YinType type, Node location) {
        if (type instanceof org.yinwang.yin.type.AnyType
                || type instanceof org.yinwang.yin.type.IntType
                || type instanceof org.yinwang.yin.type.FloatType
                || type instanceof org.yinwang.yin.type.BoolType
                || type instanceof org.yinwang.yin.type.StringType) return;
        if (type instanceof org.yinwang.yin.type.RecordType record) {
            for (String field : record.properties.keySet()) {
                requireJsonContract(record.properties.lookupLocalType(field), location);
            }
            return;
        }
        if (type instanceof org.yinwang.yin.type.VariantType variant) {
            for (org.yinwang.yin.type.RecordType alternative : variant.alternatives()) {
                requireJsonContract(alternative, location);
            }
            return;
        }
        if (type instanceof org.yinwang.yin.type.HomogeneousVectorType vector) {
            requireJsonContract(vector.element(), location);
            return;
        }
        if (type instanceof org.yinwang.yin.type.VectorType vector) {
            for (YinType element : vector.elements()) requireJsonContract(element, location);
            return;
        }
        if (type instanceof org.yinwang.yin.type.OptionType option) {
            requireJsonContract(option.value(), location);
            return;
        }
        if (type instanceof org.yinwang.yin.type.ResultType result) {
            requireJsonContract(result.ok(), location);
            requireJsonContract(result.error(), location);
            return;
        }
        if (type instanceof org.yinwang.yin.type.UnionType union) {
            for (YinType member : union.members()) requireJsonContract(member, location);
            return;
        }
        Util.abort(location, "tool contract is not JSON-encodable: " + type);
    }

    @Override public String toString() {
        return "(" + Constants.TOOL_KEYWORD + " " + name + " " + inputType + " " + outputType
                + " " + errorType + " :capability \"" + capability + "\" :effect :"
                + effect.sourceName() + " :approval " + approvalRequired + " :idempotent "
                + idempotent + " :open-world " + openWorld + ")";
    }
}
