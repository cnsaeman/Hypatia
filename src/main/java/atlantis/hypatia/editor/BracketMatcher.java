package atlantis.hypatia.editor;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.LayeredHighlighter;
import javax.swing.text.Position;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Highlights matching brackets: an opening {, [ or ( right of the cursor, or a closing one
 * left of it, together with its partner. Each kind is matched on its own, so the half-open
 * intervals [0,1) of mathematics do not confuse the braces. Escaped brackets (\{ ...) and
 * comments are skipped. An unmatched brace is marked in the error colour.
 */
public final class BracketMatcher {

    private static final int MAX_SCAN = 200_000;

    private final RSyntaxTextArea area;
    private final Object[] tags = new Object[4];
    private final Painter match = new Painter(false);
    private final Painter unmatched = new Painter(true);

    private BracketMatcher(RSyntaxTextArea area) {
        this.area = area;
    }

    public static void install(RSyntaxTextArea area) {
        area.setBracketMatchingEnabled(false); // replaced by this
        BracketMatcher m = new BracketMatcher(area);
        area.addCaretListener(e -> SwingUtilities.invokeLater(m::update)); // after the document change
        // An edit next to a marked bracket would stretch its mark over the new text until the
        // caret update comes; drop the marks right away instead.
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { m.clear(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { m.clear(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        area.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { m.clear(); }
            @Override public void focusGained(java.awt.event.FocusEvent e) { m.update(); }
        });
    }

    private void clear() {
        Highlighter h = area.getHighlighter();
        for (int i = 0; i < tags.length; i++) {
            if (tags[i] != null) h.removeHighlight(tags[i]);
            tags[i] = null;
        }
    }

    private void update() {
        clear();
        if (!area.isFocusOwner()) return;
        String text = area.getText();
        int caret = area.getCaretPosition();
        int slot = 0;
        // opening bracket right of the cursor
        if (caret < text.length() && "{[(".indexOf(text.charAt(caret)) >= 0 && !escaped(text, caret)) {
            slot = mark(text, caret, slot);
        }
        // closing bracket left of the cursor
        if (caret > 0 && "}])".indexOf(text.charAt(caret - 1)) >= 0 && !escaped(text, caret - 1)) {
            mark(text, caret - 1, slot);
        }
    }

    private int mark(String text, int at, int slot) {
        int partner = partner(text, at);
        Highlighter h = area.getHighlighter();
        try {
            if (partner >= 0) {
                tags[slot++] = h.addHighlight(at, at + 1, match);
                tags[slot++] = h.addHighlight(partner, partner + 1, match);
            } else if (partner == -1 && (text.charAt(at) == '{' || text.charAt(at) == '}')) {
                tags[slot++] = h.addHighlight(at, at + 1, unmatched);
            }
        } catch (BadLocationException ignored) {
            // document changed meanwhile
        }
        return slot;
    }

    /** Offset of the partner, -1 if there is none, -2 if the search gave up. */
    static int partner(String text, int at) {
        char c = text.charAt(at);
        char open = switch (c) { case '}', '{' -> '{'; case ']', '[' -> '['; default -> '('; };
        char close = switch (open) { case '{' -> '}'; case '[' -> ']'; default -> ')'; };
        boolean inComment = commentStart(text, lineStart(text, at)) <= at;
        int depth = 0;
        if (c == open) {
            int lineEnd = lineEnd(text, at);
            int cs = inComment ? lineEnd : commentStart(text, lineStart(text, at));
            for (int i = at, n = 0; i < text.length(); i++, n++) {
                if (n > MAX_SCAN) return -2;
                if (i == lineEnd + 1) { // next line
                    lineEnd = lineEnd(text, i);
                    cs = inComment ? lineEnd : commentStart(text, i);
                }
                if (i >= cs) { // rest of the line is a comment
                    i = lineEnd;
                    continue;
                }
                char d = text.charAt(i);
                if ((d == open || d == close) && !escaped(text, i)) {
                    depth += d == open ? 1 : -1;
                    if (depth == 0) return i;
                }
            }
        } else {
            int ls = lineStart(text, at);
            int cs = inComment ? Integer.MAX_VALUE : commentStart(text, ls);
            for (int i = at, n = 0; i >= 0; i--, n++) {
                if (n > MAX_SCAN) return -2;
                if (i < ls) { // previous line
                    ls = lineStart(text, i);
                    cs = inComment ? Integer.MAX_VALUE : commentStart(text, ls);
                }
                if (i >= cs) continue;
                char d = text.charAt(i);
                if ((d == open || d == close) && !escaped(text, i)) {
                    depth += d == close ? 1 : -1;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /** True if the character at {@code i} is preceded by an odd number of backslashes. */
    private static boolean escaped(String text, int i) {
        int n = 0;
        for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) n++;
        return n % 2 == 1;
    }

    private static int lineStart(String text, int i) {
        return text.lastIndexOf('\n', i - 1) + 1;
    }

    private static int lineEnd(String text, int i) {
        int e = text.indexOf('\n', i);
        return e < 0 ? text.length() : e;
    }

    /** Offset of the first unescaped % in the line starting at {@code ls}, or its end. */
    private static int commentStart(String text, int ls) {
        int end = lineEnd(text, ls);
        for (int i = ls; i < end; i++) {
            if (text.charAt(i) == '%' && !escaped(text, i)) return i;
        }
        return end;
    }

    /** A rounded box around the bracket, in the theme's bracket-match colour. */
    private final class Painter extends LayeredHighlighter.LayerPainter {
        private final boolean error;

        Painter(boolean error) {
            this.error = error;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            // not used: LayeredHighlighter calls paintLayer
        }

        @Override
        public Shape paintLayer(Graphics g, int p0, int p1, Shape bounds, JTextComponent c, View view) {
            try {
                Shape s = view.modelToView(p0, Position.Bias.Forward, p1, Position.Bias.Backward, bounds);
                Rectangle r = s.getBounds();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // fill: the theme's bracket colour; border: the caret colour, which stands out in both themes
                Color fill = error ? withAlpha(errorColour(), 110) : area.getMatchedBracketBGColor();
                Color border = error ? errorColour() : withAlpha(area.getCaretColor(), 200);
                g2.setColor(fill);
                g2.fillRoundRect(r.x - 1, r.y, r.width + 1, r.height - 1, 4, 4);
                g2.setColor(border);
                g2.drawRoundRect(r.x - 1, r.y, r.width + 1, r.height - 1, 4, 4);
                g2.dispose();
                return r;
            } catch (BadLocationException e) {
                return null;
            }
        }

        private static Color withAlpha(Color c, int alpha) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
        }

        private Color errorColour() {
            Color c = UIManager.getColor("Component.error.focusedBorderColor");
            return c != null ? c : new Color(0xE0, 0x50, 0x50);
        }
    }
}
