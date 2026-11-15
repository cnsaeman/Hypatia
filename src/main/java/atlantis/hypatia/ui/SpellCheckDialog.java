package atlantis.hypatia.ui;

import atlantis.hypatia.spell.SpellChecker;
import atlantis.hypatia.spell.SpellChecker.Misspelling;
import atlantis.hypatia.spell.SpellConfig;
import atlantis.hypatia.spell.SpellDictionary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Position;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * "Spellcheck Document": goes through the misspelled words of the current file, starting at
 * the cursor and wrapping around once. Enter replaces, Esc closes; Up/Down choose a suggestion.
 */
public class SpellCheckDialog extends JDialog {

    private final RSyntaxTextArea area;
    private final SpellDictionary dict;
    private final SpellConfig config;
    private final Position startPos;

    private int pos;
    private boolean wrapped;
    private Misspelling current;
    private int changes;

    private final JLabel heading = new JLabel(" ");
    private final JLabel context = new JLabel(" ");
    private final JTextField field = new JTextField();
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> suggestions = new JList<>(model);
    private final JButton replace = new JButton("Replace");
    private final JButton replaceAll = new JButton("Replace All");
    private final JButton ignore = new JButton("Ignore");
    private final JButton ignoreAll = new JButton("Ignore All");
    private final JButton add = new JButton("Add to Dictionary");
    private final JButton close = new JButton("Close");

    public SpellCheckDialog(JFrame owner, RSyntaxTextArea area, SpellDictionary dict, SpellConfig config)
            throws BadLocationException {
        super(owner, "Spelling (" + dict.language() + ")", ModalityType.APPLICATION_MODAL);
        this.area = area;
        this.dict = dict;
        this.config = config;
        int caret = area.getSelectionStart();
        this.startPos = area.getDocument().createPosition(caret);
        this.pos = caret;

        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setForeground(Theme.accent());
        context.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        context.setFont(Theme.editorFont("", 13));

        suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestions.setFocusable(false);
        suggestions.putClientProperty("FlatLaf.style",
                "selectionInactiveBackground: @selectionBackground; selectionInactiveForeground: @selectionForeground");
        suggestions.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && suggestions.getSelectedValue() != null) {
                field.setText(suggestions.getSelectedValue());
                field.selectAll();
            }
        });
        suggestions.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) replace();
            }
        });

        replace.addActionListener(e -> replace());
        replaceAll.addActionListener(e -> replaceAll());
        ignore.addActionListener(e -> ignore());
        ignoreAll.addActionListener(e -> ignoreAll());
        add.addActionListener(e -> addWord());
        close.addActionListener(e -> dispose());
        replaceAll.setMnemonic('A');
        ignore.setMnemonic('I');
        ignoreAll.setMnemonic('G');
        add.setMnemonic('D');

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(heading, BorderLayout.NORTH);
        top.add(context, BorderLayout.CENTER);

        JPanel change = new JPanel(new BorderLayout(6, 6));
        JLabel changeLabel = new JLabel("Change to:");
        change.add(changeLabel, BorderLayout.WEST);
        change.add(field, BorderLayout.CENTER);
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(change, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(suggestions);
        sp.setPreferredSize(new Dimension(320, 170));
        left.add(sp, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        for (JButton b : new JButton[] {replace, replaceAll, ignore, ignoreAll, add}) {
            b.setFocusable(false);
            buttons.add(b);
        }
        buttons.add(new JLabel());
        close.setFocusable(false);
        buttons.add(close);
        JPanel right = new JPanel(new BorderLayout());
        right.add(buttons, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.add(left, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);

        JLabel keys = new JLabel("Enter replace   ↑↓ choose   Esc close");
        keys.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(keys, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(replace);

        bind(KeyEvent.VK_ESCAPE, this::dispose);
        bind(KeyEvent.VK_DOWN, () -> move(1));
        bind(KeyEvent.VK_UP, () -> move(-1));

        pack();
        setSize(Math.max(getWidth(), 640), getHeight());
        // Bottom right of the window, so the highlighted word stays visible.
        Rectangle o = owner.getBounds();
        setLocation(o.x + o.width - getWidth() - 40, o.y + o.height - getHeight() - 60);
    }

    private void bind(int key, Runnable r) {
        String name = "spell-" + key;
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Runs the check; returns the number of changes made, or -1 if nothing was misspelled. */
    public int run() {
        if (!next()) {
            return -1;
        }
        field.requestFocusInWindow();
        setVisible(true);
        return changes;
    }

    /** Moves to the next misspelling; returns false when done. */
    private boolean next() {
        String text = area.getText();
        List<Misspelling> all = SpellChecker.check(text, dict, config);
        int stop = startPos.getOffset();
        for (int round = 0; round < 2; round++) {
            for (Misspelling m : all) {
                if (m.start() < pos) continue;
                if (wrapped && m.start() >= stop) break;
                show(m);
                return true;
            }
            if (wrapped) break;
            wrapped = true;
            pos = 0;
        }
        finish();
        return false;
    }

    private void show(Misspelling m) {
        current = m;
        area.select(m.start(), m.end());
        try {
            Rectangle r = area.modelToView2D(m.start()).getBounds();
            r.grow(0, 80);
            area.scrollRectToVisible(r);
        } catch (BadLocationException ignored) {
            // still selected
        }
        heading.setText("Not in the " + dict.language() + " dictionary: “" + m.word() + "”");
        context.setText(contextHtml(m));
        model.clear();
        List<String> s = dict.suggest(m.word(), 12);
        s.forEach(model::addElement);
        if (!s.isEmpty()) {
            suggestions.setSelectedIndex(0);
        } else {
            field.setText(m.word());
            field.selectAll();
        }
        replaceAll.setText("Replace All “" + shorten(m.source()) + "”");
        pack();
        setSize(Math.max(getWidth(), 640), getHeight());
    }

    private void finish() {
        current = null;
        heading.setText("Spelling check finished");
        context.setText(changes == 0 ? "No changes made." : changes + (changes == 1 ? " change made." : " changes made."));
        model.clear();
        field.setText("");
        replaceAll.setText("Replace All");
        for (JButton b : new JButton[] {replace, replaceAll, ignore, ignoreAll, add}) b.setEnabled(false);
        getRootPane().setDefaultButton(close);
    }

    private String contextHtml(Misspelling m) {
        try {
            Element root = area.getDocument().getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(m.start()));
            int ls = line.getStartOffset();
            int le = line.getEndOffset() - 1;
            int from = Math.max(ls, m.start() - 60);
            int to = Math.min(le, m.end() + 60);
            String before = area.getText(from, m.start() - from).stripLeading();
            String word = area.getText(m.start(), m.end() - m.start());
            String after = area.getText(m.end(), to - m.end());
            Color c = Theme.error();
            String hex = String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
            return "<html>" + (from > ls ? "… " : "") + esc(before) + "<b><span style='color:" + hex + "'>"
                    + esc(word) + "</span></b>" + esc(after) + (to < le ? " …" : "") + "</html>";
        } catch (BadLocationException e) {
            return m.source();
        }
    }

    private static String shorten(String s) {
        return s.length() > 18 ? s.substring(0, 16) + "…" : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, suggestions.getSelectedIndex() + delta));
        suggestions.setSelectedIndex(i);
        suggestions.ensureIndexIsVisible(i);
    }

    // --- actions --------------------------------------------------------------------------------

    private String replacementFor(Misspelling m) {
        return SpellChecker.encodeLike(field.getText(), m.source());
    }

    private void replace() {
        Misspelling m = current;
        if (m == null) {
            dispose();
            return;
        }
        String r = replacementFor(m);
        SpellChecker.replace(area, m.start(), m.end(), r);
        changes++;
        pos = m.start() + r.length();
        next();
    }

    private void replaceAll() {
        Misspelling m = current;
        if (m == null) return;
        String r = replacementFor(m);
        List<Misspelling> all = SpellChecker.check(area.getText(), dict, config);
        int shiftBefore = 0;
        area.beginAtomicEdit();
        try {
            for (int i = all.size() - 1; i >= 0; i--) {
                Misspelling x = all.get(i);
                if (!x.source().equals(m.source())) continue;
                area.getDocument().remove(x.start(), x.end() - x.start());
                area.getDocument().insertString(x.start(), r, null);
                changes++;
                if (x.start() < m.start()) shiftBefore += r.length() - (x.end() - x.start());
            }
        } catch (BadLocationException ignored) {
            // document changed meanwhile
        } finally {
            area.endAtomicEdit();
        }
        pos = m.start() + shiftBefore + r.length();
        next();
    }

    private void ignore() {
        if (current == null) return;
        pos = current.end();
        next();
    }

    private void ignoreAll() {
        if (current == null) return;
        dict.ignore(current.word());
        pos = current.end();
        next();
    }

    private void addWord() {
        if (current == null) return;
        try {
            dict.addWord(current.word());
        } catch (IOException e) {
            heading.setText("Could not write " + dict.userFile() + ": " + e.getMessage());
            return;
        }
        pos = current.end();
        next();
    }
}
