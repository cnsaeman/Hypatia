package atlantis.hypatia.ui;

import atlantis.hypatia.editor.EditorTab;
import atlantis.hypatia.editor.LatexTokenMaker;
import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

/** Read-only, syntax-highlighted LaTeX snippet with real line numbers (used in search popups). */
public class PreviewPane extends JPanel {

    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll = new RTextScrollPane(area, true);
    private final JLabel caption = new JLabel(" ");

    public PreviewPane(Font font) {
        super(new BorderLayout());
        EditorTab.registerLanguage();
        area.setSyntaxEditingStyle(LatexTokenMaker.SYNTAX_STYLE);
        area.setEditable(false);
        area.setHighlightCurrentLine(false);
        area.setCodeFoldingEnabled(false);
        area.setLineWrap(false);
        area.getCaret().setVisible(false);
        Theme.palette().apply(area, scroll, font);
        caption.setForeground(UIManager.getColor("Label.disabledForeground"));
        caption.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(caption, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * @param firstLine     line number (1-based) of the first line of {@code text}
     * @param highlightLine 1-based line to highlight, or 0
     */
    public void show(String caption, String text, int firstLine, int highlightLine) {
        this.caption.setText(caption == null || caption.isEmpty() ? " " : caption);
        area.setText(text == null ? "" : text);
        area.removeAllLineHighlights();
        scroll.getGutter().setLineNumberingStartIndex(Math.max(1, firstLine));
        if (highlightLine > 0) {
            try {
                area.addLineHighlight(highlightLine - firstLine, Theme.palette().flashLine());
            } catch (BadLocationException ignored) {
                // line outside the snippet
            }
        }
        area.setCaretPosition(0);
    }

    /** Wraps long lines at the window edge (off by default). */
    public void setWrap(boolean wrap) {
        area.setLineWrap(wrap);
        area.setWrapStyleWord(true);
    }

    public void clear() {
        show(" ", "", 1, 0);
    }
}
