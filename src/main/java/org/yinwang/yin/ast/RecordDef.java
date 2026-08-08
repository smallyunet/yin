package org.yinwang.yin.ast;

import org.yinwang.yin.Constants;
import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.value.RecordConstructor;
import org.yinwang.yin.value.Value;
import org.yinwang.yin.type.RecordType;
import org.yinwang.yin.type.YinType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class RecordDef extends Node {
    public Name name;
    public List<Name> parents;
    public Scope<Object> propertyForm;


    public RecordDef(Name name, List<Name> parents, Scope<Object> propertyForm,
                     String file, int start, int end, int line, int col)
    {
        super(file, start, end, line, col);
        this.name = name;
        this.parents = parents;
        this.propertyForm = propertyForm;
    }


    public Value interp(Scope<Value> s) {
        Scope<Value> properties = Declare.evalProperties(propertyForm, s);

        if (parents != null) {
            for (Node p : parents) {
                Value pv = p.interp(s);
                if (!(pv instanceof RecordConstructor)) {
                    Util.abort(p, "parent is not a record: " + pv);
                }
                Scope<Value> parentProperties = ((RecordConstructor) pv).properties;
                rejectConflictingFields(properties, parentProperties, p, pv);
                properties.putAll(parentProperties);
            }
        }
        Value r = new RecordConstructor(name.id, this, properties);
        s.putValue(name.id, r);
        return r;
    }


    @Override
    public YinType typecheck(Scope<YinType> s) {
        Scope<YinType> properties = Declare.typecheckProperties(propertyForm, s);
        Set<String> nominalTypes = new LinkedHashSet<>();
        nominalTypes.add(name.id);

        if (parents != null) {
            for (Node p : parents) {
                YinType pv = p.typecheck(s);
                if (!(pv instanceof RecordType)) {
                    Util.abort(p, "parent is not a record: " + pv);
                    return org.yinwang.yin.type.Types.VOID;
                }
                Scope<YinType> parentProps = ((RecordType) pv).properties;
                nominalTypes.addAll(((RecordType) pv).nominalTypes());

                rejectConflictingFields(properties, parentProps, p, pv);

                // add all properties or all fields in parent
                properties.putAll(parentProps);
            }
        }

        YinType r = new RecordType(name.id, this, properties, nominalTypes);
        s.putValue(name.id, r);
        return r;
    }


    private static void rejectConflictingFields(Scope<?> properties, Scope<?> parentProperties,
                                                Node parent, Object parentValue) {
        for (String field : parentProperties.keySet()) {
            if (properties.containsKey(field)) {
                Util.abort(parent, "conflicting field " + field +
                        " inherited from parent " + parent + ": " + parentValue);
            }
        }
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Constants.PAREN_BEGIN);
        sb.append(Constants.RECORD_KEYWORD).append(" ");
        sb.append(name).append(" ");

        if (parents != null) {
            sb.append(" (" + Node.printList(parents) + ")");
        }

        for (String field : propertyForm.keySet()) {
            sb.append("[" + field);
            Map<String, Object> props = propertyForm.lookupAllProps(field);
            for (Map.Entry<String, Object> e : props.entrySet()) {
                sb.append(" :" + e.getKey() + " " + e.getValue());
            }
            sb.append("]");
        }

        sb.append(Constants.PAREN_END);
        return sb.toString();
    }
}
