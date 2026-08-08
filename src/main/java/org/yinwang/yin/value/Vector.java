package org.yinwang.yin.value;


import org.yinwang.yin.Constants;

import java.util.ArrayList;
import java.util.List;

public class Vector extends Value {

    private final List<Value> values;


    public Vector(List<Value> values) {
        this.values = List.copyOf(values);
    }


    public int size() {
        return values.size();
    }


    public Value get(int index) {
        return values.get(index);
    }


    public List<Value> values() {
        return values;
    }


    public Vector append(Vector other) {
        List<Value> combined = new ArrayList<>(values);
        combined.addAll(other.values);
        return new Vector(combined);
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Constants.SQUARE_BEGIN);

        boolean first = true;
        for (Value v : values) {
            if (!first) {
                sb.append(" ");
            }
            sb.append(v);
            first = false;
        }

        sb.append(Constants.SQUARE_END);
        return sb.toString();
    }

}
