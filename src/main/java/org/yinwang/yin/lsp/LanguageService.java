package org.yinwang.yin.lsp;

import org.yinwang.yin.Diagnostic;
import org.yinwang.yin.Formatter;
import org.yinwang.yin.GeneralError;
import org.yinwang.yin.Scope;
import org.yinwang.yin.TypeChecker;
import org.yinwang.yin.ast.Block;
import org.yinwang.yin.parser.Parser;
import org.yinwang.yin.parser.ParserException;
import org.yinwang.yin.type.YinType;

import java.util.List;

/** Stateless editor-facing analysis over unsaved Yin source text. */
public final class LanguageService {
    public List<Diagnostic> diagnose(String sourceName, String source) {
        try {
            Block program = (Block) Parser.parseSource(sourceName, source);
            TypeChecker checker = new TypeChecker(sourceName);
            Scope<YinType> scope = Scope.buildInitTypeScope(checker);
            checker.typecheckTopLevel(program, scope);
            return List.of();
        } catch (ParserException error) {
            return List.of(new Diagnostic(
                    Diagnostic.Code.SYNTAX,
                    "parsing error: " + error.getMessage(),
                    error.span));
        } catch (GeneralError error) {
            return List.of(error.diagnostic);
        }
    }

    public String format(String sourceName, String source) {
        return Formatter.format(sourceName, source);
    }
}
