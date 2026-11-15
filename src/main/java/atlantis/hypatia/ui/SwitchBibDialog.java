package atlantis.hypatia.ui;

import atlantis.hypatia.tex.BibMatcher.Row;
import atlantis.hypatia.tex.Bibliography;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

/**
 * LaTeX > Switch BibTeX File: the cited keys of the old .bib file with the proposed keys of
 * the new one, old and new entry side by side. "Replace Current Match" searches the new file
 * for another entry; Delete keeps the old key; OK applies the list.
 */
public class SwitchBibDialog extends JDialog {

    private static final int MAX_RESULTS = 10;

    private final List<Row> rows;
    private final Bibliography newBib;
    private final Font font;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final PreviewPane oldPane;
    private final PreviewPane newPane;
    private final JLabel summary = new JLabel(" ");
    private final String oldName;
    private final String newName;
    private boolean accepted;

    public SwitchBibDialog(JFrame owner, Path oldFile, Path newFile, List<Row> rows, Bibliography newBib, Font font) {
        super(owner, "Switch BibTeX File", true);
        this.rows = rows;
        this.newBib = newBib;
        this.font = font;
        this.oldName = oldFile.getFileName().toString();
        this.newName = newFile.getFileName().toString();
        oldPane = new PreviewPane(font);
        newPane = new PreviewPane(font);
        oldPane.setWrap(true); // long fields (abstracts) stay readable side by side
        newPane.setWrap(true);

        rows.forEach(model::addElement);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showRow();
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) chooseOther();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "keep");
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "keep");
        list.getActionMap().put("keep", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { keepOld(); }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "choose");
        list.getActionMap().put("choose", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { chooseOther(); }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        top.add(summary, BorderLayout.CENTER);
        JLabel hint = new JLabel("Enter or double-click: choose another entry   ·   Delete: keep the old key");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        top.add(hint, BorderLayout.SOUTH);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(Theme.line(1, 0, 1, 0));
        JPanel previews = new JPanel(new GridLayout(1, 2, 6, 0));
        previews.add(oldPane);
        previews.add(newPane);
        QuietSplitPane split = new QuietSplitPane(QuietSplitPane.VERTICAL_SPLIT, listScroll, previews);
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JButton other = new JButton("Replace Current Match…");
        other.addActionListener(e -> chooseOther());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.add(other);
        buttons.add(javax.swing.Box.createHorizontalStrut(24));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        java.awt.Rectangle o = owner.getBounds();
        setSize(Math.max(900, (int) (o.width * 0.8)), Math.max(600, (int) (o.height * 0.8)));
        setLocationRelativeTo(owner);
        updateSummary();
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    /** Shows the dialog; true if OK was pressed (the rows then hold the chosen proposals). */
    public boolean showDialog() {
        list.requestFocusInWindow();
        setVisible(true);
        return accepted;
    }

    private void showRow() {
        Row r = list.getSelectedValue();
        if (r == null) {
            oldPane.clear();
            newPane.clear();
            return;
        }
        oldPane.show("Old: " + oldName + ":" + r.old.line(), r.old.source(), r.old.line(), 0);
        if (r.proposal == null) {
            newPane.show("New: no match in " + newName + "; the key " + r.old.key() + " is kept", "", 1, 0);
        } else {
            newPane.show("New: " + newName + ":" + r.proposal.line()
                    + (r.method == null ? "" : "   (found by " + r.method + ")"), r.proposal.source(), r.proposal.line(), 0);
        }
    }

    private void chooseOther() {
        Row r = list.getSelectedValue();
        if (r == null) return;
        PreviewPane preview = new PreviewPane(font);
        String first = r.old.shortAuthors().split("[, ]")[0];
        SearchPopup<Bibliography.Entry> popup = new SearchPopup<>(this, "Replacement for " + r.old.key(),
                "Words from authors and title…", first,
                q -> {
                    Bibliography.SearchResult s = newBib.search(q, MAX_RESULTS);
                    return new SearchPopup.Result<>(s.entries(), s.total() + (s.total() == 1 ? " entry" : " entries")
                            + " in " + newName);
                },
                new ReferenceInserter.BibRenderer(),
                e -> {
                    if (e == null) preview.clear();
                    else preview.show(newName + ":" + e.line(), e.source(), e.line(), 0);
                },
                preview);
        Bibliography.Entry chosen = popup.choose();
        if (chosen != null) {
            r.proposal = chosen;
            r.method = "chosen by hand";
            changed();
        }
        // after the popup has gone, so that the keys reach the list again
        javax.swing.SwingUtilities.invokeLater(() -> {
            toFront();
            list.requestFocus();
        });
    }

    private void keepOld() {
        Row r = list.getSelectedValue();
        if (r == null) return;
        r.proposal = null;
        r.method = null;
        changed();
    }

    private void changed() {
        int i = list.getSelectedIndex();
        model.set(i, model.get(i)); // repaint the row
        showRow();
        updateSummary();
    }

    private void updateSummary() {
        long matched = rows.stream().filter(r -> r.proposal != null).count();
        summary.setText(oldName + "  →  " + newName + ":   " + rows.size()
                + (rows.size() == 1 ? " cited entry, " : " cited entries, ") + matched + " with a replacement, "
                + (rows.size() - matched) + " kept");
    }

    private final class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            Row r = (Row) value;
            Color dim = selected ? l.getSelectionForeground() : UIManager.getColor("Label.disabledForeground");
            Color warn = selected ? l.getSelectionForeground() : Theme.warning();
            String title = r.old.title().isEmpty() ? "" : r.old.title();
            if (title.length() > 90) title = title.substring(0, 87) + "…";
            String right;
            if (r.proposal == null) {
                right = "<span style='color:" + hex(warn) + "'>no replacement: old key kept</span>";
            } else {
                right = "<b>" + esc(r.proposal.key()) + "</b>&nbsp;&nbsp;<span style='color:" + hex(dim) + "'>("
                        + esc(r.method == null ? "" : r.method) + ")</span>";
            }
            String html = "<html><b>" + esc(r.old.key()) + "</b>&nbsp;&nbsp;→&nbsp;&nbsp;" + right
                    + "<br><span style='color:" + hex(dim) + "'>" + esc(r.old.shortAuthors())
                    + (title.isEmpty() ? "" : ": " + esc(title)) + "</span></html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(l, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            return c;
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
