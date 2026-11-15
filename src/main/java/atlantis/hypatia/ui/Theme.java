package atlantis.hypatia.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.UIManager;
import atlantis.hypatia.editor.EditorPalette;

/** Look-and-feel setup: FlatLaf, tinted with the colours of the Hypatia icon. */
public final class Theme {

    private static boolean dark = true;

    private Theme() {}

    public static boolean isDark() {
        return dark;
    }

    public static EditorPalette palette() {
        return dark ? EditorPalette.DARK : EditorPalette.LIGHT;
    }

    /** Installs the look and feel. Call {@code FlatLaf.updateUI()} afterwards when switching at runtime. */
    public static void install(boolean useDark) {
        dark = useDark;
        FlatLaf.setGlobalExtraDefaults(useDark
                ? Map.of("@background", "#1B2635", "@foreground", "#DCD5C4",
                         "@accentColor", "#4FB0BB", "@selectionBackground", "#2E5A6E")
                : Map.of("@background", "#F4F1EA", "@foreground", "#2A2733",
                         "@accentColor", "#1F6E7A"));
        if (useDark) FlatDarkLaf.setup(); else FlatLightLaf.setup();

        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.selectedBackground", palette().background());
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("SplitPaneDivider.style", "plain");
    }

    public static Color accent() {
        return dark ? new Color(0x4FB0BB) : new Color(0x1F6E7A);
    }

    public static Color gold() {
        return dark ? new Color(0xE0B562) : new Color(0x8B5E14);
    }

    public static Color error() {
        return dark ? new Color(0xEF7A6A) : new Color(0xC0392B);
    }

    public static Color warning() {
        return dark ? new Color(0xE6B85C) : new Color(0xA86A00);
    }

    /** Squiggle under style problems. */
    public static Color stylePurple() {
        return dark ? new Color(0xB98CF2) : new Color(0x8A4FD1);
    }

        /** Squiggle under misspelled words: a lighter red. */
    public static Color spellSquiggle() {
        return dark ? new Color(0xF4928A) : new Color(0xE8837C);
    }

    public static Color success() {
        return dark ? new Color(0x8FC77E) : new Color(0x3C7A2A);
    }

    /**
     * A 1px separator line on the given sides, in the current theme's border colour
     * (looked up when painting, so it follows theme switches).
     */
    public static javax.swing.border.Border line(int top, int left, int bottom, int right) {
        return new javax.swing.border.AbstractBorder() {
            @Override
            public void paintBorder(java.awt.Component c, java.awt.Graphics g, int x, int y, int w, int h) {
                g.setColor(UIManager.getColor("Component.borderColor"));
                if (top > 0) g.fillRect(x, y, w, 1);
                if (bottom > 0) g.fillRect(x, y + h - 1, w, 1);
                if (left > 0) g.fillRect(x, y, 1, h);
                if (right > 0) g.fillRect(x + w - 1, y, 1, h);
            }

            @Override
            public Insets getBorderInsets(java.awt.Component c, Insets i) {
                i.set(top, left, bottom, right);
                return i;
            }
        };
    }

    // --- fonts ------------------------------------------------------------------

    private static final List<String> PREFERRED_MONO = List.of(
            "JetBrains Mono", "Hack", "Fira Code", "Source Code Pro", "Cascadia Code",
            "Noto Sans Mono", "DejaVu Sans Mono", "Liberation Mono",
            "SF Mono", "Menlo", "Cascadia Mono", "Consolas", "Monospaced"); // macOS, Windows

    public static Font editorFont(String family, int size) {
        Set<String> installed = Set.copyOf(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        if (family != null && !family.isBlank() && installed.contains(family)) {
            return new Font(family, Font.PLAIN, size);
        }
        for (String f : PREFERRED_MONO) {
            if (installed.contains(f)) return new Font(f, Font.PLAIN, size);
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }
}
