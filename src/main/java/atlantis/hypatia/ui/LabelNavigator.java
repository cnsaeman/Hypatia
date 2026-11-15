package atlantis.hypatia.ui;

import atlantis.hypatia.editor.EditorTab;
import atlantis.hypatia.tex.ProjectScanner;
import atlantis.hypatia.tex.References;
import java.awt.Color;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Following labels: from a reference (\ref, \eqref, \cref{a,b}, ...) at the caret to its
 * \label, and from a \label (or a reference) to a list of all places that refer to it.
 */
final class LabelNavigator {

    private final MainFrame frame;

    LabelNavigator(MainFrame frame) {
        this.frame = frame;
    }

    /** Opens the file with the \label of the reference at the caret, at that label. */
    void goToDefinition() {
        EditorTab tab = frame.current();
        if (tab == null) return;
        References.Key k = keyAtCaret(tab);
        if (k == null) {
            frame.status("Put the cursor on a reference such as \\ref{…} or \\cref{a,b} to go to its \\label", true);
            return;
        }
        if (k.definition()) {
            frame.status("This is the \\label of " + k.key() + "; " + frame.shortcutText("latex.labelUses", "Show Uses of Label")
                    + " lists the places that refer to it", false);
            return;
        }
        Project p = project(tab);
        if (p == null) return;
        List<References.Definition> defs = References.definitions(p.files(), frame::textOf, k.key());
        if (defs.isEmpty()) {
            frame.status("No \\label{" + k.key() + "} in " + p.root().getFileName() + " and the files it includes", true);
            return;
        }
        References.Definition d = defs.get(0);
        EditorTab t = frame.open(d.file());
        if (t == null) return;
        frame.adoptRoot(t, p.root());
        t.goToLine(d.line(), d.column());
        if (defs.size() > 1) {
            frame.status("\\label{" + k.key() + "} is defined " + defs.size() + " times; this is the first", true);
        } else {
            frame.status(relative(p.root().getParent(), d.file()) + ":" + d.line() + "  \\label{" + k.key() + "}", false);
        }
    }

    /** Lists every reference to the label at the caret, with its surroundings; Enter goes there. */
    void showUses() {
        EditorTab tab = frame.current();
        if (tab == null) return;
        References.Key k = keyAtCaret(tab);
        if (k == null) {
            frame.status("Put the cursor in a \\label{…} (or a reference) to list the places that refer to it", true);
            return;
        }
        Project p = project(tab);
        if (p == null) return;
        String key = k.key();
        List<References.Use> uses = References.uses(p.files(), frame::textOf, key);
        if (uses.isEmpty()) {
            frame.status("Nothing refers to " + key + " in " + p.root().getFileName() + " and the files it includes", false);
            return;
        }
        long fileCount = uses.stream().map(References.Use::file).distinct().count();
        String info = uses.size() + (uses.size() == 1 ? " use" : " uses") + " in " + fileCount
                + (fileCount == 1 ? " file" : " files");

        RSyntaxTextArea area = tab.area();
        Path base = p.root().getParent();
        PreviewPane preview = new PreviewPane(frame.editorFont());
        SearchPopup<References.Use> popup = new SearchPopup<>(frame, "Uses of " + key, null, "",
                q -> new SearchPopup.Result<>(uses, info),
                new UseRenderer(base),
                u -> {
                    if (u == null) preview.clear();
                    else preview.show(relative(base, u.file()) + ":" + u.line(), u.context(), u.contextStart(), u.line());
                },
                preview);
        popup.setKeysHint("↑↓ select   Enter go to   Esc close");
        References.Use chosen = popup.choose();
        if (chosen == null) {
            SwingUtilities.invokeLater(area::requestFocusInWindow);
            return;
        }
        EditorTab t = frame.open(chosen.file());
        if (t == null) return;
        frame.adoptRoot(t, p.root());
        t.goToLine(chosen.line(), chosen.column());
        frame.status(relative(base, chosen.file()) + ":" + chosen.line() + "  \\" + chosen.command() + "{" + key + "}", false);
    }

    private static References.Key keyAtCaret(EditorTab tab) {
        RSyntaxTextArea a = tab.area();
        return References.at(a.getText(), a.getCaretPosition());
    }

    private record Project(Path root, List<Path> files) {}

    /** The main file and every file it includes (plus the current one), or null with a status message. */
    private Project project(EditorTab tab) {
        Path root = frame.projectRoot(tab);
        if (root == null) return null;
        List<Path> files = new ArrayList<>(new ProjectScanner(frame::textOf).texFiles(root));
        if (tab.file() != null && !files.contains(tab.file())) files.add(tab.file());
        return new Project(root, files);
    }

    private static String relative(Path base, Path file) {
        try {
            return base == null ? file.toString() : base.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /** file:line and the command, then the line itself. */
    private static final class UseRenderer extends DefaultListCellRenderer {
        private final Path base;

        UseRenderer(Path base) {
            this.base = base;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            References.Use u = (References.Use) value;
            Color dim = selected ? list.getSelectionForeground() : UIManager.getColor("Label.disabledForeground");
            String[] lines = u.context().split("\n", -1);
            String text = lines[Math.min(lines.length - 1, u.line() - u.contextStart())].strip();
            if (text.length() > 140) text = text.substring(0, 140) + "…";
            String html = "<html><b>" + ReferenceInserter.esc(relative(base, u.file()) + ":" + u.line()) + "</b>"
                    + "&nbsp;&nbsp;<span style='color:" + ReferenceInserter.hex(dim) + "'>\\" + ReferenceInserter.esc(u.command())
                    + "</span><br><span style='color:" + ReferenceInserter.hex(dim) + "'>"
                    + ReferenceInserter.esc(text) + "</span></html>";
            JLabel c = (JLabel) super.getListCellRendererComponent(list, html, index, selected, focus);
            c.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
            return c;
        }
    }
}
