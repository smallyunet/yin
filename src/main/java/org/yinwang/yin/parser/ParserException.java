package org.yinwang.yin.parser;


import org.yinwang.yin.SourceSpan;
import org.yinwang.yin.ast.Node;

public class ParserException extends Exception {
    public int line;
    public int col;
    public int start;
    public SourceSpan span;


    public ParserException(String message, String file, int line, int col, int start, int end) {
        super(message);
        this.line = line;
        this.col = col;
        this.start = start;
        this.span = new SourceSpan(file, start, end, line, col);
    }


    public ParserException(String message, Node node) {
        super(message);
        this.line = node.line;
        this.col = node.col;
        this.start = node.start;
        this.span = node.sourceSpan();
    }


    @Override
    public String toString() {
        return (line + 1) + ":" + (col + 1) + " parsing error " + getMessage();
    }
}
