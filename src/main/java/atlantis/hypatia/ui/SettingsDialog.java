package atlantis.hypatia.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import atlantis.hypatia.Platform;
import atlantis.hypatia.Settings;
import atlantis.hypatia.Viewers;

/** Preferences: appearance, build and viewer commands, session. */
public class SettingsDialog extends JDialog {

    private boolean accepted;
    /** The tab shown last, shown again next time. */
    private static int lastTab;

    private final JComboBox<String> theme = new JComboBox<>(new String[] {"Dark", "Light"});
    private final JComboBox<String> font = new JComboBox<>();
    private final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(14, 8, 40, 1));
    private final JCheckBox softWrap = new JCheckBox("Wrap long lines (wrapped rows keep the line's indentation)");
    private final JCheckBox autoIndent = new JCheckBox("Indent environments automatically (4 spaces)");
    private final JCheckBox spellEnabled = new JCheckBox("Check spelling as you type");
    private final JComboBox<String> spellLanguage = new JComboBox<>();
    private final JCheckBox indentDocument = new JCheckBox("Also indent inside \\begin{document}");
    private final JCheckBox autoClose = new JCheckBox("Add \\end{…} when pressing Enter after \\begin{…}");
    private final JTextField compile = new JTextField(42);
    private final JTextField latex = new JTextField(42);
    private final JTextField bibtex = new JTextField(42);
    private final JTextField viewer = new JTextField(42);
    private final JTextField newViewer = new JTextField(42);
    private final JCheckBox showAfter = new JCheckBox("Show the PDF position after a successful build");
    private final JCheckBox restore = new JCheckBox("Reopen files from the last session");

    private static String wrap(String text) {
        String esc = text.replace("&", "&amp;").replace("<", "&lt;");
        return text.isEmpty() ? "" : "<html><div style='width:400px'>" + esc + "</div></html>";
    }

    public SettingsDialog(JFrame owner, Settings s) {
        super(owner, "Hypatia Settings", true);

        font.addItem("(automatic)");
        monospacedFamilies().forEach(font::addItem);
        font.setSelectedItem(s.fontFamily().isBlank() ? "(automatic)" : s.fontFamily());
        theme.setSelectedIndex(s.isDark() ? 0 : 1);
        fontSize.setValue(s.fontSize());
        softWrap.setSelected(s.softWrap());
        autoIndent.setSelected(s.autoIndent());
        spellEnabled.setSelected(s.spellEnabled());
        spellLanguage.setEditable(true);
        spellLanguage.addItem("(none)");
        atlantis.hypatia.spell.Dictionaries.available().forEach(spellLanguage::addItem);
        String lang = s.spellLanguage();
        if (!lang.isEmpty() && ((javax.swing.DefaultComboBoxModel<String>) spellLanguage.getModel()).getIndexOf(lang) < 0) {
            spellLanguage.addItem(lang);
        }
        spellLanguage.setSelectedItem(lang.isEmpty() ? "(none)" : lang);
        indentDocument.setSelected(s.indentDocument());
        autoClose.setSelected(s.autoCloseEnvironments());
        indentDocument.setEnabled(autoIndent.isSelected());
        autoIndent.addActionListener(e -> indentDocument.setEnabled(autoIndent.isSelected()));
        compile.setText(s.compileCommand());
        latex.setText(s.latexCommand());
        bibtex.setText(s.bibtexCommand());
        viewer.setText(s.viewerCommand());
        newViewer.setText(s.newViewerCommand());
        showAfter.setSelected(s.syncAfterCompile());
        restore.setSelected(s.restoreSession());

        javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
        Row r = new Row(tabs, "Editor");

        r.section("Appearance");
        r.field("Theme", theme);
        r.field("Editor font", font);
        r.field("Font size", fontSize);
        r.field("", softWrap);

        r.section("Editing");
        r.field("", autoIndent);
        r.field("", indentDocument);
        r.field("", autoClose);
        r.hint("Enter after \\begin{…} indents; typing \\end{…} aligns it; Tab left of the indentation jumps to it."
                + " Edit > Re-indent fixes a selection or the whole file.");

        r.section("Session");
        r.field("", restore);

        r = new Row(tabs, "Spelling");
        r.section("Spelling");
        r.field("", spellEnabled);
        r.field("Default language", spellLanguage);
        r.hint("For files without \"% !TeX spellcheck = …\". Lists the Hunspell dictionaries found on this computer.");

        r = new Row(tabs, "Building");
        r.section("Building");
        r.field("Build command", compile);
        r.hint("%f main file, %n main file without .tex, %d its folder. Runs in the main file's folder.");
        r.field("", showAfter);
        r.field("", resetButton("Reset to latexmk", () -> compile.setText(Settings.DEFAULT_COMPILE)));
        r.section("Single runs");
        r.field("Run LaTeX", latex);
        r.field("Run BibTeX", bibtex);
        r.hint("LaTeX > Run pdfLaTeX and Run BibTeX: one run each, e.g. \"biber %n\" for biblatex.");
        r.field("", resetButton("Reset to pdflatex and bibtex", () -> {
            latex.setText(Settings.DEFAULT_LATEX);
            bibtex.setText(Settings.DEFAULT_BIBTEX);
        }));

        r = new Row(tabs, "Viewer Integration");
        r.section("PDF viewer (forward search)");
        JComboBox<Object> preset = new JComboBox<>();
        for (Viewers.Viewer v : Viewers.presets()) preset.addItem(v);
        preset.addItem("Custom");
        Viewers.Viewer current = Viewers.byCommand(viewer.getText().trim());
        preset.setSelectedItem(current != null ? current : "Custom");
        r.field("Viewer", preset);
        r.field("Viewer command", viewer);
        r.hint("%p PDF, %l line, %c column, %t current .tex file.");
        JCheckBox separate = new JCheckBox("Separate Okular window for each document");
        separate.setSelected(s.separateViewerWindows());
        r.field("", separate);
        JLabel separateHint = r.hint(wrap("Files of the same document share its window. Needs Okular's"
                + " \"Open new files in tabs\" turned off"
                + (atlantis.hypatia.OkularWindows.available() ? "." : ", and dbus-send, gdbus or qdbus, none of which was found.")));
        Runnable okularOnly = () -> {
            boolean ok = atlantis.hypatia.OkularWindows.isOkular(
                    atlantis.hypatia.tex.CommandTemplate.build(viewer.getText().trim(), java.util.Map.of()));
            separate.setEnabled(ok);
            separateHint.setEnabled(ok);
        };
        okularOnly.run();
        r.field("New window", newViewer);
        r.hint("For LaTeX > Open PDF in New Viewer: a second, independent viewer window.");

        r.section("Inverse search (click in the PDF to jump here)");
        JTextField inverse = new JTextField();
        inverse.setEditable(false);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(inverse.getText()), null));
        JPanel inv = new JPanel(new BorderLayout(6, 0));
        inv.setOpaque(false);
        inv.add(inverse, BorderLayout.CENTER);
        inv.add(copy, BorderLayout.EAST);
        r.field("Editor command", inv);
        JLabel howTo = r.hint("");
        Runnable showInverse = () -> {
            Object sel = preset.getSelectedItem();
            if (sel instanceof Viewers.Viewer v) {
                inverse.setText(v.inverse().isEmpty() ? "(not available with this viewer)" : v.inverse());
                howTo.setText(wrap(v.howTo()));
            } else {
                inverse.setText(Platform.hypatiaCommandLine() + " --line LINE FILE");
                howTo.setText(wrap("Enter this in your viewer's inverse-search setting, with its own placeholders for LINE and FILE."));
            }
            copy.setEnabled(!inverse.getText().startsWith("("));
        };
        boolean[] syncing = {false};
        preset.addActionListener(e -> {
            if (!syncing[0] && preset.getSelectedItem() instanceof Viewers.Viewer v) {
                viewer.setText(v.forward());
                newViewer.setText(v.newWindow());
            }
            showInverse.run();
        });
        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener() {
            private void changed() {
                okularOnly.run();
                Viewers.Viewer v = Viewers.byCommand(viewer.getText().trim());
                Object want = v != null ? v : "Custom";
                if (!want.equals(preset.getSelectedItem())) {
                    syncing[0] = true; // only show the matching preset, leave the text alone
                    preset.setSelectedItem(want);
                    syncing[0] = false;
                }
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        };
        showInverse.run();
        viewer.getDocument().addDocumentListener(edited);

        tabs.setSelectedIndex(Math.min(lastTab, tabs.getTabCount() - 1));
        tabs.addChangeListener(e -> lastTab = tabs.getSelectedIndex());
        JPanel form = new JPanel(new BorderLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        form.add(tabs, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            accepted = true;
            s.setDark(theme.getSelectedIndex() == 0);
            Object f = font.getSelectedItem();
            s.set("editor.font.family", "(automatic)".equals(f) ? "" : (String) f);
            s.setInt("editor.font.size", (Integer) fontSize.getValue());
            s.setBool("editor.softWrap", softWrap.isSelected());
            s.setBool("editor.autoIndent", autoIndent.isSelected());
            s.setBool("spell.enabled", spellEnabled.isSelected());
            Object sl = spellLanguage.getSelectedItem();
            String slv = sl == null || "(none)".equals(sl) ? "" : atlantis.hypatia.spell.Dictionaries.normalize(sl.toString());
            s.set("spell.defaultLanguage", slv);
            s.setBool("editor.indentDocument", indentDocument.isSelected());
            s.setBool("editor.autoCloseEnvironments", autoClose.isSelected());
            s.set("compile.command", compile.getText().trim());
            s.set("compile.latexCommand", latex.getText().trim());
            s.set("compile.bibtexCommand", bibtex.getText().trim());
            s.set("viewer.command", viewer.getText().trim());
            s.set("viewer.newWindowCommand", newViewer.getText().trim());
            s.setBool("viewer.separateWindows", separate.isSelected());
            s.setBool("compile.showPdfAfter", showAfter.isSelected());
            s.setBool("session.restore", restore.isSelected());
            s.save();
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 10));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    public boolean showDialog() {
        setVisible(true);
        return accepted;
    }

    private static JButton resetButton(String text, Runnable r) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "borderless");
        b.addActionListener(e -> r.run());
        return b;
    }

    /** Font families whose 'i' and 'm' have the same width. */
    private static List<String> monospacedFamilies() {
        List<String> out = new ArrayList<>();
        java.awt.Graphics g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();
        for (String fam : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            FontMetrics fm = g.getFontMetrics(new Font(fam, Font.PLAIN, 12));
            if (fm.charWidth('i') == fm.charWidth('m') && fm.charWidth('m') > 0 && fam.matches("[\\p{L}\\p{N} .-]+")) {
                out.add(fam);
            }
        }
        g.dispose();
        return out;
    }

    /** Helper for a two-column form. */
    private static final class Row {
        private final JPanel p = new JPanel(new GridBagLayout());
        private int y;

        /** A new tab of the dialog; its rows start at the top. */
        Row(javax.swing.JTabbedPane tabs, String title) {
            p.setOpaque(false);
            JPanel tab = new JPanel(new BorderLayout());
            tab.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
            tab.add(p, BorderLayout.NORTH);
            tabs.addTab(title, tab);
        }

        void section(String title) {
            JLabel l = new JLabel(title);
            l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 1f));
            l.setForeground(Theme.accent());
            GridBagConstraints c = gbc(0, y++, 2);
            c.insets = new Insets(y == 1 ? 0 : 14, 0, 6, 0);
            p.add(l, c);
            GridBagConstraints s = gbc(0, y++, 2);
            s.insets = new Insets(0, 0, 6, 0);
            p.add(new JSeparator(), s);
        }

        void field(String label, JComponent comp) {
            GridBagConstraints c = gbc(0, y, 1);
            c.insets = new Insets(3, 0, 3, 12);
            c.anchor = GridBagConstraints.LINE_END;
            c.fill = GridBagConstraints.NONE;
            p.add(new JLabel(label), c);
            GridBagConstraints d = gbc(1, y++, 1);
            d.insets = new Insets(3, 0, 3, 0);
            d.fill = (comp instanceof JCheckBox || comp instanceof JButton || comp instanceof JSpinner)
                    ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
            p.add(comp, d);
        }

        JLabel hint(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(UIManager.getColor("Label.disabledForeground"));
            l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
            GridBagConstraints c = gbc(1, y++, 1);
            c.insets = new Insets(0, 2, 4, 0);
            p.add(l, c);
            return l;
        }

        private static GridBagConstraints gbc(int x, int y, int w) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x; c.gridy = y; c.gridwidth = w;
            c.anchor = GridBagConstraints.LINE_START;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = x == 1 ? 1 : 0;
            return c;
        }
    }
}
