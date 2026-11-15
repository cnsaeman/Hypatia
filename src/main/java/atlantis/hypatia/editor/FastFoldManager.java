package atlantis.hypatia.editor;

import java.beans.PropertyChangeListener;
import java.util.BitSet;
import java.util.List;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;

/**
 * Wraps RSyntaxTextArea's fold manager to make {@link #isLineHidden} cheap.
 *
 * With line wrap on, every layout asks isLineHidden for every line, and the default manager
 * answers by walking the folds (each \begin…\end is one) and computing their line numbers:
 * lines × folds work on every keystroke, which made typing in long papers sluggish. Here the
 * hidden lines are computed once into a bit set and kept until the folds, their collapsed
 * states or the document change; the same goes for the fold starting on each line.
 */
final class FastFoldManager implements FoldManager {

    private final FoldManager base;
    private BitSet hidden; // null: recompute
    private boolean anyCollapsed;
    private java.util.Map<Integer, Fold> byStartLine = new java.util.HashMap<>(); // outermost fold per line

    private FastFoldManager(FoldManager base) {
        this.base = base;
    }

    /** Puts a caching wrapper around the area's fold manager (no-op if that is not possible). */
    static void install(RSyntaxTextArea area) {
        try {
            java.lang.reflect.Field f = RSyntaxTextArea.class.getDeclaredField("foldManager");
            f.setAccessible(true);
            FoldManager current = (FoldManager) f.get(area);
            if (current == null || current instanceof FastFoldManager) return;
            FastFoldManager fast = new FastFoldManager(current);
            f.set(area, fast);
            current.addPropertyChangeListener(e -> fast.invalidate());
            area.getDocument().addDocumentListener(fast.docListener);
            area.addPropertyChangeListener("document", e -> {
                if (e.getOldValue() instanceof javax.swing.text.Document d) d.removeDocumentListener(fast.docListener);
                if (e.getNewValue() instanceof javax.swing.text.Document d) d.addDocumentListener(fast.docListener);
                fast.invalidate();
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            // another RSyntaxTextArea version: keep the default (slower) manager
        }
    }

    private final DocumentListener docListener = new DocumentListener() {
        @Override public void insertUpdate(DocumentEvent e) { invalidate(); }
        @Override public void removeUpdate(DocumentEvent e) { invalidate(); }
        @Override public void changedUpdate(DocumentEvent e) { }
    };

    /** Called when folds are re-parsed, a fold is collapsed or expanded, or the text changes. */
    void invalidate() {
        hidden = null;
    }

    private void compute() {
        BitSet b = new BitSet();
        boolean any = false;
        java.util.Map<Integer, Fold> starts = new java.util.HashMap<>();
        for (int i = 0; i < base.getFoldCount(); i++) {
            any |= mark(base.getFold(i), b);
            index(base.getFold(i), starts);
        }
        hidden = b;
        anyCollapsed = any;
        byStartLine = starts;
    }

    private static void index(Fold f, java.util.Map<Integer, Fold> starts) {
        starts.putIfAbsent(f.getStartLine(), f);
        for (int i = 0; i < f.getChildCount(); i++) index(f.getChild(i), starts);
    }

    /** Marks the lines hidden inside {@code f}; true if any fold in it is collapsed. */
    private static boolean mark(Fold f, BitSet b) {
        if (f.isCollapsed()) {
            int start = f.getStartLine();
            int end = f.getEndLine();
            if (end > start) b.set(start + 1, end + 1);
            return true;
        }
        boolean any = false;
        for (int i = 0; i < f.getChildCount(); i++) any |= mark(f.getChild(i), b);
        return any;
    }

    @Override
    public boolean isLineHidden(int line) {
        if (hidden == null) compute();
        return anyCollapsed && hidden.get(line);
    }

    @Override
    public int getHiddenLineCount() {
        if (hidden == null) compute();
        return anyCollapsed ? hidden.cardinality() : 0;
    }

    @Override
    public int getHiddenLineCountAbove(int line) {
        return getHiddenLineCountAbove(line, false);
    }

    @Override
    public int getHiddenLineCountAbove(int line, boolean physical) {
        if (hidden == null) compute();
        if (!anyCollapsed) return 0;
        return base.getHiddenLineCountAbove(line, physical);
    }

    // --- delegated ---------------------------------------------------------------------------

    @Override public void addPropertyChangeListener(PropertyChangeListener l) { base.addPropertyChangeListener(l); }
    @Override public void removePropertyChangeListener(PropertyChangeListener l) { base.removePropertyChangeListener(l); }
    @Override public void clear() { base.clear(); invalidate(); }
    @Override public boolean ensureOffsetNotInClosedFold(int offs) {
        boolean changed = base.ensureOffsetNotInClosedFold(offs);
        if (changed) invalidate();
        return changed;
    }
    @Override public Fold getDeepestFoldContaining(int offs) { return base.getDeepestFoldContaining(offs); }
    @Override public Fold getDeepestOpenFoldContaining(int offs) { return base.getDeepestOpenFoldContaining(offs); }
    @Override public Fold getFold(int index) { return base.getFold(index); }
    @Override public int getFoldCount() { return base.getFoldCount(); }
    @Override public Fold getFoldForLine(int line) {
        if (hidden == null) compute();
        return byStartLine.get(line);
    }
    @Override public int getLastVisibleLine() { return base.getLastVisibleLine(); }
    @Override public int getVisibleLineAbove(int line) { return base.getVisibleLineAbove(line); }
    @Override public int getVisibleLineBelow(int line) { return base.getVisibleLineBelow(line); }
    @Override public boolean isCodeFoldingEnabled() { return base.isCodeFoldingEnabled(); }
    @Override public boolean isCodeFoldingSupportedAndEnabled() { return base.isCodeFoldingSupportedAndEnabled(); }
    @Override public boolean isFoldStartLine(int line) {
        if (hidden == null) compute();
        return byStartLine.containsKey(line);
    }
    @Override public void reparse() { base.reparse(); invalidate(); }
    @Override public void setCodeFoldingEnabled(boolean enabled) { base.setCodeFoldingEnabled(enabled); invalidate(); }
    @Override public void setFolds(List<Fold> folds) { base.setFolds(folds); invalidate(); }
}
