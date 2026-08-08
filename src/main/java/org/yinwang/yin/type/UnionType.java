package org.yinwang.yin.type;

import org.yinwang.yin.Constants;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class UnionType extends YinType {
    private final Set<YinType> members = new LinkedHashSet<>();

    private UnionType() {
    }

    public static YinType union(Collection<YinType> types) {
        UnionType union = new UnionType();
        types.forEach(union::add);
        return union.members.size() == 1 ? union.members.iterator().next() : union;
    }

    public static YinType union(YinType... types) {
        return union(Arrays.asList(types));
    }

    private void add(YinType type) {
        if (type instanceof UnionType union) {
            union.members.forEach(this::add);
        } else if (type instanceof AnyType) {
            members.clear();
            members.add(type);
        } else if (members.stream().anyMatch(AnyType.class::isInstance)) {
            return;
        } else if (members.stream().noneMatch(member -> Types.equivalent(member, type))) {
            members.add(type);
        }
    }

    public Set<YinType> members() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    @Override
    public String toString() {
        return Constants.PAREN_BEGIN + "U " + String.join(" ",
                members.stream().map(Object::toString).toList()) + Constants.PAREN_END;
    }
}
