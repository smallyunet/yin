package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.BoolType;
import org.yinwang.yin.type.HomogeneousVectorType;
import org.yinwang.yin.type.RecordType;
import org.yinwang.yin.type.RecordValueType;
import org.yinwang.yin.type.ErrType;
import org.yinwang.yin.type.OkType;
import org.yinwang.yin.type.ResultType;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.UnionType;
import org.yinwang.yin.type.VectorType;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.BoolValue;
import org.yinwang.yin.value.FloatValue;
import org.yinwang.yin.value.IntValue;
import org.yinwang.yin.value.RecordConstructor;
import org.yinwang.yin.value.RecordValue;
import org.yinwang.yin.value.ResultValue;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.value.ValueEquality;
import org.yinwang.yin.value.Vector;
import org.yinwang.yin.value.StringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exhaustive pattern matching with branch-local immutable bindings. */
public final class Match extends Node {
    public record Clause(MatchPattern pattern, Node body) {
    }

    private final Node target;
    private final List<Clause> clauses;

    public Match(Node target, List<Clause> clauses,
                 String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.target = target;
        this.clauses = List.copyOf(clauses);
    }

    @Override
    public Value interp(Scope<Value> scope) {
        Value value = target.interp(scope);
        for (Clause clause : clauses) {
            Map<String, Value> bindings = new LinkedHashMap<>();
            if (matches(clause.pattern(), value, scope, bindings)) {
                Scope<Value> branch = new Scope<>(scope);
                bindings.forEach(branch::putValue);
                return clause.body().interp(branch);
            }
        }
        Util.abort(this, "match did not handle value: " + value);
        return Value.VOID;
    }

    @Override
    public YinType typecheck(Scope<YinType> scope) {
        YinType targetType = target.typecheck(scope);
        List<YinType> alternatives = alternatives(targetType);
        Coverage coverage = new Coverage(alternatives.size());
        List<YinType> results = new ArrayList<>();

        for (Clause clause : clauses) {
            Map<String, List<YinType>> collected = new LinkedHashMap<>();
            boolean reachable = false;
            for (int i = 0; i < alternatives.size(); i++) {
                YinType alternative = alternatives.get(i);
                PatternTypes analysis = analyze(clause.pattern(), alternative, scope);
                if (analysis != null) {
                    reachable = true;
                    analysis.bindings().forEach((name, type) ->
                            collected.computeIfAbsent(name, ignored -> new ArrayList<>()).add(type));
                    markCoverage(clause.pattern(), alternative, scope, coverage, i);
                }
            }
            if (!reachable) {
                Util.abort(clause.pattern().location(),
                        "pattern cannot match target type: " + targetType);
            }
            Scope<YinType> branch = new Scope<>(scope);
            collected.forEach((name, types) -> branch.putValue(name, UnionType.union(types)));
            results.add(clause.body().typecheck(branch));
        }

        for (int i = 0; i < alternatives.size(); i++) {
            if (!coverage.complete(i, alternatives.get(i))) {
                Util.abort(this, "non-exhaustive match for type: " + alternatives.get(i));
            }
        }
        return UnionType.union(results);
    }

    private static boolean matches(MatchPattern pattern, Value value, Scope<Value> scope,
                                   Map<String, Value> bindings) {
        if (pattern instanceof MatchPattern.Wildcard) {
            return true;
        }
        if (pattern instanceof MatchPattern.Binding binding) {
            bindings.put(binding.name().id, value);
            return true;
        }
        if (pattern instanceof MatchPattern.Literal literal) {
            return ValueEquality.equal(literal.value().interp(scope), value);
        }
        if (pattern instanceof MatchPattern.VectorPattern vectorPattern) {
            if (!(value instanceof Vector vector)
                    || vector.size() != vectorPattern.elements().size()) {
                return false;
            }
            for (int i = 0; i < vector.size(); i++) {
                if (!matches(vectorPattern.elements().get(i), vector.get(i), scope, bindings)) {
                    return false;
                }
            }
            return true;
        }
        MatchPattern.RecordPattern recordPattern = (MatchPattern.RecordPattern) pattern;
        ResultValue.Tag resultTag = resultTag(recordPattern.type().id);
        if (resultTag != null) {
            return recordPattern.fields().size() == 1
                    && value instanceof ResultValue result
                    && result.tag() == resultTag
                    && matches(recordPattern.fields().get(0), result.payload(), scope, bindings);
        }
        YinType builtIn = builtInType(recordPattern.type().id);
        if (builtIn != null) {
            return recordPattern.fields().size() == 1
                    && valueMatchesType(value, builtIn)
                    && matches(recordPattern.fields().get(0), value, scope, bindings);
        }
        if (!(value instanceof RecordValue record)
                || !record.nominalTypes().contains(recordPattern.type().id)) {
            return false;
        }
        Value constructorValue = scope.lookup(recordPattern.type().id);
        if (!(constructorValue instanceof RecordConstructor constructor)
                || constructor.properties.keySet().size() != recordPattern.fields().size()) {
            return false;
        }
        int index = 0;
        for (String field : constructor.properties.keySet()) {
            if (!matches(recordPattern.fields().get(index++),
                    record.properties.lookupLocal(field), scope, bindings)) {
                return false;
            }
        }
        return true;
    }

    private static PatternTypes analyze(
            MatchPattern pattern, YinType candidate, Scope<YinType> scope) {
        if (pattern instanceof MatchPattern.Wildcard) {
            return new PatternTypes(Map.of());
        }
        if (pattern instanceof MatchPattern.Binding binding) {
            return new PatternTypes(Map.of(binding.name().id, candidate));
        }
        if (pattern instanceof MatchPattern.Literal literal) {
            YinType literalType = literal.value().typecheck(scope);
            return Types.overlaps(candidate, literalType) ? new PatternTypes(Map.of()) : null;
        }
        if (pattern instanceof MatchPattern.VectorPattern vectorPattern) {
            List<YinType> elements;
            if (candidate instanceof VectorType exact) {
                if (exact.elements().size() != vectorPattern.elements().size()) {
                    return null;
                }
                elements = exact.elements();
            } else if (candidate instanceof HomogeneousVectorType homogeneous) {
                elements = java.util.Collections.nCopies(
                        vectorPattern.elements().size(), homogeneous.element());
            } else if (candidate instanceof org.yinwang.yin.type.AnyType) {
                elements = java.util.Collections.nCopies(
                        vectorPattern.elements().size(), Types.ANY);
            } else {
                return null;
            }
            return analyzeChildren(vectorPattern.elements(), elements, scope);
        }

        MatchPattern.RecordPattern recordPattern = (MatchPattern.RecordPattern) pattern;
        if (resultTag(recordPattern.type().id) != null) {
            YinType resultPayload = resultPayload(recordPattern.type().id, candidate);
            if (recordPattern.fields().size() != 1) {
                Util.abort(recordPattern.location(), recordPattern.type().id
                        + " pattern expects exactly one payload");
            }
            if (resultPayload == null) {
                return null;
            }
            return analyzeChildren(recordPattern.fields(), List.of(resultPayload), scope);
        }
        YinType declared = scope.lookup(recordPattern.type().id);
        YinType builtIn = builtInType(recordPattern.type().id);
        if (builtIn != null) {
            if (recordPattern.fields().size() != 1 || !Types.overlaps(candidate, builtIn)) {
                return null;
            }
            return analyzeChildren(recordPattern.fields(), List.of(builtIn), scope);
        }
        if (!(declared instanceof RecordType patternType)) {
            Util.abort(recordPattern.type(), "unknown record type in pattern: " + recordPattern.type().id);
            return null;
        }
        Scope<YinType> candidateFields;
        Set<String> nominalTypes;
        if (candidate instanceof RecordValueType record) {
            candidateFields = record.fields;
            nominalTypes = record.nominalTypes();
        } else if (candidate instanceof RecordType record) {
            candidateFields = record.properties;
            nominalTypes = record.nominalTypes();
        } else if (candidate instanceof org.yinwang.yin.type.AnyType) {
            candidateFields = patternType.properties;
            nominalTypes = patternType.nominalTypes();
        } else {
            return null;
        }
        if (!nominalTypes.contains(patternType.name)
                || recordPattern.fields().size() != patternType.properties.keySet().size()) {
            return null;
        }
        List<YinType> fields = patternType.properties.keySet().stream()
                .map(candidateFields::lookupLocalType).toList();
        return analyzeChildren(recordPattern.fields(), fields, scope);
    }

    private static PatternTypes analyzeChildren(
            List<MatchPattern> patterns, List<YinType> types, Scope<YinType> scope) {
        Map<String, YinType> bindings = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            PatternTypes child = analyze(patterns.get(i), types.get(i), scope);
            if (child == null) {
                return null;
            }
            for (Map.Entry<String, YinType> binding : child.bindings().entrySet()) {
                if (bindings.put(binding.getKey(), binding.getValue()) != null) {
                    Util.abort(patterns.get(i).location(),
                            "duplicate binding in pattern: " + binding.getKey());
                }
            }
        }
        return new PatternTypes(bindings);
    }

    private static void markCoverage(MatchPattern pattern, YinType candidate,
                                     Scope<YinType> scope, Coverage coverage, int index) {
        if (irrefutable(pattern, candidate, scope)) {
            coverage.total[index] = true;
        } else if (candidate instanceof BoolType && pattern instanceof MatchPattern.Literal literal
                && literal.value() instanceof Name name) {
            if (name.id.equals("true")) {
                coverage.boolTrue[index] = true;
            } else if (name.id.equals("false")) {
                coverage.boolFalse[index] = true;
            }
        }
    }

    private static boolean irrefutable(
            MatchPattern pattern, YinType candidate, Scope<YinType> scope) {
        if (pattern instanceof MatchPattern.Wildcard || pattern instanceof MatchPattern.Binding) {
            return true;
        }
        if (pattern instanceof MatchPattern.VectorPattern vectorPattern
                && candidate instanceof VectorType exact
                && vectorPattern.elements().size() == exact.elements().size()) {
            for (int i = 0; i < exact.elements().size(); i++) {
                if (!irrefutable(vectorPattern.elements().get(i), exact.elements().get(i), scope)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern instanceof MatchPattern.RecordPattern recordPattern) {
            if (resultTag(recordPattern.type().id) != null) {
                YinType resultPayload = resultPayload(recordPattern.type().id, candidate);
                return recordPattern.fields().size() == 1
                        && resultPayload != null
                        && (candidate instanceof OkType || candidate instanceof ErrType)
                        && irrefutable(recordPattern.fields().get(0), resultPayload, scope);
            }
            YinType builtIn = builtInType(recordPattern.type().id);
            if (builtIn != null) {
                return Types.subtype(candidate, builtIn)
                        && recordPattern.fields().size() == 1
                        && irrefutable(recordPattern.fields().get(0), builtIn, scope);
            }
            PatternTypes analysis = analyze(recordPattern, candidate, scope);
            if (analysis == null) {
                return false;
            }
            RecordType patternType = (RecordType) scope.lookup(recordPattern.type().id);
            List<YinType> fieldTypes = patternType.properties.keySet().stream()
                    .map(patternType.properties::lookupLocalType).toList();
            for (int i = 0; i < fieldTypes.size(); i++) {
                if (!irrefutable(recordPattern.fields().get(i), fieldTypes.get(i), scope)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static List<YinType> alternatives(YinType type) {
        List<YinType> alternatives = new ArrayList<>();
        collectAlternatives(type, alternatives);
        return alternatives;
    }

    private static void collectAlternatives(YinType type, List<YinType> alternatives) {
        if (type instanceof UnionType union) {
            union.members().forEach(member -> collectAlternatives(member, alternatives));
        } else if (type instanceof ResultType result) {
            alternatives.add(new OkType(result.ok()));
            alternatives.add(new ErrType(result.error()));
        } else {
            alternatives.add(type);
        }
    }

    private static ResultValue.Tag resultTag(String name) {
        return switch (name) {
            case "Ok" -> ResultValue.Tag.OK;
            case "Err" -> ResultValue.Tag.ERR;
            default -> null;
        };
    }

    private static YinType resultPayload(String name, YinType candidate) {
        return switch (name) {
            case "Ok" -> {
                if (candidate instanceof OkType ok) {
                    yield ok.value();
                }
                if (candidate instanceof ResultType result) {
                    yield result.ok();
                }
                if (candidate instanceof org.yinwang.yin.type.AnyType) {
                    yield Types.ANY;
                }
                yield null;
            }
            case "Err" -> {
                if (candidate instanceof ErrType error) {
                    yield error.error();
                }
                if (candidate instanceof ResultType result) {
                    yield result.error();
                }
                if (candidate instanceof org.yinwang.yin.type.AnyType) {
                    yield Types.ANY;
                }
                yield null;
            }
            default -> null;
        };
    }

    private static YinType builtInType(String name) {
        return switch (name) {
            case "Int" -> Types.INT;
            case "Float" -> Types.FLOAT;
            case "Bool" -> Types.BOOL;
            case "String" -> Types.STRING;
            default -> null;
        };
    }

    private static boolean valueMatchesType(Value value, YinType type) {
        return type == Types.INT && value instanceof IntValue
                || type == Types.FLOAT && value instanceof FloatValue
                || type == Types.BOOL && value instanceof BoolValue
                || type == Types.STRING && value instanceof StringValue;
    }

    private record PatternTypes(Map<String, YinType> bindings) {
    }

    private static final class Coverage {
        private final boolean[] total;
        private final boolean[] boolTrue;
        private final boolean[] boolFalse;

        private Coverage(int size) {
            total = new boolean[size];
            boolTrue = new boolean[size];
            boolFalse = new boolean[size];
        }

        private boolean complete(int index, YinType type) {
            return total[index] || type instanceof BoolType && boolTrue[index] && boolFalse[index];
        }
    }
}
