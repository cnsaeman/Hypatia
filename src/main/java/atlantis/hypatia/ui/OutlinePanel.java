package atlantis.hypatia.ui;

import atlantis.hypatia.tex.Outline;
import atlantis.hypatia.tex.Outline.Heading;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * The structure sidebar: the project's headings as a tree. Click or Enter jumps to a heading;
 * the heading containing the cursor is selected as you move.
 */
public class OutlinePanel extends JPanel {

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel empty = new JLabel("No sections");
    private final JScrollPane scroll = new JScrollPane(tree,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final Set<String> collapsed = new HashSet<>();
    private final BiConsumer<Path, Integer> onOpen;
    private final Runnable onEscape;
    private List<Heading> current = List.of();
    private boolean selecting;

    /** @param onOpen opens a file at a 1-based line */
    public OutlinePanel(BiConsumer<Path, Integer> onOpen, Runnable onEscape) {
        super(new BorderLayout());
        this.onOpen = onOpen;
        this.onEscape = onEscape;

        JLabel title = new JLabel("Structure");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(Theme.accent());
        title.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
        add(title, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0); // a click jumps; the handle expands
        tree.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.setRowHeight(0);
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p != null && e.getButton() == MouseEvent.BUTTON1) jump(p);
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jump");
        tree.getActionMap().put("jump", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (tree.getSelectionPath() != null) jump(tree.getSelectionPath());
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back");
        tree.getActionMap().put("back", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onEscape.run(); }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override public void treeExpanded(TreeExpansionEvent e) { collapsed.remove(keyOf(e.getPath())); }
            @Override public void treeCollapsed(TreeExpansionEvent e) { collapsed.add(keyOf(e.getPath())); }
        });

        scroll.setBorder(null);
        empty.setForeground(UIManager.getColor("Label.disabledForeground"));
        empty.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 4));
        empty.setVerticalAlignment(JLabel.TOP);
        add(scroll, BorderLayout.CENTER);
        setBorder(Theme.line(0, 0, 0, 1));
    }

    /** Replaces the tree, keeping collapsed headings collapsed. */
    public void update(List<Heading> headings) {
        if (headings.equals(current)) return;
        current = headings;
        root.removeAllChildren();
        for (Heading h : headings) root.add(node(h));
        model.reload();
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath p = tree.getPathForRow(row);
            if (!collapsed.contains(keyOf(p))) tree.expandPath(p);
        }
        remove(empty);
        remove(scroll);
        add(headings.isEmpty() ? empty : scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private static DefaultMutableTreeNode node(Heading h) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(h);
        for (Heading c : h.children()) n.add(node(c));
        return n;
    }

    /** Selects the heading that contains line {@code line} of {@code file}, without jumping. */
    public void showPosition(Path file, int line) {
        Heading best = null;
        for (Heading h : Outline.flatten(current)) {
            if (Objects.equals(h.file(), file) && h.line() <= line) best = h;
        }
        if (best == null) {
            if (!tree.isSelectionEmpty()) tree.clearSelection();
            return;
        }
        DefaultMutableTreeNode n = find(root, best);
        if (n == null) return;
        TreePath p = new TreePath(n.getPath());
        if (p.equals(tree.getSelectionPath())) return;
        selecting = true;
        try {
            tree.setSelectionPath(p);
            java.awt.Rectangle b = tree.getPathBounds(p);
            if (b != null) tree.scrollRectToVisible(new java.awt.Rectangle(0, b.y, 1, b.height));
        } finally {
            selecting = false;
        }
    }

    public void focusTree() {
        tree.requestFocusInWindow();
        if (tree.isSelectionEmpty() && tree.getRowCount() > 0) tree.setSelectionRow(0);
    }

    public void themeChanged() {
        setBorder(Theme.line(0, 0, 0, 1));
        empty.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    private static DefaultMutableTreeNode find(DefaultMutableTreeNode n, Heading h) {
        if (n.getUserObject() == h) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            DefaultMutableTreeNode r = find((DefaultMutableTreeNode) n.getChildAt(i), h);
            if (r != null) return r;
        }
        return null;
    }

    private void jump(TreePath p) {
        if (selecting) return;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (o instanceof Heading h) onOpen.accept(h.file(), h.line());
    }

    private static String keyOf(TreePath p) {
        StringBuilder sb = new StringBuilder();
        for (Object o : p.getPath()) {
            Object u = ((DefaultMutableTreeNode) o).getUserObject();
            sb.append(u instanceof Heading h ? h.key() : "").append('/');
        }
        return sb.toString();
    }

    /** Chapters bold, deeper levels in the dimmer text colour; tooltip with file and line. */
    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
            setIcon(null);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof Heading h) {
                setText(h.command().endsWith("*") ? h.title() + " *" : h.title());
                Font f = t.getFont();
                setFont(h.level() <= 1 ? f.deriveFont(Font.BOLD) : f);
                if (!sel && h.level() >= 4) setForeground(UIManager.getColor("Label.disabledForeground"));
                setToolTipText(h.title() + "  —  \\" + h.command() + ", " + h.file().getFileName() + ":" + h.line());
                setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
            }
            return this;
        }
    }
}
