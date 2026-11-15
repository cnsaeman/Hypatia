package atlantis.hypatia.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/** Shown when no file is open: logo, New/Open and recent files. */
public class WelcomePanel extends JPanel {

    private final Runnable onNew;
    private final Runnable onOpen;
    private final Consumer<Path> onOpenRecent;

    public WelcomePanel(Runnable onNew, Runnable onOpen, Consumer<Path> onOpenRecent) {
        super(new GridBagLayout());
        this.onNew = onNew;
        this.onOpen = onOpen;
        this.onOpenRecent = onOpenRecent;
    }

    public void refresh(List<Path> recent) {
        removeAll();
        setBackground(Theme.palette().background());

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        Icon logo = Icons.appIcon(128);
        if (logo != null) col.add(centered(new JLabel(logo)));
        col.add(Box.createVerticalStrut(14));

        JLabel title = new JLabel("Hypatia");
        title.setFont(new Font(Font.SERIF, Font.BOLD, 34));
        title.setForeground(Theme.gold());
        col.add(centered(title));
        JLabel sub = new JLabel("LaTeX editor");
        sub.setForeground(UIManager.getColor("Label.disabledForeground"));
        col.add(centered(sub));
        col.add(Box.createVerticalStrut(22));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        JButton newB = new JButton("New file", Icons.of(Icons.Glyph.NEW));
        newB.addActionListener(e -> onNew.run());
        JButton openB = new JButton("Open…", Icons.of(Icons.Glyph.OPEN));
        openB.addActionListener(e -> onOpen.run());
        buttons.add(newB);
        buttons.add(openB);
        col.add(centered(buttons));

        List<Path> existing = recent.stream().filter(Files::exists).limit(8).toList();
        if (!existing.isEmpty()) {
            col.add(Box.createVerticalStrut(22));
            JLabel rec = new JLabel("Recent");
            rec.setFont(rec.getFont().deriveFont(Font.BOLD));
            rec.setForeground(Theme.accent());
            col.add(centered(rec));
            col.add(Box.createVerticalStrut(6));
            Color dim = UIManager.getColor("Label.disabledForeground");
            String dimHex = String.format("#%02x%02x%02x", dim.getRed(), dim.getGreen(), dim.getBlue());
            for (Path p : existing) {
                String parent = p.getParent() == null ? "" : shortHome(p.getParent().toString());
                JButton b = new JButton("<html>" + p.getFileName() + "&nbsp;&nbsp;<span style='color:"
                        + dimHex + "'>" + parent + "</span></html>");
                b.putClientProperty("JButton.buttonType", "borderless");
                b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                b.setHorizontalAlignment(SwingConstants.CENTER);
                b.addActionListener(e -> onOpenRecent.accept(p));
                col.add(centered(b));
            }
        }
        col.setBorder(BorderFactory.createEmptyBorder(20, 20, 40, 20));
        add(col);
        revalidate();
        repaint();
    }

    private static String shortHome(String path) {
        String home = System.getProperty("user.home");
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

    private static JPanel centered(java.awt.Component c) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.add(Box.createHorizontalGlue());
        p.add(c);
        p.add(Box.createHorizontalGlue());
        p.setAlignmentX(CENTER_ALIGNMENT);
        return p;
    }
}
