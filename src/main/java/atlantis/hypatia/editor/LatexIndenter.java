package atlantis.hypatia.editor;

import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Environment-based indentation: every \begin{...} … \end{...} block is indented
 * by {@link #UNIT} relative to its \begin line.
 *
 * Entry points:
 *   - Enter after an unclosed \begin{...} indents the new line one level deeper (and, if
 *     {@link #AUTO_CLOSE} is on, adds the matching \end{...} below),
 *   - typing the closing brace of \end{...} aligns that line with its \begin,
 *   - Tab left of a line's proper indentation moves there (indenting the line if needed),
 *   - {@link #reindent} re-indents the selected lines (or the whole document).
 *
 * Verbatim-like environments are never touched. Whether \begin{document}
 * indents its body is a setting (client property {@link #INDENT_DOCUMENT}).
 */
public final class LatexIndenter {

    public static final String UNIT = "    ";
    /** Client property (Boolean) on the text area: indent inside \begin{document}. */
    public static final String INDENT_DOCUMENT = "hypatia.indentDocument";
    /** Client property (Boolean) on the text area: automatic indentation on/off. */
    public static final String AUTO_INDENT = "hypatia.autoIndentEnvironments";
    /** Client property (Boolean) on the text area: Enter after \begin{x} adds \end{x}. */
    public static final String AUTO_CLOSE = "hypatia.autoCloseEnvironments";

    private static final Pattern ENV = Pattern.compile("\\\\(begin|end)\\s*\\{([^}]*)\\}");
    private static final Pattern LEADING_END = Pattern.compile("^\\s*\\\\end\\s*\\{([^}]*)\\}");
    /** \begin{name} at the end of the text before the caret, optionally with arguments: [h], {cc}. */
    private static final Pattern BEGIN_AT_END = Pattern.compile(
            "\\\\begin\\s*\\{([^{}]+)\\}(?:\\s*(?:\\[[^\\]]*\\]|\\{[^{}]*\\}))*\\s*$");
    private static final Set<String> VERBATIM = Set.of(
            "verbatim", "verbatim*", "Verbatim", "lstlisting", "minted", "alltt", "comment");

    private LatexIndenter() {}

    // --- Enter -------------------------------------------------------------------------

    /** Used by {@link LatexTokenMaker#getInsertBreakAction()} (so Enter is still recorded in macros). */
    static final Action INSERT_BREAK = new AbstractAction("hypatia-insert-break") {
        @Override
        public void actionPerformed(ActionEvent e) {
            Object src = e.getSource();
            if (!(src instanceof RSyntaxTextArea)) {
                src = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
            }
            if (src instanceof RSyntaxTextArea area) insertBreak(area);
        }
    };

    private static void insertBreak(RSyntaxTextArea area) {
        try {
            int caret = area.getCaretPosition();
            int lineIndex = area.getLineOfOffset(caret);
            int lineStart = area.getLineStartOffset(lineIndex);
            int lineEnd = area.getLineEndOffset(lineIndex);
            String line = area.getText(lineStart, lineEnd - lineStart);
            if (line.endsWith("\n")) line = line.substring(0, line.length() - 1);
            int col = caret - lineStart;
            String before = line.substring(0, Math.min(col, line.length()));
            String after = line.substring(Math.min(col, line.length()));

            String indent = leadingWhitespace(line);
            if (indent.length() > before.length()) indent = indent.substring(0, before.length());
            String insert = "\n" + indent;
            int caretAfter = -1;
            String closing = autoClose(area) && after.isBlank() ? environmentToClose(area, before) : null;
            if (closing != null) {
                // \begin{x} + Enter: add \end{x} below and put the caret on the line between.
                String inner = enabled(area) && indents(closing, indentDocument(area)) && !VERBATIM.contains(closing)
                        ? indent + UNIT : indent;
                insert = "\n" + inner + "\n" + indent + "\\end{" + closing + "}";
                caretAfter = 1 + inner.length();
            } else if (enabled(area) && netOpenEnvironments(before, indentDocument(area)) > 0) {
                if (after.stripLeading().startsWith("\\end")) {
                    // Enter between \begin{x} and \end{x}: open an indented line between them.
                    insert = "\n" + indent + UNIT + "\n" + indent;
                    caretAfter = 1 + indent.length() + UNIT.length();
                } else {
                    insert = "\n" + indent + UNIT;
                }
            }
            // Drop whitespace that would otherwise sit between the new indent and the text.
            int skip = after.length() - after.stripLeading().length();
            area.beginAtomicEdit();
            try {
                if (skip > 0) area.getDocument().remove(caret, skip);
                area.getDocument().insertString(caret, insert, null);
                if (caretAfter >= 0) area.setCaretPosition(caret + caretAfter);
                // A line that held only whitespace is left empty.
                if (before.isBlank() && !before.isEmpty()) {
                    int pos = area.getCaretPosition();
                    area.getDocument().remove(lineStart, before.length());
                    if (caretAfter >= 0) area.setCaretPosition(pos - before.length());
                }
            } finally {
                area.endAtomicEdit();
            }
        } catch (BadLocationException e) {
            area.replaceSelection("\n");
        }
    }

    /**
     * The environment to close after Enter: the text before the caret ends with \begin{name}
     * (and arguments), and the document has more \begin{name} than \end{name}. Else null.
     */
    private static String environmentToClose(RSyntaxTextArea area, String before) {
        Matcher m = BEGIN_AT_END.matcher(stripComment(before));
        if (!m.find()) return null;
        String name = m.group(1).trim();
        if (name.isEmpty()) return null;
        String code = blankComments(area.getText());
        Pattern env = Pattern.compile("\\\\(begin|end)\\s*\\{\\s*" + Pattern.quote(name) + "\\s*\\}");
        Matcher e = env.matcher(code);
        int balance = 0;
        while (e.find()) balance += e.group(1).equals("begin") ? 1 : -1;
        return balance > 0 ? name : null;
    }

    /** \begin minus \end on a (comment-stripped) piece of a line, counting only indenting environments. */
    private static int netOpenEnvironments(String text, boolean indentDocument) {
        int net = 0;
        Matcher m = ENV.matcher(stripComment(text));
        while (m.find()) {
            String name = m.group(2).trim();
            if (!indents(name, indentDocument) || VERBATIM.contains(name)) continue;
            net += m.group(1).equals("begin") ? 1 : -1;
        }
        return net;
    }

    // --- \end{...} typed ---------------------------------------------------------------------

    /** Installs the "align \end{...} with its \begin" and the Tab behaviour. */
    public static void install(RSyntaxTextArea area) {
        javax.swing.KeyStroke tab = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        Object name = area.getInputMap().get(tab);
        Action plainTab = name == null ? null : area.getActionMap().get(name);
        area.getInputMap().put(tab, "hypatia.smartTab");
        area.getActionMap().put("hypatia.smartTab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!area.isEditable() || !smartTab(area)) {
                    if (plainTab != null) plainTab.actionPerformed(e); else area.replaceSelection("\t");
                }
            }
        });
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '}' && enabled(area)) {
                    SwingUtilities.invokeLater(() -> alignEndLine(area));
                }
            }
        });
    }

    private static void alignEndLine(RSyntaxTextArea area) {
        try {
            int caret = area.getCaretPosition();
            int lineIndex = area.getLineOfOffset(caret);
            int lineStart = area.getLineStartOffset(lineIndex);
            String before = area.getText(lineStart, caret - lineStart);
            Matcher m = LEADING_END.matcher(before);
            if (!m.find() || m.end() != before.length()) return; // caret right after "\end{name}"
            String name = m.group(1).trim();
            if (!indents(name, indentDocument(area)) || VERBATIM.contains(name)) return;
            String target = indentOfMatchingBegin(area, lineIndex);
            if (target == null) return;
            String current = leadingWhitespace(before);
            if (current.equals(target)) return;
            area.beginAtomicEdit();
            try {
                area.getDocument().remove(lineStart, current.length());
                area.getDocument().insertString(lineStart, target, null);
            } finally {
                area.endAtomicEdit();
            }
        } catch (BadLocationException ignored) {
            // the document changed underneath us; nothing to do
        }
    }

    /** Indentation of the \begin that the \end at the start of {@code endLine} closes, or null. */
    private static String indentOfMatchingBegin(RSyntaxTextArea area, int endLine) throws BadLocationException {
        Element root = area.getDocument().getDefaultRootElement();
        int depth = 0;
        for (int i = endLine - 1; i >= 0; i--) {
            String text = lineText(area, root, i);
            Matcher m = ENV.matcher(stripComment(text));
            // Walk this line's \begin/\end from right to left.
            Deque<Boolean> events = new ArrayDeque<>();
            while (m.find()) {
                if (!VERBATIM.contains(m.group(2).trim())) events.push(m.group(1).equals("begin"));
            }
            for (boolean isBegin : events) {
                if (!isBegin) {
                    depth++;
                } else if (depth == 0) {
                    return leadingWhitespace(text);
                } else {
                    depth--;
                }
            }
        }
        return null;
    }

    // --- Tab -----------------------------------------------------------------------------------

    /**
     * Tab with the caret in a line's leading whitespace, left of the line's proper indentation:
     * moves the caret there, indenting the line first if it is indented less. Returns false
     * (plain Tab) otherwise: selection, caret in the text or already far enough right.
     */
    private static boolean smartTab(RSyntaxTextArea area) {
        if (!enabled(area) || area.getSelectionStart() != area.getSelectionEnd()) return false;
        try {
            int caret = area.getCaretPosition();
            Element root = area.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(caret);
            int lineStart = root.getElement(line).getStartOffset();
            String text = lineText(area, root, line);
            String current = leadingWhitespace(text);
            int col = caret - lineStart;
            if (col > current.length()) return false; // caret in the text
            String target = canonicalIndent(area, line);
            if (target == null || col >= target.length()) return false;
            if (current.length() < target.length()) {
                area.beginAtomicEdit();
                try {
                    area.getDocument().remove(lineStart, current.length());
                    area.getDocument().insertString(lineStart, target, null);
                } finally {
                    area.endAtomicEdit();
                }
            }
            area.setCaretPosition(lineStart + target.length());
            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }

    /** The indentation {@link #reindent} would give {@code line}, or null inside a verbatim environment. */
    static String canonicalIndent(RSyntaxTextArea area, int line) throws BadLocationException {
        boolean indentDocument = indentDocument(area);
        Element root = area.getDocument().getDefaultRootElement();
        int depth = 0;
        String verbatim = null;
        for (int i = 0; i < line; i++) {
            String code = stripComment(lineText(area, root, i));
            if (verbatim != null) {
                if (code.matches(".*\\\\end\\s*\\{" + Pattern.quote(verbatim) + "\\}.*")) verbatim = null;
                continue;
            }
            depth = Math.max(0, depth + netOpenEnvironments(code, indentDocument));
            Matcher vb = ENV.matcher(code);
            while (vb.find()) {
                String n = vb.group(2).trim();
                if (VERBATIM.contains(n)) verbatim = vb.group(1).equals("begin") ? n : null;
            }
        }
        if (verbatim != null) return null;
        int leadingEnds = 0;
        String rest = stripComment(lineText(area, root, line));
        Matcher le;
        while ((le = LEADING_END.matcher(rest)).find()) {
            String n = le.group(1).trim();
            if (indents(n, indentDocument) && !VERBATIM.contains(n)) leadingEnds++;
            rest = rest.substring(le.end());
        }
        return UNIT.repeat(Math.max(0, depth - leadingEnds));
    }

    // --- re-indent -------------------------------------------------------------------------

    /**
     * Re-indents the lines touched by the selection, or every line if nothing is
     * selected, from the environment nesting. Comment lines are indented too;
     * lines inside verbatim-like environments are left alone.
     */
    public static void reindent(RSyntaxTextArea area) {
        boolean indentDocument = indentDocument(area);
        Element root = area.getDocument().getDefaultRootElement();
        int lines = root.getElementCount();
        int firstLine = 0;
        int lastLine = lines - 1;
        if (area.getSelectionStart() != area.getSelectionEnd()) {
            firstLine = root.getElementIndex(area.getSelectionStart());
            lastLine = root.getElementIndex(Math.max(area.getSelectionStart(), area.getSelectionEnd() - 1));
        }

        int caretLine = root.getElementIndex(area.getCaretPosition());
        int caretFromTextStart = 0;
        StringBuilder out = new StringBuilder();
        try {
            int depth = 0;
            String verbatim = null;
            for (int i = 0; i <= lastLine; i++) {
                String text = lineText(area, root, i);
                String code = stripComment(text);
                String trimmed = text.stripLeading();
                String newText = text;

                if (verbatim != null) {
                    if (code.matches(".*\\\\end\\s*\\{" + Pattern.quote(verbatim) + "\\}.*")) {
                        verbatim = null;
                        newText = UNIT.repeat(depth) + trimmed;
                    }
                } else {
                    int leadingEnds = 0;
                    String rest = code;
                    Matcher le;
                    while ((le = LEADING_END.matcher(rest)).find()) {
                        String n = le.group(1).trim();
                        if (indents(n, indentDocument) && !VERBATIM.contains(n)) leadingEnds++;
                        rest = rest.substring(le.end());
                    }
                    int level = Math.max(0, depth - leadingEnds);
                    newText = trimmed.isEmpty() ? "" : UNIT.repeat(level) + trimmed;
                    depth = Math.max(0, depth + netOpenEnvironments(code, indentDocument));
                    Matcher vb = ENV.matcher(code);
                    while (vb.find()) {
                        String n = vb.group(2).trim();
                        if (VERBATIM.contains(n)) verbatim = vb.group(1).equals("begin") ? n : null;
                    }
                }

                if (i >= firstLine) {
                    if (i == caretLine) {
                        int col = area.getCaretPosition() - root.getElement(i).getStartOffset();
                        int oldIndent = text.length() - trimmed.length();
                        int newIndent = newText.length() - trimmed.length();
                        caretFromTextStart = Math.max(newIndent, col - oldIndent + newIndent);
                    }
                    out.append(newText);
                    if (i < lines - 1) out.append('\n');
                }
            }
        } catch (BadLocationException e) {
            return;
        }

        int start = root.getElement(firstLine).getStartOffset();
        Element last = root.getElement(lastLine);
        int end = lastLine < lines - 1 ? last.getEndOffset() : last.getEndOffset() - 1;
        try {
            if (area.getText(start, end - start).equals(out.toString())) return;
        } catch (BadLocationException e) {
            return;
        }
        area.beginAtomicEdit();
        try {
            area.replaceRange(out.toString(), start, end);
        } finally {
            area.endAtomicEdit();
        }
        if (caretLine >= firstLine && caretLine <= lastLine) {
            Element cl = root.getElement(caretLine);
            area.setCaretPosition(Math.min(cl.getStartOffset() + caretFromTextStart, cl.getEndOffset() - 1));
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private static boolean indents(String env, boolean indentDocument) {
        return indentDocument || !env.equals("document");
    }

    private static boolean enabled(JTextComponent c) {
        return !Boolean.FALSE.equals(c.getClientProperty(AUTO_INDENT));
    }

    private static boolean autoClose(JTextComponent c) {
        return !Boolean.FALSE.equals(c.getClientProperty(AUTO_CLOSE));
    }

    /** Replaces % comments by spaces, keeping offsets. */
    private static String blankComments(String text) {
        char[] c = text.toCharArray();
        boolean comment = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\n') comment = false;
            else if (comment) c[i] = ' ';
            else if (c[i] == '\\') i++;
            else if (c[i] == '%') {
                comment = true;
                c[i] = ' ';
            }
        }
        return new String(c);
    }

    private static boolean indentDocument(JTextComponent c) {
        return Boolean.TRUE.equals(c.getClientProperty(INDENT_DOCUMENT));
    }

    private static String lineText(RSyntaxTextArea area, Element root, int i) throws BadLocationException {
        Element e = root.getElement(i);
        String t = area.getText(e.getStartOffset(), e.getEndOffset() - e.getStartOffset());
        return t.endsWith("\n") ? t.substring(0, t.length() - 1) : t;
    }

    private static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    /** Removes a % comment (an unescaped %) from a line. */
    static String stripComment(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++; // skip escaped character, e.g. \%
            } else if (c == '%') {
                return s.substring(0, i);
            }
        }
        return s;
    }
}
