package atlantis.hypatia.ui;

import atlantis.hypatia.tex.LatexLog;
import atlantis.hypatia.tex.LatexLog.Category;
import atlantis.hypatia.tex.LatexLog.Entry;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * View > Errors and Warnings: everything the last LaTeX run reported, read from the main
 * file's .log and .blg, by category. Click or Enter jumps to the place in the source.
 */
public class ProblemsWindow extends JDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final Supplier<Path> rootSupplier;
    private final Consumer<Entry> onOpen;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final Map<Category, JCheckBox> filters = new EnumMap<>(Category.class);
    private final JLabel header = new JLabel(" ");
    private final JLabel empty = new JLabel();
    private final JScrollPane scroll = new JScrollPane(list);
    private List<Entry> entries = List.of();
    private Path root;

    /**
     * @param rootSupplier the main file of the current project (or null)
     * @param onOpen       opens an entry's file at its line
     */
    public ProblemsWindow(JFrame owner, Supplier<Path> rootSupplier, Consumer<Entry> onOpen) {
        super(owner, "Errors and Warnings", false);
        this.rootSupplier = rootSupplier;
        this.onOpen = onOpen;

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 6));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        top.add(header, BorderLayout.CENTER);
        JButton refresh = new JButton("Refresh");
        refresh.setToolTipText("Read the log again (also done after every build)");
        refresh.addActionListener(e -> refresh());
        top.add(refresh, BorderLayout.EAST);

        JPanel boxes = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 2));
        boxes.setBorder(BorderFactory.createEmptyBorder(0, 4, 6, 4));
        for (Category c : Category.values()) {
            JCheckBox b = new JCheckBox(c.label);
            b.setSelected(true);
            b.addActionListener(e -> fill());
            filters.put(c, b);
            boxes.add(b);
        }
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(boxes, BorderLayout.CENTER);
        north.setBorder(Theme.line(0, 0, 1, 0));

        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) onOpen.accept(model.get(i));
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (list.getSelectedValue() != null) onOpen.accept(list.getSelectedValue());
            }
        });
        scroll.setBorder(null);
        empty.setHorizontalAlignment(JLabel.CENTER);
        empty.setForeground(UIManager.getColor("Label.disabledForeground"));

        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        setSize(820, 460);
        setLocationRelativeTo(owner);
    }

    /** Shows the window for the current project, reading its log. */
    public void showFor() {
        refresh();
        setVisible(true);
        list.requestFocusInWindow();
    }

    /** Reads the log of the current project again. */
    public void refresh() {
        root = rootSupplier.get();
        if (root == null) {
            entries = List.of();
            header.setText("No main file");
            fill();
            return;
        }
        Path log = LatexLog.logFile(root);
        entries = LatexLog.read(root);
        if (log == null) {
            header.setText(root.getFileName() + ": no log file yet. Use LaTeX > Build.");
        } else {
            long errors = entries.stream().filter(Entry::error).count();
            String when;
            try {
                when = TIME.format(Instant.ofEpochMilli(Files.getLastModifiedTime(log).toMillis())
                        .atZone(ZoneId.systemDefault()));
            } catch (java.io.IOException e) {
                when = "?";
            }
            header.setText(log.getFileName() + " (" + when + "):  " + errors + (errors == 1 ? " error, " : " errors, ")
                    + (entries.size() - errors) + (entries.size() - errors == 1 ? " warning" : " warnings"));
        }
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Entry e : entries) counts.merge(e.category(), 1, Integer::sum);
        for (Category c : Category.values()) {
            int n = counts.getOrDefault(c, 0);
            JCheckBox b = filters.get(c);
            b.setText(c.label + " (" + n + ")");
            b.setEnabled(n > 0);
        }
        fill();
    }

    private void fill() {
        Entry selected = list.getSelectedValue();
        model.clear();
        for (Category c : Category.values()) {
            if (!filters.get(c).isSelected()) continue;
            for (Entry e : entries) {
                if (e.category() == c) model.addElement(e);
            }
        }
        if (selected != null && model.contains(selected)) list.setSelectedValue(selected, true);
        else if (!model.isEmpty()) list.setSelectedIndex(0);
        boolean none = model.isEmpty();
        empty.setText(entries.isEmpty() ? (root == null ? "Open a file of the project." : "Nothing to report.")
                : "All entries are filtered out.");
        remove(none ? scroll : empty);
        add(none ? empty : scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private String location(Entry e) {
        if (e.file() == null) return "";
        Path dir = root == null ? null : root.getParent();
        String f = dir != null && e.file().startsWith(dir) ? dir.relativize(e.file()).toString()
                : e.file().getFileName().toString();
        return e.line() > 0 ? f + ":" + e.line() : f;
    }

    private final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            Entry e = (Entry) value;
            String color = hex(e.error() ? Theme.error() : Theme.warning());
            String mark = e.error() ? "●" : "▲";
            String loc = ProblemsWindow.this.location(e);
            String locHtml = loc.isEmpty() ? "" : "<span style='color:"
                    + hex(UIManager.getColor("Label.disabledForeground")) + "'>" + escape(loc) + "</span>&nbsp;&nbsp;";
            String html = "<html><span style='color:" + color + "'>" + mark + "</span>&nbsp;&nbsp;" + locHtml
                    + escape(e.message()) + "</html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(l, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            c.setToolTipText(e.category().label + (loc.isEmpty() ? "" : " — " + loc));
            return c;
        }

        private static String hex(java.awt.Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** FlowLayout that wraps its rows when the window is narrow. */
    private static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int h, int v) {
            super(align, h, v);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container target) {
            return size(target);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container target) {
            java.awt.Dimension d = size(target);
            d.width = 0;
            return d;
        }

        private java.awt.Dimension size(java.awt.Container target) {
            synchronized (target.getTreeLock()) {
                int width = target.getWidth() > 0 ? target.getWidth() : Integer.MAX_VALUE;
                java.awt.Insets in = target.getInsets();
                int max = width - in.left - in.right - getHgap() * 2;
                int x = 0, rowH = 0, h = 0, w = 0;
                for (Component c : target.getComponents()) {
                    if (!c.isVisible()) continue;
                    java.awt.Dimension d = c.getPreferredSize();
                    if (x > 0 && x + d.width > max) {
                        h += rowH + getVgap();
                        x = 0;
                        rowH = 0;
                    }
                    x += d.width + getHgap();
                    w = Math.max(w, x);
                    rowH = Math.max(rowH, d.height);
                }
                h += rowH + getVgap() * 2;
                return new java.awt.Dimension(Math.min(w, width) + in.left + in.right, h + in.top + in.bottom);
            }
        }
    }
}
