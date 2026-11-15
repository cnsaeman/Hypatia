package atlantis.hypatia.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A keyboard-driven search popup: type to filter, Up/Down (and Page Up/Down)
 * to choose, Enter to accept, Esc to cancel. Optionally shows a preview of the
 * selected item on the right. Without a placeholder there is no search field: the popup
 * just lists the items for {@code initialQuery}, and the keys go to the list.
 */
public class SearchPopup<T> extends JDialog {

    /** Result of one search: the items to show and a line of information. */
    public record Result<T>(List<T> items, String info) {}

    private final JTextField field = new JTextField();
    private final DefaultListModel<T> model = new DefaultListModel<>();
    private final JList<T> list = new JList<>(model) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true; // wrap long entries instead of scrolling sideways
        }
    };
    private final JLabel info = new JLabel(" ");
    private final JLabel keys = new JLabel("↑↓ select   Enter insert   Esc cancel");
    private final boolean searchable;
    private final Function<String, Result<T>> search;
    private final Consumer<T> preview;
    private T chosen;

    /**
     * @param placeholder hint in the empty search field; null for a list without a search field
     * @param search  runs on every keystroke
     * @param preview called with the selected item (or null); may be null
     * @param previewComponent shown to the right of the list when not null
     */
    public SearchPopup(Window owner, String title, String placeholder, String initialQuery,
                       Function<String, Result<T>> search, ListCellRenderer<? super T> renderer,
                       Consumer<T> preview, JComponent previewComponent) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.search = search;
        this.preview = preview;
        this.searchable = placeholder != null;
        setUndecorated(true);

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setForeground(Theme.accent());
        field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 3f));
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JTextField.showClearButton", true);
        field.putClientProperty("JTextField.selectAllOnFocusPolicy", "never"); // keep typing after a pre-filled query
        field.putClientProperty("JTextField.leadingIcon", Icons.of(Icons.Glyph.FIND));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        top.add(heading, BorderLayout.NORTH);
        if (searchable) top.add(field, BorderLayout.CENTER);

        list.setCellRenderer(renderer);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(!searchable); // typing always goes to the search field
        // ...so show the selection as active although the list never has focus
        list.putClientProperty("FlatLaf.style",
                "selectionInactiveBackground: @selectionBackground; selectionInactiveForeground: @selectionForeground");
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && this.preview != null) this.preview.accept(list.getSelectedValue());
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) accept();
            }
        });
        // HTML cells wrap to the list width: recompute their heights when it changes.
        list.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                list.setFixedCellHeight(10);
                list.setFixedCellHeight(-1);
            }
        });
        JScrollPane listScroll = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setBorder(Theme.line(1, 0, 1, 0));

        JComponent middle = listScroll;
        if (previewComponent != null) {
            QuietSplitPane split = new QuietSplitPane(QuietSplitPane.HORIZONTAL_SPLIT, listScroll, previewComponent);
            split.setResizeWeight(0.45);
            split.setBorder(null);
            split.setDividerSize(5);
            middle = split;
        }

        keys.setForeground(UIManager.getColor("Label.disabledForeground"));
        info.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 14, 8, 14));
        bottom.add(info, BorderLayout.CENTER);
        bottom.add(keys, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createLineBorder(Theme.accent(), 1));
        content.add(top, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);

        bind(KeyEvent.VK_DOWN, () -> move(1));
        bind(KeyEvent.VK_UP, () -> move(-1));
        bind(KeyEvent.VK_PAGE_DOWN, () -> move(8));
        bind(KeyEvent.VK_PAGE_UP, () -> move(-8));
        bind(KeyEvent.VK_ENTER, this::accept);
        bind(KeyEvent.VK_ESCAPE, this::dispose);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        field.setText(initialQuery == null ? "" : initialQuery);
        field.setCaretPosition(field.getText().length());
        refresh();

        // Sizeable: most of the owner window.
        Rectangle o = owner.getBounds();
        int w = Math.max(720, Math.min(1400, (int) (o.width * 0.72)));
        int h = Math.max(440, Math.min(900, (int) (o.height * 0.7)));
        setSize(new Dimension(w, h));
        setLocation(o.x + (o.width - w) / 2, o.y + Math.max(40, (o.height - h) / 3));
    }

    /** Shows the popup and returns the accepted item, or null if cancelled. */
    public T choose() {
        (searchable ? field : list).requestFocusInWindow();
        setVisible(true); // modal: returns when closed
        return chosen;
    }

    /** The key help at the bottom right (default "↑↓ select   Enter insert   Esc cancel"). */
    public void setKeysHint(String text) {
        keys.setText(text);
    }

    private void bind(int key, Runnable r) {
        String name = "popup-" + key;
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        getRootPane().getActionMap().put(name, field.getActionMap().get(name));
    }

    private void refresh() {
        Result<T> r = search.apply(field.getText());
        model.clear();
        r.items().forEach(model::addElement);
        info.setText(r.info());
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        } else if (preview != null) {
            preview.accept(null);
        }
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void accept() {
        chosen = list.getSelectedValue();
        if (chosen != null) dispose();
    }
}
