package org.yinwang.yin.ast;

import org.yinwang.yin.Scope;
import org.yinwang.yin.Util;
import org.yinwang.yin.type.Types;
import org.yinwang.yin.type.YinType;
import org.yinwang.yin.value.Value;

import java.math.BigInteger;

/** Historical arbitrary-precision literal node; not produced by the parser. */
public class BigInt extends Node {
    public String content;
    public BigInteger value;
    public int base;

    public BigInt(String content, String file, int start, int end, int line, int col) {
        super(file, start, end, line, col);
        this.content = content;

        int sign;
        if (content.startsWith("+")) {
            sign = 1;
            content = content.substring(1);
        } else if (content.startsWith("-")) {
            sign = -1;
            content = content.substring(1);
        } else {
            sign = 1;
        }

        if (content.startsWith("0b")) {
            base = 2;
            content = content.substring(2);
        } else if (content.startsWith("0x")) {
            base = 16;
            content = content.substring(2);
        } else if (content.startsWith("0o")) {
            base = 8;
            content = content.substring(2);
        } else {
            base = 10;
        }

        BigInteger parsed = new BigInteger(content, base);
        this.value = sign == -1 ? parsed.negate() : parsed;
    }

    public static BigInt parse(String content, String file, int start, int end, int line, int col) {
        try {
            return new BigInt(content, file, start, end, line, col);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    @Override
    public Value interp(Scope<Value> scope) {
        Util.abort(this, "arbitrary-precision integers are unsupported");
        return Value.VOID;
    }

    @Override
    public YinType typecheck(Scope<YinType> scope) {
        Util.abort(this, "arbitrary-precision integers are unsupported");
        return Types.VOID;
    }

    @Override
    public String toString() {
        return content;
    }
}
