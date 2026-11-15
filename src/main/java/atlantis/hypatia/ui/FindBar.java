package atlantis.hypatia.ui;

import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/**
 * Find / replace bar shown below the editor (Ctrl+F, Ctrl+R). Typing searches incrementally:
 * the first match after the position where the search started is selected at once, and all
 * matches are highlighted shortly after typing pauses (from two characters on, since a single
 * character can have thousands of matches).
 *
 * <p>With "In selection" ticked, finding, counting and replacing are limited to a region of
 * the text: the selection when the bar was opened (a selection over several lines ticks the
 * box by itself), or when the box was ticked. The region is tinted while the bar is open and
 * moves with edits elsewhere in the text.
 */
public class FindBar extends JPanel {

    private final Supplier<RSyntaxTextArea> target;
    private final JTextField findField = new JTextField(24);
    private final JTextField replaceField = new JTextField(24);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JCheckBox regex = new JCheckBox("Regex");
    private final JCheckBox wholeWord = new JCheckBox("Whole word");
    private final JCheckBox inSelection = new JCheckBox("In selection");
    private final JLabel status = new JLabel(" ");
    private final JPanel replaceRow;
    /** Coalesces keystrokes: the search runs once typing pauses briefly. */
    private final javax.swing.Timer typingTimer;
    /** Where incremental search starts: the caret (or selection start) when the bar was opened or last navigated. */
    private int anchor = -1;
    private static final int MIN_MARK_ALL = 2;

    /** The "In selection" region (it moves with edits), the text area it belongs to, and its tint. */
    private javax.swing.text.Position regionStart, regionEnd;
    private RSyntaxTextArea regionArea;
    private Object regionTag;
    /** The match this bar selected last, so that ticking "In selection" does not take it as the region. */
    private int lastMatchStart = -1, lastMatchEnd = -1;

    public FindBar(Supplier<RSyntaxTextArea> target) {
        super(new GridBagLayout());
        this.target = target;
        setBorder(BorderFactory.createCompoundBorder(
                Theme.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        findField.putClientProperty("JTextField.placeholderText", "Find");
        findField.putClientProperty("JTextField.showClearButton", true);
        replaceField.putClientProperty("JTextField.placeholderText", "Replace with");

        JPanel findRow = row();
        findRow.add(findField);
        findRow.add(button("Previous", () -> find(false)));
        findRow.add(button("Next", () -> find(true)));
        findRow.add(matchCase);
        findRow.add(wholeWord);
        findRow.add(regex);
        findRow.add(inSelection);
        findRow.add(status);

        replaceRow = row();
        replaceRow.add(replaceField);
        replaceRow.add(button("Replace", this::replace));
        replaceRow.add(button("Replace all", this::replaceAll));

        JButton close = button("✕", this::close);
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Close (Esc)");

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.anchor = GridBagConstraints.WEST;
        add(findRow, c);
        c.gridy = 1;
        add(replaceRow, c);
        c.gridx = 1; c.gridy = 0; c.weightx = 0; c.anchor = GridBagConstraints.NORTHEAST;
        add(close, c);

        findField.addActionListener(e -> find(true));
        replaceField.addActionListener(e -> replace());
        typingTimer = new javax.swing.Timer(120, e -> incremental());
        // coming back to the field after moving around in the editor: search from there
        findField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                RSyntaxTextArea area = target.get();
                if (area != null) anchor = area.getSelectionStart();
            }
        });
        typingTimer.setRepeats(false);
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { typingTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { typingTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { typingTimer.restart(); }
        });
        // a fixed width, so that changing status text does not re-layout the bar on every keystroke
        status.setPreferredSize(new java.awt.Dimension(170, status.getPreferredSize().height));
        matchCase.addActionListener(e -> markAll());
        regex.addActionListener(e -> markAll());
        wholeWord.addActionListener(e -> markAll());
        inSelection.setToolTipText("Find and replace only within the text that was selected");
        inSelection.addActionListener(e -> inSelectionToggled());

        // Tab order: find field, replace field (if shown), then the options; cycles within the bar
        setFocusTraversalPolicyProvider(true);
        setFocusTraversalPolicy(new OrderPolicy());

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::close);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "prev", () -> find(false));
        setVisible(false);
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        return p;
    }

    private static JButton button(String text, Runnable r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> r.run());
        b.setFocusable(false);
        return b;
    }

    private void bind(KeyStroke ks, String name, Runnable r) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    public void open(boolean withReplace) {
        replaceRow.setVisible(withReplace);
        setVisible(true);
        RSyntaxTextArea area = target.get();
        anchor = area == null ? -1 : area.getSelectionStart();
        String sel = area == null ? null : area.getSelectedText();
        if (sel != null && sel.contains("\n")) { // several lines: search within them
            setRegion(area, area.getSelectionStart(), area.getSelectionEnd());
            inSelection.setSelected(true);
        } else {
            clearRegion();
            inSelection.setSelected(false);
            if (sel != null) findField.setText(sel);
        }
        typingTimer.stop();
        findField.requestFocusInWindow();
        findField.selectAll();
        markAll();
        revalidate();
    }

    public void close() {
        typingTimer.stop();
        setVisible(false);
        clearRegion();
        RSyntaxTextArea area = target.get();
        if (area != null) {
            SearchEngine.markAll(area, new SearchContext()); // clears highlights
            area.requestFocusInWindow();
        }
        revalidate();
    }

    /** The search for RSyntaxTextArea; marking is done by {@link #markAll()}. */
    private SearchContext context(boolean forward) {
        SearchContext ctx = new SearchContext(findField.getText());
        ctx.setMatchCase(matchCase.isSelected());
        ctx.setRegularExpression(regex.isSelected());
        ctx.setWholeWord(wholeWord.isSelected());
        ctx.setSearchForward(forward);
        ctx.setSearchWrap(true);
        ctx.setReplaceWith(replaceField.getText());
        ctx.setMarkAll(false);
        return ctx;
    }

    public void find(boolean forward) {
        RSyntaxTextArea area = target.get();
        if (area == null || findField.getText().isEmpty()) return;
        if (typingTimer.isRunning()) { // Enter right after typing: show the first match first
            incremental();
            return;
        }
        if (region(area) != null) {
            findInRegion(area, forward, forward ? area.getSelectionEnd() : area.getSelectionStart());
            return;
        }
        try {
            SearchResult r = SearchEngine.find(area, context(forward)); // also clears the marks
            if (r.wasFound()) {
                anchor = area.getSelectionStart();
                lastMatchStart = anchor;
                lastMatchEnd = area.getSelectionEnd();
            }
            markAll();
            if (r.wasFound() && r.isWrapped()) status.setText("Wrapped around");
        } catch (RuntimeException e) {
            status.setText("Invalid regex");
        }
    }

    /** After typing: selects the first match at or after the anchor, then highlights all matches. */
    private void incremental() {
        typingTimer.stop();
        RSyntaxTextArea area = target.get();
        if (area == null) return;
        if (anchor < 0 || anchor > area.getDocument().getLength()) anchor = area.getSelectionStart();
        if (findField.getText().isEmpty()) {
            area.setCaretPosition(anchor);
            markAll();
            return;
        }
        if (region(area) != null) {
            int[] reg = region(area);
            int from = anchor >= reg[0] && anchor <= reg[1] ? anchor : reg[0];
            if (!findInRegion(area, true, from)) area.setCaretPosition(from);
            return;
        }
        try {
            area.setCaretPosition(anchor); // search from the anchor, not from the previous match
            SearchResult r = SearchEngine.find(area, context(true));
            if (!r.wasFound()) area.setCaretPosition(anchor);
            lastMatchStart = area.getSelectionStart();
            lastMatchEnd = area.getSelectionEnd();
        } catch (RuntimeException e) {
            // incomplete regex while typing: markAll reports it
        }
        markAll();
    }

    private void replace() {
        RSyntaxTextArea area = target.get();
        if (area == null || findField.getText().isEmpty()) return;
        if (region(area) != null) {
            replaceInRegion(area);
            return;
        }
        try {
            SearchResult r = SearchEngine.replace(area, context(true));
            markAll();
            if (!r.wasFound()) report(false, "Not found");
        } catch (RuntimeException e) {
            status.setText("Invalid regex");
        }
    }

    private void replaceAll() {
        RSyntaxTextArea area = target.get();
        if (area == null || findField.getText().isEmpty()) return;
        if (region(area) != null) {
            replaceAllInRegion(area);
            return;
        }
        try {
            SearchResult r = SearchEngine.replaceAll(area, context(true));
            markAll();
            status.setForeground(UIManager.getColor("Label.foreground"));
            status.setText(r.getCount() + " replaced");
        } catch (RuntimeException e) {
            status.setText("Invalid regex");
        }
    }

    /** Most highlights shown; beyond this, highlighting costs more than it helps. */
    private static final int MAX_MARKS = 5000;

    /**
     * Highlights all matches in one pass over the text. (RSyntaxTextArea's own mark-all copies
     * the rest of the document for every match, which made typing lag in long files.)
     */
    private void markAll() {
        RSyntaxTextArea area = target.get();
        if (area == null) return;
        String q = findField.getText();
        area.clearMarkAllHighlights();
        if (q.isEmpty()) {
            area.markAll(java.util.List.of());
            report(true, " ");
            return;
        }
        java.util.regex.Pattern p;
        try {
            p = pattern(q);
        } catch (java.util.regex.PatternSyntaxException e) {
            area.markAll(java.util.List.of());
            report(false, "Invalid regex");
            return;
        }
        if (q.length() < MIN_MARK_ALL && !regex.isSelected()) {
            area.markAll(java.util.List.of()); // a single character: too many matches to be useful
            boolean found = area.getSelectedText() != null;
            report(found, found ? " " : "Not found");
            return;
        }
        java.util.List<org.fife.ui.rtextarea.DocumentRange> ranges = new java.util.ArrayList<>();
        java.util.regex.Matcher m = p.matcher(area.getText());
        int[] reg = region(area);
        if (reg != null) limit(m, reg);
        int count = 0;
        try {
            while (m.find()) {
                if (m.end() == m.start()) continue; // empty matches (e.g. "a|") are not shown
                count++;
                if (ranges.size() < MAX_MARKS) ranges.add(new org.fife.ui.rtextarea.DocumentRange(m.start(), m.end()));
            }
        } catch (StackOverflowError e) {
            // pathological regex: show what was found so far
        }
        area.markAll(ranges);
        String where = reg != null ? " in selection" : "";
        report(count > 0, count == 0 ? "Not found" + where : (count == 1 ? "1 match" : count + " matches") + where);
        status.setToolTipText(count > MAX_MARKS ? "Only the first " + MAX_MARKS + " matches are highlighted" : null);
    }

    /** The search as a Java pattern, with the same options as RSyntaxTextArea's search. */
    private java.util.regex.Pattern pattern(String q) {
        int flags = java.util.regex.Pattern.MULTILINE;
        if (!matchCase.isSelected()) flags |= java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE;
        String re = regex.isSelected() ? q : java.util.regex.Pattern.quote(q);
        if (wholeWord.isSelected()) {
            re = regex.isSelected() ? "\\b(?:" + re + ")\\b" : "(?<![\\p{L}\\p{N}])" + re + "(?![\\p{L}\\p{N}])";
        }
        return java.util.regex.Pattern.compile(re, flags);
    }

    private void report(boolean found, String text) {
        boolean miss = !found && !findField.getText().isEmpty();
        status.setForeground(miss ? Theme.error() : UIManager.getColor("Label.disabledForeground"));
        status.setText(text);
        findField.putClientProperty("JComponent.outline", miss ? "error" : null);
    }

    // --- "In selection" --------------------------------------------------------------------

    /** The region [start, end] if "In selection" is on and the region belongs to {@code area}; else null. */
    private int[] region(RSyntaxTextArea area) {
        if (!inSelection.isSelected() || area == null || area != regionArea || regionStart == null) return null;
        int s = regionStart.getOffset(), e = regionEnd.getOffset();
        return s < e ? new int[] {s, e} : null;
    }

    private void setRegion(RSyntaxTextArea area, int start, int end) {
        clearRegion();
        try {
            regionStart = area.getDocument().createPosition(start);
            regionEnd = area.getDocument().createPosition(end);
            regionArea = area;
            java.awt.Color c = area.getSelectionColor();
            regionTag = area.getHighlighter().addHighlight(start, end,
                    new RegionPainter(new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue(), 105)));
        } catch (javax.swing.text.BadLocationException e) {
            clearRegion();
        }
    }

    private void clearRegion() {
        if (regionArea != null && regionTag != null) regionArea.getHighlighter().removeHighlight(regionTag);
        regionStart = regionEnd = null;
        regionArea = null;
        regionTag = null;
    }

    private void inSelectionToggled() {
        RSyntaxTextArea area = target.get();
        if (inSelection.isSelected() && area != null) {
            int s = area.getSelectionStart(), e = area.getSelectionEnd();
            boolean isMatch = s == lastMatchStart && e == lastMatchEnd;
            if (s < e && !isMatch) {
                setRegion(area, s, e);
            } else if (region(area) == null) {
                inSelection.setSelected(false);
                report(false, "Select text first");
                findField.putClientProperty("JComponent.outline", null);
                return;
            }
            anchor = region(area)[0];
        } else {
            clearRegion();
        }
        markAll();
    }

    /** Restricts a matcher to the region, letting look-arounds and \\b see the text around it. */
    private static void limit(java.util.regex.Matcher m, int[] reg) {
        m.region(reg[0], reg[1]);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
    }

    /** The non-empty matches in the region, as [start, end] pairs. */
    private java.util.List<int[]> regionMatches(RSyntaxTextArea area, java.util.regex.Pattern p, int[] reg) {
        java.util.List<int[]> list = new java.util.ArrayList<>();
        java.util.regex.Matcher m = p.matcher(area.getText());
        limit(m, reg);
        while (m.find()) {
            if (m.end() > m.start()) list.add(new int[] {m.start(), m.end()});
        }
        return list;
    }

    private void select(RSyntaxTextArea area, int start, int end) {
        area.setCaretPosition(start);
        area.moveCaretPosition(end);
        lastMatchStart = start;
        lastMatchEnd = end;
    }

    /** Selects the next (or previous) match in the region from {@code from}, wrapping within the region. */
    private boolean findInRegion(RSyntaxTextArea area, boolean forward, int from) {
        int[] reg = region(area);
        java.util.List<int[]> all;
        try {
            all = regionMatches(area, pattern(findField.getText()), reg);
        } catch (RuntimeException | StackOverflowError e) {
            markAll();
            return false;
        }
        int[] hit = null;
        boolean wrapped = false;
        if (forward) {
            for (int[] r : all) if (r[0] >= from) { hit = r; break; }
            if (hit == null && !all.isEmpty()) { hit = all.get(0); wrapped = true; }
        } else {
            for (int i = all.size() - 1; i >= 0; i--) if (all.get(i)[1] <= from) { hit = all.get(i); break; }
            if (hit == null && !all.isEmpty()) { hit = all.get(all.size() - 1); wrapped = true; }
        }
        if (hit != null) {
            select(area, hit[0], hit[1]);
            anchor = hit[0];
        }
        markAll();
        if (wrapped && all.size() > 1) status.setText("Wrapped around");
        return hit != null;
    }

    /** The replacement for the match {@code m}, as RSyntaxTextArea's own Replace does it ($1, \\n for regexes). */
    private String replacementFor(java.util.regex.Matcher m) {
        return regex.isSelected() ? SearchEngine.getReplacementText(m, replaceField.getText()) : replaceField.getText();
    }

    /** Replaces the selected match (if the selection is one), then selects the next match in the region. */
    private void replaceInRegion(RSyntaxTextArea area) {
        int[] reg = region(area);
        java.util.regex.Pattern p;
        try {
            p = pattern(findField.getText());
        } catch (RuntimeException e) {
            report(false, "Invalid regex");
            return;
        }
        int s = area.getSelectionStart(), e = area.getSelectionEnd();
        int next = s;
        if (s < e && s >= reg[0] && e <= reg[1]) {
            java.util.regex.Matcher m = p.matcher(area.getText());
            limit(m, new int[] {s, e});
            m.useAnchoringBounds(false);
            if (m.find() && m.start() == s && m.end() == e) {
                String with = replacementFor(m);
                area.replaceRange(with, s, e);
                next = s + with.length();
                // set the region anew: the edit may have pushed its start or end past the new text
                setRegion(area, reg[0], reg[1] + with.length() - (e - s));
            }
        }
        if (!findInRegion(area, true, next)) report(false, "Not found in selection");
    }

    /** Replaces every match in the region, as one undo step; the region then covers the changed text. */
    private void replaceAllInRegion(RSyntaxTextArea area) {
        int[] reg = region(area);
        java.util.regex.Pattern p;
        try {
            p = pattern(findField.getText());
        } catch (RuntimeException e) {
            report(false, "Invalid regex");
            return;
        }
        java.util.regex.Matcher m = p.matcher(area.getText());
        limit(m, reg);
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<String> withs = new java.util.ArrayList<>();
        try {
            while (m.find()) {
                if (m.end() == m.start()) continue;
                spans.add(new int[] {m.start(), m.end()});
                withs.add(replacementFor(m));
            }
        } catch (RuntimeException | StackOverflowError e) {
            report(false, "Invalid replacement");
            return;
        }
        int delta = 0;
        if (!spans.isEmpty()) {
            area.beginAtomicEdit();
            try {
                for (int i = spans.size() - 1; i >= 0; i--) { // from the end, so offsets stay valid
                    int[] r = spans.get(i);
                    area.replaceRange(withs.get(i), r[0], r[1]);
                    delta += withs.get(i).length() - (r[1] - r[0]);
                }
            } finally {
                area.endAtomicEdit();
            }
            setRegion(area, reg[0], reg[1] + delta); // the region keeps exactly the replaced text
            area.setCaretPosition(reg[0]);
        }
        markAll();
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.setText(spans.size() + " replaced in selection");
    }

    /** A translucent tint, painted under the text like a selection. */
    private static final class RegionPainter extends javax.swing.text.DefaultHighlighter.DefaultHighlightPainter {
        RegionPainter(java.awt.Color c) { super(c); }
    }

    private final class OrderPolicy extends java.awt.FocusTraversalPolicy {
        private java.util.List<java.awt.Component> order() {
            java.util.List<java.awt.Component> l = new java.util.ArrayList<>();
            for (java.awt.Component c : new java.awt.Component[] {findField, replaceField, matchCase, wholeWord, regex, inSelection}) {
                if (c.isShowing() && c.isEnabled() && c.isFocusable()) l.add(c);
            }
            return l;
        }

        private java.awt.Component step(java.awt.Component from, int dir) {
            java.util.List<java.awt.Component> l = order();
            if (l.isEmpty()) return null;
            int i = l.indexOf(from);
            if (i < 0) return l.get(0);
            return l.get(Math.floorMod(i + dir, l.size()));
        }

        @Override public java.awt.Component getComponentAfter(java.awt.Container root, java.awt.Component c) { return step(c, 1); }
        @Override public java.awt.Component getComponentBefore(java.awt.Container root, java.awt.Component c) { return step(c, -1); }
        @Override public java.awt.Component getFirstComponent(java.awt.Container root) { return order().isEmpty() ? null : order().get(0); }
        @Override public java.awt.Component getLastComponent(java.awt.Container root) {
            java.util.List<java.awt.Component> l = order();
            return l.isEmpty() ? null : l.get(l.size() - 1);
        }
        @Override public java.awt.Component getDefaultComponent(java.awt.Container root) { return findField; }
    }
}
