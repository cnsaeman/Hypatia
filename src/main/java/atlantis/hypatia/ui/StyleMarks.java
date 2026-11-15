package atlantis.hypatia.ui;

import atlantis.hypatia.style.Problem;
import atlantis.hypatia.style.StyleLibrary;
import atlantis.hypatia.style.StyleRunner;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SquiggleUnderlineHighlightPainter;

/**
 * Style problems of one editor: purple squiggles, the explanation bar below the editor, and
 * re-checking after each pause in typing while a style is active.
 */
public final class StyleMarks {

    private static final int DELAY_MS = 800;

    /** All style checks run here, one at a time. */
    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hypatia-style-check");
        t.setDaemon(true);
        return t;
    });

    private record Mark(Highlighter.Highlight highlight, String explanation) {}

    private final RSyntaxTextArea area;
    private final Supplier<StyleLibrary> library;
    private final LongSupplier pluginTimeout;
    private final Consumer<String> onMessage;
    private final Supplier<String> keyHint; // e.g. " (Alt+Page Down: next)"

    private final SquiggleUnderlineHighlightPainter painter = new SquiggleUnderlineHighlightPainter(Theme.stylePurple());
    private final List<Mark> marks = new ArrayList<>();
    private final Timer timer;
    private final JPanel bar = new JPanel(new BorderLayout(8, 0));
    private final JLabel text = new JLabel(" ");
    private final JLabel summary = new JLabel();

    private String styleName; // null: not active
    private long version;
    private boolean running;
    private Set<String> lastMessages = Set.of();

    public StyleMarks(RSyntaxTextArea area, Supplier<StyleLibrary> library, LongSupplier pluginTimeout,
                      Consumer<String> onMessage, Supplier<String> keyHint, Runnable onClear) {
        this.area = area;
        this.library = library;
        this.pluginTimeout = pluginTimeout;
        this.onMessage = onMessage;
        this.keyHint = keyHint;

        timer = new Timer(DELAY_MS, e -> runNow());
        timer.setRepeats(false);
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        area.addCaretListener(e -> updateBar());

        text.setIcon(new Dot());
        text.setIconTextGap(8);
        summary.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton close = new JButton("✕");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Clear style marks");
        close.setFocusable(false);
        close.addActionListener(e -> onClear.run());
        JPanel right = new JPanel(new BorderLayout(6, 0));
        right.setOpaque(false);
        right.add(summary, BorderLayout.CENTER);
        right.add(close, BorderLayout.EAST);
        bar.add(text, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        bar.setBorder(BorderFactory.createCompoundBorder(Theme.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(2, 10, 2, 4)));
        bar.setVisible(false);
    }

    /** The bar to put below the editor. */
    public JPanel bar() {
        return bar;
    }

    public boolean isActive() {
        return styleName != null;
    }

    public String styleName() {
        return styleName;
    }

    /** Checks with {@code name} now, and again after every pause in typing until {@link #clear()}. */
    public void activate(String name) {
        styleName = name;
        lastMessages = Set.of();
        bar.setVisible(true);
        summary.setText("checking…");
        runNow();
    }

    public void clear() {
        styleName = null;
        timer.stop();
        version++; // drop results still on their way
        removeMarks();
        bar.setVisible(false);
        bar.revalidate();
    }

    /** Re-run (e.g. after the style files changed). */
    public void refresh() {
        if (isActive()) runNow();
    }

    public void themeChanged() {
        painter.setPaint(Theme.stylePurple());
        bar.setBorder(BorderFactory.createCompoundBorder(Theme.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(2, 10, 2, 4)));
        summary.setForeground(UIManager.getColor("Label.disabledForeground"));
        area.repaint();
    }

    private void edited() {
        version++;
        if (isActive()) timer.restart();
    }

    private void runNow() {
        timer.stop();
        if (!isActive()) return;
        if (running) { // one check per editor at a time; try again shortly
            timer.restart();
            return;
        }
        StyleLibrary lib = library.get();
        StyleLibrary.Style style = lib == null ? null : lib.get(styleName);
        if (style == null) {
            String n = styleName;
            clear();
            onMessage.accept("No style called “" + n + "” in " + StyleLibrary.configFile());
            return;
        }
        String snapshot = area.getText();
        long v = version;
        long timeout = pluginTimeout.getAsLong();
        running = true;
        CHECKS.submit(() -> {
            StyleRunner.Result r;
            try {
                r = StyleRunner.run(style, snapshot, timeout);
            } catch (RuntimeException e) {
                r = new StyleRunner.Result(List.of(), List.of("Style check failed: " + e));
            }
            StyleRunner.Result result = r;
            SwingUtilities.invokeLater(() -> {
                running = false;
                if (!isActive() || !style.name().equals(styleName)) return;
                if (v != version) { // edited meanwhile: the timer runs again
                    if (!timer.isRunning()) timer.restart();
                    return;
                }
                apply(result.problems());
                Set<String> msgs = new LinkedHashSet<>(result.messages());
                if (!msgs.isEmpty() && !msgs.equals(lastMessages)) onMessage.accept(String.join("; ", msgs));
                lastMessages = msgs;
            });
        });
    }

    private void apply(List<Problem> problems) {
        removeMarks();
        Highlighter h = area.getHighlighter();
        for (Problem p : problems) {
            try {
                Object tag = h.addHighlight(p.start(), p.end(), painter);
                marks.add(new Mark((Highlighter.Highlight) tag, p.explanation()));
            } catch (BadLocationException ignored) {
                // outside the document
            }
        }
        area.repaint();
        updateBar();
    }

    private void removeMarks() {
        Highlighter h = area.getHighlighter();
        for (Mark m : marks) h.removeHighlight(m.highlight());
        marks.clear();
        area.repaint();
    }

    // --- caret, tooltip, navigation ---------------------------------------------------------------

    /** Explanations of the problems covering {@code offset} (end inclusive for the caret after a word). */
    public List<String> explanationsAt(int offset, boolean endInclusive) {
        List<String> out = new ArrayList<>();
        for (Mark m : marks) {
            int s = m.highlight().getStartOffset();
            int e = m.highlight().getEndOffset();
            if (e > s && offset >= s && (offset < e || (endInclusive && offset == e))) {
                if (!out.contains(m.explanation())) out.add(m.explanation());
            }
        }
        return out;
    }

    /** Tooltip for the mouse at {@code offset}, or null. */
    public String tooltipAt(int offset) {
        List<String> ex = explanationsAt(offset, false);
        return ex.isEmpty() ? null : "<html>" + String.join("<br>", ex.stream().map(StyleMarks::esc).toList()) + "</html>";
    }

    private void updateBar() {
        if (!isActive()) return;
        int n = (int) marks.stream().filter(m -> m.highlight().getEndOffset() > m.highlight().getStartOffset()).count();
        List<String> here = explanationsAt(area.getCaretPosition(), true);
        if (!here.isEmpty()) {
            text.setText(String.join("   ·   ", here));
            text.setForeground(UIManager.getColor("Label.foreground"));
        } else {
            text.setText(n == 0 ? "No style problems" : n + (n == 1 ? " style problem" : " style problems")
                    + keyHint.get());
            text.setForeground(UIManager.getColor("Label.disabledForeground"));
        }
        summary.setText("Style: " + styleName);
    }

    /** Moves the caret to the next (or previous) problem, wrapping around. */
    public boolean go(boolean forward) {
        List<int[]> ranges = new ArrayList<>();
        for (Mark m : marks) {
            int s = m.highlight().getStartOffset();
            int e = m.highlight().getEndOffset();
            if (e > s) ranges.add(new int[] {s, e});
        }
        if (ranges.isEmpty()) return false;
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        int caret = area.getCaretPosition();
        int[] target = null;
        if (forward) {
            for (int[] r : ranges) {
                if (r[0] > caret) {
                    target = r;
                    break;
                }
            }
            if (target == null) target = ranges.get(0);
        } else {
            int ref = caret; // the start of the problem the caret is on, if any
            for (int[] r : ranges) {
                if (r[0] <= caret && caret <= r[1]) ref = Math.min(ref, r[0]);
            }
            for (int i = ranges.size() - 1; i >= 0 && target == null; i--) {
                if (ranges.get(i)[0] < ref) target = ranges.get(i);
            }
            if (target == null) target = ranges.get(ranges.size() - 1);
        }
        area.setCaretPosition(target[0]);
        try {
            java.awt.Rectangle r = area.modelToView2D(target[0]).getBounds();
            r.grow(0, 60);
            area.scrollRectToVisible(r);
        } catch (BadLocationException ignored) {
            // caret set anyway
        }
        return true;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Small purple dot in front of the explanation. */
    private static final class Dot implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.stylePurple());
            g2.fillOval(x, y + 2, 8, 8);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 8; }
        @Override public int getIconHeight() { return 12; }
    }
}
