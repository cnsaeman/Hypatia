package atlantis.hypatia.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import atlantis.hypatia.tex.LogParser.Problem;
import atlantis.hypatia.tex.LogParser.Severity;

/** Bottom panel: clickable list of errors/warnings next to the raw build output. */
public class LogPanel extends JPanel {

    private final DefaultListModel<Problem> problems = new DefaultListModel<>();
    private final JList<Problem> list = new JList<>(problems);
    private final JTextArea raw = new JTextArea();
    private final JLabel summary = new JLabel("No build yet. Use LaTeX > Build.");
    private final JSplitPane split;

    public LogPanel(Consumer<Problem> onOpen, Runnable onClose) {
        super(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createCompoundBorder(
                Theme.line(1, 0, 1, 0),
                BorderFactory.createEmptyBorder(3, 10, 3, 4)));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        header.add(summary, BorderLayout.CENTER);
        JButton close = new JButton("✕");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.setToolTipText("Hide log (F8)");
        close.addActionListener(e -> onClose.run());
        header.add(close, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        list.setCellRenderer(new ProblemRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Problem p = list.getSelectedValue();
                if (p == null) return;
                showInRaw(p.logLine());
                onOpen.accept(p);
            }
        });

        raw.setEditable(false);
        raw.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        raw.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JScrollPane left = new JScrollPane(list);
        left.setBorder(null);
        JScrollPane right = new JScrollPane(raw);
        right.setBorder(null);
        split = new QuietSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.45);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    public void start(String commandLine) {
        problems.clear();
        raw.setText("$ " + commandLine + "\n");
        setSummary("Compiling…", UIManager.getColor("Label.foreground"));
    }

    public void append(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        raw.append(sb.toString());
        raw.setCaretPosition(raw.getDocument().getLength());
    }

    public void finish(List<Problem> found, String text, boolean ok) {
        problems.clear();
        found.forEach(problems::addElement);
        setSummary(text, ok ? Theme.success() : Theme.error());
        split.setDividerLocation(found.isEmpty() ? 0.0 : 0.45);
    }

    public void setSummary(String text, java.awt.Color color) {
        summary.setText(text);
        summary.setForeground(color);
    }

    public boolean hasErrors() {
        for (int i = 0; i < problems.size(); i++) {
            if (problems.get(i).severity() == Severity.ERROR) return true;
        }
        return false;
    }

    private void showInRaw(int logLine) {
        try {
            int line = logLine + 1; // first raw line is the command
            if (line < raw.getLineCount()) {
                int start = raw.getLineStartOffset(line);
                int end = raw.getLineEndOffset(line);
                raw.select(start, end);
                raw.getCaret().setSelectionVisible(true);
                raw.scrollRectToVisible(raw.modelToView2D(start).getBounds());
            }
        } catch (BadLocationException ignored) {
            // line out of range: log was cleared
        }
    }

    private static final class ProblemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            Problem p = (Problem) value;
            String color = p.severity() == Severity.ERROR ? hex(Theme.error()) : hex(Theme.warning());
            String label = p.severity() == Severity.ERROR ? "●" : "▲";
            String loc = p.location().isEmpty() ? "" : "<span style='color:" + hex(
                    UIManager.getColor("Label.disabledForeground")) + "'>" + escape(p.location()) + "</span>&nbsp;&nbsp;";
            String html = "<html><span style='color:" + color + "'>" + label + "</span>&nbsp;&nbsp;"
                    + loc + escape(p.message()) + "</html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(l, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            return c;
        }

        private static String hex(java.awt.Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
