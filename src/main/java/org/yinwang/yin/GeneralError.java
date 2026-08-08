package org.yinwang.yin;


import org.yinwang.yin.ast.Node;

public class GeneralError extends RuntimeException {
    public final Node location;


    public GeneralError(Node location, String msg) {
        super(msg);
        this.location = location;
    }


    public GeneralError(String msg) {
        super(msg);
        this.location = null;
    }


    public String toString() {
        if (location != null) {
            return location.getFileLineCol() + " " + getMessage();
        } else {
            return getMessage();
        }
    }

}
