package atlantis.hypatia.macro;

import atlantis.hypatia.editor.LatexIndenter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/** Runs macros on a text area. The whole macro is one undoable edit. */
public final class MacroRunner {

    private static final String UNIT = LatexIndenter.UNIT;
    private static final Pattern ENV = Pattern.compile("\\\\(begin|end)\\s*\\{([^}]*)\\}");

    /** Raised by a command that cannot run; the message is shown in the status bar. */
    public static final class MacroException extends Exception {
        MacroException(String message) {
            super(message);
        }
    }

    private final RSyntaxTextArea area;
    private final Document doc;

    private MacroRunner(RSyntaxTextArea area) {
        this.area = area;
        this.doc = area.getDocument();
    }

    /**
     * Runs {@code macro}. If {@code removeBefore} &gt; 0, that many characters before the caret
     * (a typed text trigger) are removed first, in the same undoable edit.
     */
    public static void run(RSyntaxTextArea area, Macro macro, int removeBefore) throws MacroException {
        MacroRunner r = new MacroRunner(area);
        area.beginAtomicEdit();
        try {
            if (removeBefore > 0) {
                int caret = area.getCaretPosition();
                r.doc.remove(caret - removeBefore, removeBefore);
            }
            for (Macro.Command c : macro.commands()) {
                switch (c) {
                    case Macro.SelectBlock s -> r.selectCurrentBlock();
                    case Macro.ClearEnvironment s -> r.clearEnvironment();
                    case Macro.Insert ins -> r.insert(ins.text());
                    case Macro.InsertLines ins -> r.insertLines(ins.lines());
                    case Macro.Execute ex -> r.execute(ex);
                }
            }
        } catch (BadLocationException e) {
            throw new MacroException("Macro " + macro.name() + " failed: " + e.getMessage());
        } catch (MacroException e) {
            throw new MacroException("Macro " + macro.name() + ": " + e.getMessage());
        } finally {
            area.endAtomicEdit();
        }
    }

    // --- EXECUTE ----------------------------------------------------------------------------

    /** Folder of the compiled classes for EXECUTE. */
    public static java.nio.file.Path classFolder() {
        return atlantis.hypatia.Settings.configDir().resolve("macros");
    }

    /**
     * Calls {@code public static void method(Document doc, int start, int end)} of a class in
     * the macros folder of the settings folder. A fresh class loader is used every time, so recompiled classes
     * take effect without restarting Hypatia.
     */
    void execute(Macro.Execute ex) throws MacroException {
        java.nio.file.Path dir = classFolder();
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new MacroException("EXECUTE: folder " + dir + " does not exist");
        }
        String what = ex.className() + "." + ex.method();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {dir.toUri().toURL()}, MacroRunner.class.getClassLoader())) {
            Class<?> cls;
            try {
                cls = Class.forName(ex.className(), true, loader);
            } catch (ClassNotFoundException e) {
                throw new MacroException("EXECUTE: class " + ex.className() + " not found in " + dir);
            }
            java.lang.reflect.Method found = null;
            for (java.lang.reflect.Method m : cls.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(ex.method()) && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && p.length == 3 && p[0].isAssignableFrom(doc.getClass())
                        && p[1] == int.class && p[2] == int.class) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new MacroException("EXECUTE: no method public static void " + ex.method()
                        + "(javax.swing.text.Document, int, int) in " + ex.className());
            }
            found.invoke(null, doc, area.getSelectionStart(), area.getSelectionEnd());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw new MacroException(what + " failed: " + c.getClass().getSimpleName()
                    + (c.getMessage() == null ? "" : ": " + c.getMessage()));
        } catch (LinkageError e) {
            throw new MacroException(what + ": cannot load class (" + e + ")");
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException e) {
            throw new MacroException(what + ": " + e);
        }
    }

    // --- SELECT CURRENT BLOCK -------------------------------------------------------------

    /**
     * Selects the lines around the caret up to (excluding) an empty line or a line indented
     * less than the caret's line. Lines indented more are included. Nothing is selected on an
     * empty line.
     */
    void selectCurrentBlock() throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        int line = root.getElementIndex(area.getSelectionStart());
        String text = lineText(line);
        if (text.isBlank()) {
            area.setCaretPosition(area.getSelectionStart());
            return;
        }
        int indent = width(leading(text));
        int first = line;
        while (first > 0 && belongs(lineText(first - 1), indent)) first--;
        int last = line;
        while (last < root.getElementCount() - 1 && belongs(lineText(last + 1), indent)) last++;
        int start = root.getElement(first).getStartOffset();
        int end = root.getElement(last).getEndOffset() - 1;
        area.select(start, end);
    }

    private static boolean belongs(String line, int indent) {
        return !line.isBlank() && width(leading(line)) >= indent;
    }

    // --- CLEAR ENVIRONMENT ----------------------------------------------------------------

    private record Env(String name, int beginStart, int beginEnd, int endStart, int endEnd) {}

    /**
     * Removes the innermost \begin{x} ... \end{x} enclosing the selection (or the caret),
     * never \begin{document}. Arguments directly after \begin{x} ([...] and {...}) go too;
     * a line left empty is removed. The content is de-indented by one level (not past the
     * \begin line) and selected as whole lines.
     */
    void clearEnvironment() throws BadLocationException, MacroException {
        String text = doc.getText(0, doc.getLength());
        int s = area.getSelectionStart();
        int e = area.getSelectionEnd();
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;

        Env env = null;
        for (Env c : environments(text)) {
            if (c.name().equals("document")) continue;
            if (c.beginStart() <= s && c.endEnd() >= e && (env == null || c.beginStart() > env.beginStart())) {
                env = c;
            }
        }
        if (env == null) throw new MacroException("no environment around the cursor");

        Element root = doc.getDefaultRootElement();
        int beginLine = root.getElementIndex(env.beginStart());
        int endLine = root.getElementIndex(env.endStart());
        String baseIndent = leading(lineText(beginLine));

        // Ends of the \begin part: the command plus its [...] / {...} arguments.
        int beginEnd = skipArguments(text, env.beginEnd());

        if (beginLine == endLine) {
            // Inline environment: keep only the content, selected.
            String content = text.substring(beginEnd, env.endStart());
            doc.remove(env.beginStart(), env.endEnd() - env.beginStart());
            doc.insertString(env.beginStart(), content, null);
            area.select(env.beginStart(), env.beginStart() + content.length());
            return;
        }

        // Work from the bottom up so earlier offsets stay valid.
        removeOnLine(endLine, env.endStart(), env.endEnd());
        for (int l = endLine - 1; l > beginLine; l--) dedent(l, baseIndent.length());
        boolean beginLineRemoved = removeOnLine(beginLine, env.beginStart(), beginEnd);

        // Select the former content as whole lines (including what was left on the \begin line).
        int beginLineStart = root.getElement(beginLine).getStartOffset();
        boolean textBeforeBegin = !text.substring(beginLineStart, env.beginStart()).isBlank();
        int firstContent = (beginLineRemoved || !textBeforeBegin) ? beginLine : beginLine + 1;
        int lastContent = endLine - 1 - (beginLineRemoved ? 1 : 0);
        if (lastContent < firstContent) {
            area.setCaretPosition(root.getElement(Math.min(firstContent, root.getElementCount() - 1)).getStartOffset());
            return;
        }
        area.select(root.getElement(firstContent).getStartOffset(), root.getElement(lastContent).getEndOffset() - 1);
    }

    /** Removes [from,to) on a line; removes the whole line if nothing but whitespace is left. Returns true then. */
    private boolean removeOnLine(int line, int from, int to) throws BadLocationException {
        Element el = doc.getDefaultRootElement().getElement(line);
        int ls = el.getStartOffset();
        int le = el.getEndOffset() - 1; // before the newline
        String rest = doc.getText(ls, from - ls) + doc.getText(to, le - to);
        if (rest.isBlank()) {
            boolean lastLine = el.getEndOffset() > doc.getLength();
            if (lastLine && ls > 0) {
                doc.remove(ls - 1, le - ls + 1); // also the newline before it
            } else {
                doc.remove(ls, Math.min(el.getEndOffset(), doc.getLength()) - ls);
            }
            return true;
        }
        doc.remove(from, to - from);
        return false;
    }

    /** Removes up to one indentation level, but keeps at least {@code min} columns. */
    private void dedent(int line, int min) throws BadLocationException {
        String text = lineText(line);
        String lead = leading(text);
        if (text.isBlank()) return;
        int remove;
        if (lead.length() > min && lead.charAt(min) == '\t') {
            remove = 1;
        } else {
            remove = 0;
            while (remove < UNIT.length() && min + remove < lead.length() && lead.charAt(min + remove) == ' ') remove++;
        }
        if (remove > 0) {
            doc.remove(doc.getDefaultRootElement().getElement(line).getStartOffset() + min, remove);
        }
    }

    /** Skips [..] and {..} groups (and spaces between them) following position {@code i} on the same line. */
    private static int skipArguments(String text, int i) {
        int j = i;
        while (true) {
            int k = j;
            while (k < text.length() && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) k++;
            if (k >= text.length()) return j;
            char open = text.charAt(k);
            if (open != '[' && open != '{') return j;
            char close = open == '[' ? ']' : '}';
            int depth = 0;
            int m = k;
            for (; m < text.length(); m++) {
                char c = text.charAt(m);
                if (c == '\n') return j;
                if (c == '\\') { m++; continue; }
                if (c == open) depth++;
                else if (c == close && --depth == 0) break;
            }
            if (m >= text.length()) return j;
            j = m + 1;
        }
    }

    /** All matched \begin/\end pairs, ignoring commented-out ones. */
    private static List<Env> environments(String text) {
        String code = blankComments(text);
        List<Env> out = new ArrayList<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        Matcher m = ENV.matcher(code);
        while (m.find()) {
            String name = m.group(2).trim();
            if (m.group(1).equals("begin")) {
                stack.push(new Object[] {name, m.start(), m.end()});
            } else {
                // pop to the matching \begin, tolerating unbalanced input
                for (Object[] b : stack) {
                    if (b[0].equals(name)) {
                        Object[] top;
                        do {
                            top = stack.pop();
                        } while (top != b);
                        out.add(new Env(name, (int) b[1], (int) b[2], m.start(), m.end()));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Replaces % comments by spaces (offsets stay the same). */
    private static String blankComments(String text) {
        char[] c = text.toCharArray();
        boolean comment = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\n') {
                comment = false;
            } else if (comment) {
                c[i] = ' ';
            } else if (c[i] == '\\') {
                i++;
            } else if (c[i] == '%') {
                comment = true;
                c[i] = ' ';
            }
        }
        return new String(c);
    }

    // --- INSERT ---------------------------------------------------------------------------

    /** Inserts one line of text; selected text goes to >CURSOR<, otherwise it is replaced. */
    void insert(String template) throws BadLocationException {
        int s = area.getSelectionStart();
        int e = area.getSelectionEnd();
        String selected = doc.getText(s, e - s);
        int marker = template.indexOf(Macro.CURSOR);
        String t = template.replace(Macro.CURSOR, "");
        doc.remove(s, e - s);
        if (marker < 0) {
            doc.insertString(s, t, null);
            area.setCaretPosition(s + t.length());
        } else {
            String text = t.substring(0, marker) + selected + t.substring(marker);
            doc.insertString(s, text, null);
            area.setCaretPosition(s + marker + selected.length());
        }
    }

    // --- INSERT LINES ---------------------------------------------------------------------

    /**
     * Inserts lines indented like the current line. At the start of a line (or in its
     * leading whitespace) every inserted line gets that indentation; after other text the
     * first line continues the current line. On an empty line the indentation comes from
     * the previous line, one level deeper after an open \begin. Selected text goes to
     * >CURSOR< (re-indented to the marker's line), otherwise it is replaced.
     */
    void insertLines(List<String> template) throws BadLocationException {
        Element root = doc.getDefaultRootElement();
        int s = area.getSelectionStart();
        int e = area.getSelectionEnd();
        String selected = doc.getText(s, e - s);
        int line = root.getElementIndex(s);
        int lineStart = root.getElement(line).getStartOffset();
        String lineText = lineText(line);
        String before = doc.getText(lineStart, s - lineStart);

        boolean lineMode = before.isBlank();
        boolean pushDown = false; // insert above an existing line, which moves down
        String base;
        int start;
        if (lineMode) {
            start = lineStart;
            if (!lineText.isBlank()) {
                base = leading(lineText);
                if (s == e) {
                    // caret in the indentation of a line with text: insert above it
                    e = lineStart;
                    pushDown = true;
                }
            } else {
                base = leading(lineText).isEmpty() ? indentFromPrevious(line) : leading(lineText);
                if (s == e) e = root.getElement(line).getEndOffset() - 1; // replace the blank line
            }
        } else {
            start = s;
            base = leading(lineText);
        }

        // Build the text; find the marker.
        StringBuilder out = new StringBuilder();
        int markerPos = -1;
        String markerIndent = base;
        for (int i = 0; i < template.size(); i++) {
            String tl = template.get(i);
            if (i > 0) out.append('\n');
            String prefix = (i == 0 && !lineMode) || tl.isBlank() ? "" : base;
            int m = tl.indexOf(Macro.CURSOR);
            if (m >= 0 && markerPos < 0) {
                markerPos = out.length() + prefix.length() + m;
                markerIndent = base + leading(tl);
            }
            out.append(tl.isBlank() ? "" : prefix + tl.replace(Macro.CURSOR, ""));
        }
        if (pushDown) out.append('\n');
        String text = out.toString();

        int caret;
        if (markerPos < 0) {
            caret = text.length() - (pushDown ? 1 : 0);
        } else if (selected.isEmpty()) {
            caret = markerPos;
        } else {
            String moved = reindent(selected, markerIndent, s == lineStart || before.isBlank());
            text = text.substring(0, markerPos) + moved + text.substring(markerPos);
            caret = markerPos + moved.length();
        }
        doc.remove(start, e - start);
        doc.insertString(start, text, null);
        area.setCaretPosition(start + caret);
    }

    /**
     * Strips the selection's common indentation and indents its lines (after the first,
     * which goes at the marker) with {@code indent}.
     */
    private static String reindent(String selected, String indent, boolean firstLineComplete) {
        String[] lines = selected.split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (int i = firstLineComplete ? 0 : 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) common = Math.min(common, leading(lines[i]).length());
        }
        if (common == Integer.MAX_VALUE) common = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (i > 0) sb.append('\n');
            if (l.isBlank()) continue;
            if (i == 0) {
                sb.append(firstLineComplete ? l.substring(Math.min(common, leading(l).length())) : l.stripLeading());
            } else {
                sb.append(indent).append(l.substring(Math.min(common, leading(l).length())));
            }
        }
        return sb.toString();
    }

    /** Indentation for an empty line: the previous non-empty line's, one level deeper after an open \begin. */
    private String indentFromPrevious(int line) throws BadLocationException {
        for (int l = line - 1; l >= 0; l--) {
            String t = lineText(l);
            if (t.isBlank()) continue;
            String code = t.replaceAll("(?<!\\\\)%.*", "");
            int net = 0;
            Matcher m = ENV.matcher(code);
            while (m.find()) net += m.group(1).equals("begin") ? 1 : -1;
            return leading(t) + (net > 0 ? UNIT : "");
        }
        return "";
    }

    // --- helpers ----------------------------------------------------------------------------

    private String lineText(int line) throws BadLocationException {
        Element el = doc.getDefaultRootElement().getElement(line);
        int end = Math.min(el.getEndOffset(), doc.getLength() + 1) - 1;
        return doc.getText(el.getStartOffset(), Math.max(0, end - el.getStartOffset()));
    }

    static String leading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(0, i);
    }

    private static int width(String ws) {
        int w = 0;
        for (char c : ws.toCharArray()) w = c == '\t' ? (w / UNIT.length() + 1) * UNIT.length() : w + 1;
        return w;
    }
}
