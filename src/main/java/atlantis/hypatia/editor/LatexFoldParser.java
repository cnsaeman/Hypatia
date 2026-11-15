package atlantis.hypatia.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;

/** Folds \begin{env} ... \end{env} blocks, based on {@link LatexTokenMaker}'s tokens. */
public class LatexFoldParser implements FoldParser {

    @Override
    public List<Fold> getFolds(RSyntaxTextArea textArea) {
        List<Fold> folds = new ArrayList<>();
        Deque<String> expected = new ArrayDeque<>();
        Fold current = null;
        try {
            for (int line = 0; line < textArea.getLineCount(); line++) {
                Token t = textArea.getTokenListForLine(line);
                while (t != null && t.isPaintable()) {
                    if (t.getType() == LatexTokenMaker.STRUCTURE
                            && (t.is(LatexTokenMaker.STRUCTURE, "\\begin") || t.is(LatexTokenMaker.STRUCTURE, "\\end"))) {
                        boolean begin = t.getLexeme().equals("\\begin");
                        Token name = environmentName(t);
                        if (name != null) {
                            String env = name.getLexeme().trim();
                            if (begin) {
                                current = (current == null)
                                        ? add(folds, new Fold(FoldType.CODE, textArea, t.getOffset()))
                                        : current.createChild(FoldType.CODE, t.getOffset());
                                expected.push(env);
                            } else if (current != null && !expected.isEmpty() && expected.peek().equals(env)) {
                                expected.pop();
                                current.setEndOffset(t.getOffset());
                                Fold parent = current.getParent();
                                if (current.isOnSingleLine() && !current.removeFromParent()) {
                                    folds.remove(folds.size() - 1);
                                }
                                current = parent;
                            }
                            t = name;
                        }
                    }
                    t = t.getNextToken();
                }
            }
        } catch (BadLocationException e) {
            // cannot happen: offsets come from the document itself
        }
        return folds;
    }

    private static Fold add(List<Fold> folds, Fold f) {
        folds.add(f);
        return f;
    }

    private static Token environmentName(Token command) {
        Token t = command.getNextToken();
        while (t != null && t.getType() == LatexTokenMaker.WHITESPACE) t = t.getNextToken();
        if (t == null || !t.isSingleChar(LatexTokenMaker.BRACE, '{')) return null;
        t = t.getNextToken();
        return (t != null && t.getType() == LatexTokenMaker.ENV_NAME) ? t : null;
    }
}
