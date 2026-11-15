package atlantis.hypatia.editor;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.awt.event.KeyEvent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import atlantis.hypatia.ui.Theme;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;
import org.fife.ui.rtextarea.RTextScrollPane;

/** One open file: a syntax-highlighting text area with line numbers. */
public class EditorTab extends JPanel {

    private static boolean registered;

    static {
        registerLanguage();
    }

    /** Registers Hypatia's LaTeX tokenizer and folding with RSyntaxTextArea (idempotent). */
    public static synchronized void registerLanguage() {
        if (registered) return;
        registered = true;
        ((AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance())
                .putMapping(LatexTokenMaker.SYNTAX_STYLE, LatexTokenMaker.class.getName());
        FoldParserManager.get().addFoldParserMapping(LatexTokenMaker.SYNTAX_STYLE, new LatexFoldParser());
    }

    private final RSyntaxTextArea area = new HypatiaTextArea(30, 80);
    private final RTextScrollPane scroll = new RTextScrollPane(area, true);
    private final List<Object> problemHighlights = new ArrayList<>();
    private Path file;
    private Charset charset = StandardCharsets.UTF_8;
    private FileTime lastModified;
    private boolean dirty;
    private Runnable onStateChange = () -> {};

    public EditorTab() {
        super(new BorderLayout());
        area.setSyntaxEditingStyle(LatexTokenMaker.SYNTAX_STYLE);
        area.setCodeFoldingEnabled(true);
        area.setAntiAliasingEnabled(true);
        area.setTabSize(4);
        area.setTabsEmulated(true); // Tab inserts 4 spaces
        area.setAutoIndentEnabled(true);
        LatexIndenter.install(area);
        area.setAnimateBracketMatching(false);
        BracketMatcher.install(area);
        installSmartHome();
        area.setHighlightCurrentLine(true);
        area.setClearWhitespaceLinesEnabled(false);
        area.setMarkOccurrences(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        // New files get Unix line ends on every system (loaded files keep theirs).
        area.getDocument().putProperty(javax.swing.text.DefaultEditorKit.EndOfLineStringProperty, "\n");
        // RSTA binds Ctrl+Shift+R/S (Cmd+Shift+R/S on a Mac) to its own macro recorder; free them
        int menuMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                | java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), "none");
        area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask), "none");
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);

        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
    }

    /**
     * Home: on a wrapped continuation row, first to the start of that row; then to the first
     * non-blank character of the line; then to column 1 (and back to the first non-blank
     * character). Shift+Home selects.
     */
    private void installSmartHome() {
        for (boolean select : new boolean[] {false, true}) {
            String name = select ? "hypatia.smartHomeSelect" : "hypatia.smartHome";
            area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME,
                    select ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0), name);
            area.getActionMap().put(name, new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    try {
                        int caret = area.getCaretPosition();
                        int line = area.getLineOfOffset(caret);
                        int start = area.getLineStartOffset(line);
                        int end = area.getLineEndOffset(line);
                        String text = area.getText(start, end - start);
                        int indent = 0;
                        while (indent < text.length() && (text.charAt(indent) == ' ' || text.charAt(indent) == '\t')) {
                            indent++;
                        }
                        int indentEnd = start + indent;
                        int rowStart = area.getLineWrap()
                                ? javax.swing.text.Utilities.getRowStart(area, caret) : start;
                        int target;
                        if (rowStart > start && caret != rowStart && rowStart > indentEnd) {
                            target = rowStart; // start of the wrapped row
                        } else if (caret != indentEnd) {
                            target = indentEnd;
                        } else {
                            target = start;
                        }
                        if (select) area.moveCaretPosition(target); else area.setCaretPosition(target);
                    } catch (javax.swing.text.BadLocationException ex) {
                        // caret outside the document: ignore
                    }
                }
            });
        }
    }

    public RSyntaxTextArea area() { return area; }
    public RTextScrollPane scroll() { return scroll; }
    public Path file() { return file; }
    public boolean isDirty() { return dirty; }

    public void setOnStateChange(Runnable r) {
        onStateChange = r;
    }

    public String title() {
        return file == null ? "Untitled" : file.getFileName().toString();
    }

    public void applyLook(EditorPalette palette, Font font, boolean softWrap) {
        palette.apply(area, scroll, font);
        area.setLineWrap(softWrap);
    }

    private atlantis.hypatia.spell.SpellChecker spellChecker;

    /** Adds spell checking as you type, with suggestions in the right-click menu. */
    public void installSpellChecker(atlantis.hypatia.spell.SpellChecker checker) {
        spellChecker = checker;
        checker.install();
        ((HypatiaTextArea) area).setPopupExtras(checker::popupItems);
        ((HypatiaTextArea) area).addToolTipExtra(checker::tooltipAt);
    }

    public atlantis.hypatia.spell.SpellChecker spellChecker() {
        return spellChecker;
    }

    // --- structure sidebar ---------------------------------------------------------------

    private atlantis.hypatia.ui.OutlinePanel outline;
    private atlantis.hypatia.ui.QuietSplitPane split;
    private int outlineWidth = 240;

    /** Puts the structure sidebar to the left of the editor. */
    public void installOutline(atlantis.hypatia.ui.OutlinePanel panel, int width, boolean visible,
                               java.util.function.IntConsumer onResize) {
        outline = panel;
        outlineWidth = width;
        remove(scroll);
        split = new atlantis.hypatia.ui.QuietSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, panel, scroll);
        split.setBorder(null);
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        split.setDividerLocation(width);
        split.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (outline.isVisible() && split.getDividerLocation() > 40) {
                outlineWidth = split.getDividerLocation();
                onResize.accept(outlineWidth);
            }
        });
        add(split, BorderLayout.CENTER);
        setOutlineVisible(visible);
    }

    public atlantis.hypatia.ui.OutlinePanel outline() {
        return outline;
    }

    public boolean isOutlineVisible() {
        return outline != null && outline.isVisible();
    }

    public void setOutlineVisible(boolean visible) {
        if (outline == null) return;
        outline.setVisible(visible);
        if (visible) split.setDividerLocation(outlineWidth);
        split.revalidate();
    }

    public void setOutlineWidth(int width) {
        if (width == outlineWidth) return;
        outlineWidth = width;
        if (isOutlineVisible()) split.setDividerLocation(width);
    }

    // --- remembered position ----------------------------------------------------------------

    /** First (fully or partly) visible line, 1-based. */
    public int topLine() {
        java.awt.Rectangle r = area.getVisibleRect();
        int offset = area.viewToModel2D(new java.awt.Point(0, r.y));
        try {
            return area.getLineOfOffset(Math.max(0, offset)) + 1;
        } catch (BadLocationException e) {
            return 1;
        }
    }

    /** Puts the caret at {@code caret} and scrolls so that {@code topLine} is the first visible line. */
    public void restorePosition(int caret, int topLine) {
        int len = area.getDocument().getLength();
        area.setCaretPosition(Math.max(0, Math.min(caret, len)));
        SwingUtilities.invokeLater(() -> {
            try {
                int line = Math.max(0, Math.min(topLine - 1, area.getLineCount() - 1));
                Rectangle2D r = area.modelToView2D(area.getLineStartOffset(line));
                JViewport vp = scroll.getViewport();
                if (r == null) return;
                int y = (int) r.getY();
                y = Math.max(0, Math.min(y, area.getHeight() - vp.getHeight()));
                vp.setViewPosition(new java.awt.Point(0, y));
            } catch (BadLocationException ignored) {
                // caret is set anyway
            }
        });
    }

    private atlantis.hypatia.ui.StyleMarks styleMarks;

    /** Adds style checking: the explanation bar goes below the editor. */
    public void installStyleMarks(atlantis.hypatia.ui.StyleMarks marks) {
        styleMarks = marks;
        add(marks.bar(), BorderLayout.SOUTH);
        ((HypatiaTextArea) area).addToolTipExtra(marks::tooltipAt);
    }

    public atlantis.hypatia.ui.StyleMarks styleMarks() {
        return styleMarks;
    }

    /** Environment indentation settings (see {@link LatexIndenter}). */
    public void applyIndentation(boolean autoIndent, boolean indentDocument, boolean autoClose) {
        area.putClientProperty(LatexIndenter.AUTO_INDENT, autoIndent);
        area.putClientProperty(LatexIndenter.AUTO_CLOSE, autoClose);
        area.putClientProperty(LatexIndenter.INDENT_DOCUMENT, indentDocument);
    }

    private void edited() {
        if (fatalHighlight != null) SwingUtilities.invokeLater(this::clearFatalLine);
        markDirty();
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            onStateChange.run();
        }
    }

    // --- file I/O --------------------------------------------------------------------

    public void load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        charset = detectCharset(bytes);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), charset))) {
            area.read(r, null); // keeps the file's line-ending style for saving
        }
        area.setSyntaxEditingStyle(path.toString().endsWith(".properties")
                ? org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
                : LatexTokenMaker.SYNTAX_STYLE);
        area.discardAllEdits();
        area.setCaretPosition(0);
        file = atlantis.hypatia.Platform.canonical(path);
        lastModified = Files.getLastModifiedTime(file);
        dirty = false;
        onStateChange.run();
    }

    /** Reloads from disk, keeping the caret near where it was. */
    public void reload() throws IOException {
        int caret = area.getCaretPosition();
        load(file);
        area.setCaretPosition(Math.min(caret, area.getDocument().getLength()));
    }

    /**
     * Moves the freshly written {@code tmp} over {@code target}. On Windows the rename fails
     * while another program (OneDrive, a virus scanner, the search indexer) has the file open,
     * so it is retried briefly and finally the content is copied over the file instead.
     */
    static void replace(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < (atlantis.hypatia.Platform.WINDOWS ? 5 : 1); attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                last = e;
                break;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            Files.copy(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            e.addSuppressed(last);
            throw e;
        }
    }

    public void save() throws IOException {
        if (file == null) throw new IllegalStateException("No file name");
        Path tmp = file.resolveSibling("." + file.getFileName() + ".hypatia-tmp");
        try (Writer w = Files.newBufferedWriter(tmp, charset)) {
            area.write(w);
        }
        replace(tmp, file);
        lastModified = Files.getLastModifiedTime(file);
        dirty = false;
        onStateChange.run();
    }

    public void saveAs(Path path) throws IOException {
        file = atlantis.hypatia.Platform.canonical(path);
        save();
    }

    /** True if the file on disk has been changed by another program since we loaded/saved it. */
    public boolean changedOnDisk() {
        if (file == null || lastModified == null || !Files.exists(file)) return false;
        try {
            return !Files.getLastModifiedTime(file).equals(lastModified);
        } catch (IOException e) {
            return false;
        }
    }

    public void acceptDiskVersion() {
        try {
            lastModified = Files.getLastModifiedTime(file);
        } catch (IOException ignored) {
            // keep old timestamp
        }
    }

    private static Charset detectCharset(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    public Charset charset() { return charset; }

    // --- navigation ------------------------------------------------------------------

    /** 1-based line and column. Moves the caret, centres the line and flashes it. */
    public void goToLine(int line, int column) {
        int lineIndex = Math.max(0, Math.min(line - 1, area.getLineCount() - 1));
        try {
            int start = area.getLineStartOffset(lineIndex);
            int endOff = area.getLineEndOffset(lineIndex);
            int pos = Math.min(start + Math.max(0, column - 1), Math.max(start, endOff - 1));
            area.setCaretPosition(pos);
            SwingUtilities.invokeLater(() -> centreOn(pos));
            flash(lineIndex);
        } catch (BadLocationException ignored) {
            // line index was clamped, cannot happen
        }
        area.requestFocusInWindow();
    }

    private void centreOn(int pos) {
        try {
            Rectangle2D r = area.modelToView2D(pos);
            if (r == null) return;
            JViewport vp = scroll.getViewport();
            int y = (int) r.getY() - vp.getHeight() / 2 + (int) r.getHeight() / 2;
            y = Math.max(0, Math.min(y, area.getHeight() - vp.getHeight()));
            vp.setViewPosition(new java.awt.Point(0, y));
        } catch (BadLocationException ignored) {
            // position is valid
        }
    }

    private void flash(int lineIndex) {
        try {
            Object tag = area.addLineHighlight(lineIndex, Theme.palette().flashLine());
            Timer t = new Timer(1400, e -> area.removeLineHighlight(tag));
            t.setRepeats(false);
            t.start();
        } catch (BadLocationException ignored) {
            // valid line
        }
    }

    public int caretLine() {
        return area.getCaretLineNumber() + 1;
    }

    public int caretColumn() {
        return area.getCaretOffsetFromLineStart() + 1;
    }

    // --- compile problems --------------------------------------------------------------

    public void clearProblems() {
        for (Object tag : problemHighlights) area.removeLineHighlight(tag);
        problemHighlights.clear();
        clearFatalLine();
    }

    private Object fatalHighlight;

    /** Marks the line of the first error red, until the next edit in this file. */
    public void markFatalLine(int line) {
        clearFatalLine();
        java.awt.Color c = Theme.palette().errorLine();
        java.awt.Color strong = new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, c.getAlpha() * 11 / 5));
        try {
            fatalHighlight = area.addLineHighlight(line - 1, strong);
            // the current-line highlight is painted on top and would hide it
            area.setHighlightCurrentLine(false);
        } catch (BadLocationException ignored) {
            // line not in this document
        }
    }

    private void clearFatalLine() {
        if (fatalHighlight != null) {
            area.removeLineHighlight(fatalHighlight);
            fatalHighlight = null;
            area.setHighlightCurrentLine(true);
        }
    }

    public void markProblemLine(int line) {
        try {
            problemHighlights.add(area.addLineHighlight(line - 1, Theme.palette().errorLine()));
        } catch (BadLocationException ignored) {
            // line not in this document (stale log) - skip
        }
    }

}
