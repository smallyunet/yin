package org.yinwang.yin.type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closed, nominal set of tagged record cases. */
public final class VariantType extends YinType {
    private final String name;
    private final Map<String, RecordType> cases;
    public VariantType(String name, Map<String, RecordType> cases) {
        this.name = name;
        this.cases = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cases));
    }
    public String name() { return name; }
    public Map<String, RecordType> cases() { return cases; }
    public List<RecordType> alternatives() { return List.copyOf(cases.values()); }
    @Override public String toString() { return name; }
}
