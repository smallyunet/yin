package org.yinwang.yin.type;

public final class Types {
    public static final YinType ANY = new AnyType();
    public static final YinType BOOL = new BoolType();
    public static final YinType INT = new IntType();
    public static final YinType FLOAT = new FloatType();
    public static final YinType STRING = new StringType();
    public static final YinType VOID = new VoidType();
    public static final YinType NONE = new NoneType();

    private Types() {
    }

    public static boolean numeric(YinType type) {
        return type instanceof IntType || type instanceof FloatType;
    }

    public static YinType arithmetic(YinType left, YinType right) {
        if (!numeric(left) || !numeric(right)) {
            return null;
        }
        return left instanceof FloatType || right instanceof FloatType ? FLOAT : INT;
    }

    public static boolean subtype(YinType actual, YinType expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (expected instanceof AnyType) {
            return true;
        }
        if (actual instanceof UnionType union) {
            for (YinType member : union.members()) {
                if (!subtype(member, expected)) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof UnionType union) {
            return union.members().stream().anyMatch(member -> subtype(actual, member));
        }
        if (actual instanceof OkType ok && expected instanceof ResultType result) {
            return subtype(ok.value(), result.ok());
        }
        if (actual instanceof ErrType error && expected instanceof ResultType result) {
            return subtype(error.error(), result.error());
        }
        if (actual instanceof OkType actualOk && expected instanceof OkType expectedOk) {
            return subtype(actualOk.value(), expectedOk.value());
        }
        if (actual instanceof ErrType actualErr && expected instanceof ErrType expectedErr) {
            return subtype(actualErr.error(), expectedErr.error());
        }
        if (actual instanceof ResultType actualResult && expected instanceof ResultType expectedResult) {
            return subtype(actualResult.ok(), expectedResult.ok())
                    && subtype(actualResult.error(), expectedResult.error());
        }
        if (actual instanceof SomeType some && expected instanceof OptionType option) {
            return subtype(some.value(), option.value());
        }
        if (actual instanceof NoneType && expected instanceof OptionType) return true;
        if (actual instanceof SomeType leftSome && expected instanceof SomeType rightSome) {
            return subtype(leftSome.value(), rightSome.value());
        }
        if (actual instanceof OptionType leftOption && expected instanceof OptionType rightOption) {
            return subtype(leftOption.value(), rightOption.value());
        }
        if (actual instanceof RecordValueType record && expected instanceof VariantType variant) {
            return variant.cases().containsKey(record.name)
                    && variant.identity().equals(record.variantName());
        }
        if (actual instanceof RecordType record && expected instanceof VariantType variant) {
            return variant.cases().containsKey(record.name)
                    && variant.identity().equals(record.variantName());
        }
        if (actual instanceof VectorType actualVector
                && expected instanceof HomogeneousVectorType expectedVector) {
            return actualVector.elements().stream()
                    .allMatch(element -> subtype(element, expectedVector.element()));
        }
        if (actual instanceof HomogeneousVectorType actualVector
                && expected instanceof HomogeneousVectorType expectedVector) {
            return subtype(actualVector.element(), expectedVector.element());
        }
        if (actual instanceof VectorType actualVector && expected instanceof VectorType expectedVector) {
            if (actualVector.elements().size() != expectedVector.elements().size()) {
                return false;
            }
            for (int i = 0; i < actualVector.elements().size(); i++) {
                if (!subtype(actualVector.elements().get(i), expectedVector.elements().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (actual instanceof RecordValueType actualRecord && expected instanceof RecordType expectedRecord) {
            return expectedRecord.name != null
                    && actualRecord.nominalTypes().contains(expectedRecord.identity());
        }
        if (actual instanceof RecordValueType actualRecord && expected instanceof RecordValueType expectedRecord) {
            if (actualRecord.name != null || expectedRecord.name != null) {
                return actualRecord.name != null
                        && actualRecord.identity().equals(expectedRecord.identity());
            }
            return sameFields(actualRecord, expectedRecord);
        }
        if (actual instanceof RecordType actualRecord && expected instanceof RecordType expectedRecord) {
            return expectedRecord.name != null
                    && actualRecord.nominalTypes().contains(expectedRecord.identity());
        }
        if (actual instanceof FunctionType actualFunction
                && expected instanceof DeclaredFunctionType expectedFunction) {
            DeclaredFunctionType signature = actualFunction.declaredSignature();
            return signature != null && functionSubtype(signature, expectedFunction);
        }
        if (actual instanceof DeclaredFunctionType actualFunction
                && expected instanceof DeclaredFunctionType expectedFunction) {
            return functionSubtype(actualFunction, expectedFunction);
        }
        if (actual instanceof ToolType actualTool && expected instanceof ToolType expectedTool) {
            return equivalent(actualTool, expectedTool);
        }
        return equivalent(actual, expected);
    }

    public static boolean equivalent(YinType left, YinType right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        if (left instanceof VectorType leftVector && right instanceof VectorType rightVector) {
            if (leftVector.elements().size() != rightVector.elements().size()) {
                return false;
            }
            for (int i = 0; i < leftVector.elements().size(); i++) {
                if (!equivalent(leftVector.elements().get(i), rightVector.elements().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof HomogeneousVectorType leftVector
                && right instanceof HomogeneousVectorType rightVector) {
            return equivalent(leftVector.element(), rightVector.element());
        }
        if (left instanceof DeclaredFunctionType leftFunction
                && right instanceof DeclaredFunctionType rightFunction) {
            if (leftFunction.parameters().size() != rightFunction.parameters().size()) {
                return false;
            }
            for (int i = 0; i < leftFunction.parameters().size(); i++) {
                if (!equivalent(leftFunction.parameters().get(i), rightFunction.parameters().get(i))) {
                    return false;
                }
            }
            return equivalent(leftFunction.result(), rightFunction.result());
        }
        if (left instanceof OkType leftOk && right instanceof OkType rightOk) {
            return equivalent(leftOk.value(), rightOk.value());
        }
        if (left instanceof ErrType leftErr && right instanceof ErrType rightErr) {
            return equivalent(leftErr.error(), rightErr.error());
        }
        if (left instanceof ResultType leftResult && right instanceof ResultType rightResult) {
            return equivalent(leftResult.ok(), rightResult.ok())
                    && equivalent(leftResult.error(), rightResult.error());
        }
        if (left instanceof OptionType leftOption && right instanceof OptionType rightOption) {
            return equivalent(leftOption.value(), rightOption.value());
        }
        if (left instanceof SomeType leftSome && right instanceof SomeType rightSome) {
            return equivalent(leftSome.value(), rightSome.value());
        }
        if (left instanceof NoneType && right instanceof NoneType) return true;
        if (left instanceof ToolType leftTool && right instanceof ToolType rightTool) {
            return equivalent(leftTool.input(), rightTool.input())
                    && equivalent(leftTool.output(), rightTool.output())
                    && equivalent(leftTool.error(), rightTool.error())
                    && leftTool.descriptor().equals(rightTool.descriptor());
        }
        if (left instanceof VariantType leftVariant && right instanceof VariantType rightVariant) {
            return leftVariant.identity().equals(rightVariant.identity());
        }
        if (left instanceof UnionType leftUnion && right instanceof UnionType rightUnion) {
            return leftUnion.members().size() == rightUnion.members().size()
                    && leftUnion.members().stream().allMatch(leftMember ->
                    rightUnion.members().stream().anyMatch(rightMember -> equivalent(leftMember, rightMember)));
        }
        if (left instanceof RecordType leftRecord && right instanceof RecordType rightRecord) {
            return leftRecord.name != null && leftRecord.identity().equals(rightRecord.identity());
        }
        if (left instanceof RecordValueType leftRecord && right instanceof RecordValueType rightRecord) {
            if (leftRecord.name != null || rightRecord.name != null) {
                return leftRecord.name != null
                        && leftRecord.identity().equals(rightRecord.identity());
            }
            return sameFields(leftRecord, rightRecord);
        }
        return left instanceof AnyType
                || left instanceof BoolType
                || left instanceof FloatType
                || left instanceof IntType
                || left instanceof StringType
                || left instanceof VoidType;
    }

    public static boolean overlaps(YinType left, YinType right) {
        if (left instanceof AnyType || right instanceof AnyType) {
            return true;
        }
        if (left instanceof UnionType union) {
            return union.members().stream().anyMatch(member -> overlaps(member, right));
        }
        if (right instanceof UnionType union) {
            return union.members().stream().anyMatch(member -> overlaps(left, member));
        }
        if (numeric(left) && numeric(right)) {
            return true;
        }
        if (left instanceof RecordValueType leftRecord
                && right instanceof RecordValueType rightRecord
                && leftRecord.variantName() != null
                && leftRecord.variantName().equals(rightRecord.variantName())) return true;
        if (left instanceof RecordType leftRecord && right instanceof RecordType rightRecord
                && leftRecord.variantName() != null
                && leftRecord.variantName().equals(rightRecord.variantName())) return true;
        if (left instanceof ResultType leftResult && right instanceof ResultType rightResult) {
            return overlaps(leftResult.ok(), rightResult.ok())
                    || overlaps(leftResult.error(), rightResult.error());
        }
        if (left instanceof SomeType leftSome && right instanceof OptionType rightOption) {
            return overlaps(leftSome.value(), rightOption.value());
        }
        if (left instanceof OptionType leftOption && right instanceof SomeType rightSome) {
            return overlaps(leftOption.value(), rightSome.value());
        }
        if (left instanceof NoneType && right instanceof OptionType
                || left instanceof OptionType && right instanceof NoneType) return true;
        if (left instanceof OkType && right instanceof ErrType
                || left instanceof ErrType && right instanceof OkType) {
            return true;
        }
        if (left instanceof OkType leftOk && right instanceof OkType rightOk) {
            return overlaps(leftOk.value(), rightOk.value());
        }
        if (left instanceof ErrType leftErr && right instanceof ErrType rightErr) {
            return overlaps(leftErr.error(), rightErr.error());
        }
        if (left instanceof OkType leftOk && right instanceof ResultType rightResult) {
            return overlaps(leftOk.value(), rightResult.ok());
        }
        if (left instanceof ResultType leftResult && right instanceof OkType rightOk) {
            return overlaps(leftResult.ok(), rightOk.value());
        }
        if (left instanceof ErrType leftErr && right instanceof ResultType rightResult) {
            return overlaps(leftErr.error(), rightResult.error());
        }
        if (left instanceof ResultType leftResult && right instanceof ErrType rightErr) {
            return overlaps(leftResult.error(), rightErr.error());
        }
        return subtype(left, right) || subtype(right, left);
    }

    public static YinType vectorElement(YinType vector) {
        if (vector instanceof AnyType) {
            return ANY;
        }
        if (vector instanceof HomogeneousVectorType homogeneous) {
            return homogeneous.element();
        }
        if (vector instanceof VectorType exact) {
            if (exact.elements().isEmpty()) {
                return ANY;
            }
            return UnionType.union(exact.elements());
        }
        if (vector instanceof UnionType union) {
            java.util.List<YinType> elements = union.members().stream()
                    .map(Types::vectorElement).toList();
            return elements.stream().anyMatch(java.util.Objects::isNull)
                    ? null
                    : UnionType.union(elements);
        }
        return null;
    }

    private static boolean functionSubtype(
            DeclaredFunctionType actual, DeclaredFunctionType expected) {
        if (actual.parameters().size() != expected.parameters().size()) {
            return false;
        }
        for (int i = 0; i < actual.parameters().size(); i++) {
            if (!subtype(expected.parameters().get(i), actual.parameters().get(i))) {
                return false;
            }
        }
        return subtype(actual.result(), expected.result());
    }

    private static boolean sameFields(RecordValueType left, RecordValueType right) {
        if (!left.fields.keySet().equals(right.fields.keySet())) {
            return false;
        }
        for (String field : left.fields.keySet()) {
            if (!equivalent(left.fields.lookupLocal(field), right.fields.lookupLocal(field))) {
                return false;
            }
        }
        return true;
    }
}
