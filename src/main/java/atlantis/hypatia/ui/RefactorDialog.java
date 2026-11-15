package atlantis.hypatia.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Edit > Refactor: replaces the selected text (typically a label) everywhere in the body of
 * the project's files. Only complete keys are replaced: a match must not be glued to letters,
 * digits or _ , nor to a : - . / that continues the key (so eq:1 does not hit eq:10).
 */
public class RefactorDialog extends JDialog {

    private static final String WORD = "[\\p{L}\\p{N}_]";
    private static final String JOIN = "[:\\-./]";
    private static final Pattern BEGIN_DOCUMENT =
            Pattern.compile("(?m)^(?:[^%\\n\\\\]|\\\\.)*?\\\\begin\\s*\\{document\\}");

    private final JTextField field = new JTextField(30);
    private final JLabel summary = new JLabel(" ");
    private final JButton ok = new JButton("Replace");
    private final Map<Path, Integer> counts = new LinkedHashMap<>();
    private String result;

    /**
     * @param files  the project's files, each with the offset where its body starts
     * @param reader text of a file (from its editor tab if open)
     */
    public RefactorDialog(JFrame owner, String old, Map<Path, Integer> files, Function<Path, String> reader,
                          Path baseDir) {
        super(owner, "Refactoring", true);
        Pattern p = pattern(old);
        int total = 0;
        for (Map.Entry<Path, Integer> f : files.entrySet()) {
            String text = reader.apply(f.getKey());
            if (text == null) continue;
            int n = matches(p, text, f.getValue()).size();
            if (n > 0) counts.put(f.getKey(), n);
            total += n;
        }

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));
        JLabel title = new JLabel("Replace “" + old + "” everywhere in the project by:");
        title.setAlignmentX(LEFT_ALIGNMENT);
        form.add(title);
        form.add(javax.swing.Box.createVerticalStrut(6));
        field.setText(old);
        field.selectAll();
        field.setAlignmentX(LEFT_ALIGNMENT);
        form.add(field);
        form.add(javax.swing.Box.createVerticalStrut(8));

        StringBuilder sb = new StringBuilder("<html>");
        sb.append(total).append(total == 1 ? " occurrence" : " occurrences");
        sb.append(counts.size() == 1 ? " in 1 file" : " in " + counts.size() + " files");
        if (!counts.isEmpty()) sb.append(":");
        for (Map.Entry<Path, Integer> c : counts.entrySet()) {
            Path f = c.getKey();
            String name = baseDir != null && f.startsWith(baseDir) ? baseDir.relativize(f).toString()
                    : f.getFileName().toString();
            sb.append("<br>&nbsp;&nbsp;").append(escape(name)).append(" (").append(c.getValue()).append(")");
        }
        sb.append("<br><br>Changed files are opened in tabs, unsaved; Undo reverts each.</html>");
        summary.setText(sb.toString());
        summary.setForeground(UIManager.getColor("Label.disabledForeground"));
        summary.setFont(summary.getFont().deriveFont(Font.PLAIN));
        summary.setAlignmentX(LEFT_ALIGNMENT);
        form.add(summary);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> {
            result = field.getText();
            dispose();
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { check(old); }
            @Override public void removeUpdate(DocumentEvent e) { check(old); }
            @Override public void changedUpdate(DocumentEvent e) { check(old); }
        });
        check(old);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private void check(String old) {
        String t = field.getText();
        ok.setEnabled(!counts.isEmpty() && !t.isEmpty() && !t.equals(old) && !t.contains("\n"));
    }

    /** Shows the dialog; the new text, or null if cancelled. */
    public String showDialog() {
        setVisible(true);
        return result;
    }

    /** The files that contain the text, with the number of occurrences. */
    public Map<Path, Integer> counts() {
        return counts;
    }

    /** Matches of complete keys equal to {@code old}. */
    public static Pattern pattern(String old) {
        return Pattern.compile("(?<!" + WORD + ")(?<!" + WORD + JOIN + ")" + Pattern.quote(old)
                + "(?!" + WORD + ")(?!" + JOIN + WORD + ")");
    }

    /** Offsets {start, end} of the matches at or after {@code from}. */
    public static List<int[]> matches(Pattern p, String text, int from) {
        List<int[]> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        m.region(Math.min(from, text.length()), text.length());
        m.useTransparentBounds(true); // look-behind may see the text before the body
        while (m.find()) out.add(new int[] {m.start(), m.end()});
        return out;
    }

    /** Where the body starts: after an uncommented \begin{document}, else 0 (included files). */
    public static int bodyStart(String text) {
        Matcher m = BEGIN_DOCUMENT.matcher(text);
        return m.find() ? m.end() : 0;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
