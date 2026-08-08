package org.yinwang.yin;


import org.yinwang.yin.ast.Node;

public class GeneralError extends RuntimeException {
    public final Diagnostic diagnostic;


    public GeneralError(Node location, String msg) {
        this(new Diagnostic(Diagnostic.Code.LANGUAGE, msg, location.sourceSpan()));
    }


    public GeneralError(String msg) {
        this(new Diagnostic(Diagnostic.Code.LANGUAGE, msg, null));
    }


    public GeneralError(Diagnostic diagnostic) {
        super(diagnostic.message());
        this.diagnostic = diagnostic;
    }


    public String toString() {
        return diagnostic.format();
    }

}
