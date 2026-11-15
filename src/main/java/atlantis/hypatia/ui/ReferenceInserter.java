package atlantis.hypatia.ui;

import atlantis.hypatia.editor.EditorTab;
import atlantis.hypatia.tex.Bibliography;
import atlantis.hypatia.tex.Labels;
import atlantis.hypatia.tex.ProjectScanner;
import java.awt.Color;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * The citation (BibTeX) and reference (label) search popups, and inserting
 * the chosen key into the editor.
 *
 * Insertion: inside the braces of a \cite... or \...ref command the key is
 * inserted there (replacing what was typed since the last comma, which also
 * pre-fills the search); otherwise "\cite{key}", "\ref{key}" or, for labels in
 * equation-like environments, "\eqref{key}" is inserted at the caret. The
 * commands can be changed in hypatia.properties (cite.command, ref.command,
 * ref.mathCommand).
 */
final class ReferenceInserter {

    private static final int MAX_CITATIONS = 10;
    private static final int MAX_LABELS = 50;

    private final MainFrame frame;

    ReferenceInserter(MainFrame frame) {
        this.frame = frame;
    }

    // --- citations ------------------------------------------------------------------------

    void insertCitation() {
        EditorTab tab = frame.current();
        if (tab == null) return;
        Path root = frame.projectRoot(tab);
        if (root == null) return;
        ProjectScanner scanner = new ProjectScanner(frame::textOf);
        List<Path> texFiles = new ArrayList<>(scanner.texFiles(root));
        if (tab.file() != null && !texFiles.contains(tab.file())) texFiles.add(tab.file());
        List<Path> bibFiles = scanner.bibFiles(root, texFiles);
        if (bibFiles.isEmpty()) {
            frame.status("No .bib file found for " + root.getFileName()
                    + " (looked for % !TeX bibliography, \\bibliography, \\addbibresource and *.bib next to it)", true);
            return;
        }
        Bibliography bib = Bibliography.load(bibFiles, frame::openText);
        String fileNames = bibFiles.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));

        RSyntaxTextArea area = tab.area();
        Argument arg = Argument.at(area, "cite");
        PreviewPane preview = new PreviewPane(frame.editorFont());
        SearchPopup<Bibliography.Entry> popup = new SearchPopup<>(frame, "Insert citation",
                "Words from authors and title…", arg.partial(),
                q -> {
                    Bibliography.SearchResult r = bib.search(q, MAX_CITATIONS);
                    return new SearchPopup.Result<>(r.entries(),
                            count(r.entries().size(), r.total(), "entry", "entries", q.isBlank())
                                    + "   ·   " + fileNames + " (" + bib.size() + " entries)");
                },
                new BibRenderer(),
                e -> {
                    if (e == null) preview.clear();
                    else preview.show(e.file().getFileName() + ":" + e.line(), e.source(), e.line(), 0);
                },
                preview);
        Bibliography.Entry chosen = popup.choose();
        SwingUtilities.invokeLater(area::requestFocusInWindow);
        if (chosen != null) {
            arg.insert(area, frame.settings().get("cite.command", "\\cite"), chosen.key());
        }
    }

    // --- labels ---------------------------------------------------------------------------

    void insertReference() {
        EditorTab tab = frame.current();
        if (tab == null) return;
        Path root = frame.projectRoot(tab);
        if (root == null) return;
        ProjectScanner scanner = new ProjectScanner(frame::textOf);
        List<Path> files = new ArrayList<>(scanner.texFiles(root));
        if (tab.file() != null && !files.contains(tab.file())) files.add(tab.file());
        Labels labels = Labels.collect(files, frame::textOf);
        if (labels.size() == 0) {
            frame.status("No \\label found in " + root.getFileName() + " and the files it includes", true);
            return;
        }

        RSyntaxTextArea area = tab.area();
        Argument arg = Argument.at(area, "ref");
        Path base = root.getParent();
        PreviewPane preview = new PreviewPane(frame.editorFont());
        SearchPopup<Labels.Label> popup = new SearchPopup<>(frame, "Insert reference",
                "Words from the label or its surroundings…", arg.partial(),
                q -> {
                    Labels.SearchResult r = labels.search(q, MAX_LABELS);
                    return new SearchPopup.Result<>(r.labels(),
                            count(r.labels().size(), r.total(), "label", "labels", q.isBlank())
                                    + "   ·   " + files.size() + (files.size() == 1 ? " file" : " files"));
                },
                new LabelRenderer(base),
                l -> {
                    if (l == null) preview.clear();
                    else preview.show(relative(base, l.file()) + ":" + l.line(), l.context(), l.contextStart(), l.line());
                },
                preview);
        Labels.Label chosen = popup.choose();
        SwingUtilities.invokeLater(area::requestFocusInWindow);
        if (chosen != null) {
            String cmd = chosen.inMath()
                    ? frame.settings().get("ref.mathCommand", "\\eqref")
                    : frame.settings().get("ref.command", "\\ref");
            arg.insert(area, cmd, chosen.key());
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private static String count(int shown, int total, String one, String many, boolean all) {
        if (total == 0) return "No match";
        String noun = total == 1 ? one : many;
        if (all) return shown < total ? "First " + shown + " of " + total + " " + many : total + " " + noun;
        return shown < total ? shown + " of " + total + " matches" : total + (total == 1 ? " match" : " matches");
    }

    private static String relative(Path base, Path file) {
        try {
            return base.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Where the key goes: inside the argument of a \cite.../\...ref command at the caret
     * (replacing the partial key typed after the last comma), or a new command at the caret.
     */
    record Argument(boolean inside, int start, int end, String partial) {

        static Argument at(RSyntaxTextArea a, String kind) {
            int caret = a.getCaretPosition();
            if (a.getSelectionStart() != a.getSelectionEnd()) {
                int e = a.getSelectionEnd();
                return new Argument(false, e, e, "");
            }
            try {
                int lineStart = a.getLineStartOffset(a.getLineOfOffset(caret));
                String before = a.getText(lineStart, caret - lineStart);
                Matcher m = Pattern.compile("\\\\[A-Za-z]*" + kind + "[A-Za-z]*\\*?\\s*(?:\\[[^\\]]*\\]\\s*)*\\{([^{}]*)$")
                        .matcher(before);
                if (m.find()) {
                    String argText = m.group(1);
                    String partial = argText.substring(argText.lastIndexOf(',') + 1).stripLeading();
                    return new Argument(true, caret - partial.length(), caret, partial);
                }
            } catch (BadLocationException ignored) {
                // fall through
            }
            return new Argument(false, caret, caret, "");
        }

        void insert(RSyntaxTextArea a, String command, String key) {
            String text = inside ? key : command + "{" + key + "}";
            a.beginAtomicEdit();
            try {
                a.getDocument().remove(start, end - start);
                a.getDocument().insertString(start, text, null);
                a.setCaretPosition(start + text.length());
            } catch (BadLocationException ignored) {
                // document changed while the popup was open
            } finally {
                a.endAtomicEdit();
            }
        }
    }

    /** Title in bold, then authors · year · journal and the key. */
    static final class BibRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            Bibliography.Entry e = (Bibliography.Entry) value;
            int width = Math.max(200, list.getWidth() - 64);
            Color dim = selected ? list.getSelectionForeground() : UIManager.getColor("Label.disabledForeground");
            Color key = selected ? list.getSelectionForeground() : Theme.gold();
            List<String> meta = new ArrayList<>();
            for (String s : new String[] {e.shortAuthors(), e.year(), e.venue()}) if (!s.isBlank()) meta.add(s);
            String title = e.title().isEmpty() ? "(no title)" : e.title();
            String html = "<html><body style='width:" + width + "px'><b>" + esc(title) + "</b><br>"
                    + "<span style='color:" + hex(dim) + "'>" + esc(String.join(" · ", meta)) + "</span>"
                    + "&nbsp;&nbsp;&nbsp;<span style='color:" + hex(key) + "'>" + esc(e.key()) + "</span></body></html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(list, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            return c;
        }
    }

    /** Label key, then environment and file:line. */
    private static final class LabelRenderer extends DefaultListCellRenderer {
        private final Path base;

        LabelRenderer(Path base) {
            this.base = base;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            Labels.Label l = (Labels.Label) value;
            Color dim = selected ? list.getSelectionForeground() : UIManager.getColor("Label.disabledForeground");
            String where = (l.environment() == null ? "" : l.environment() + " · ")
                    + relative(base, l.file()) + ":" + l.line();
            String html = "<html><b>" + esc(l.key()) + "</b><br><span style='color:" + hex(dim) + "'>"
                    + esc(where) + "</span></html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(list, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            return c;
        }
    }
}
