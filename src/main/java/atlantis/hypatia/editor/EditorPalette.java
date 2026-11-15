package atlantis.hypatia.editor;

import java.awt.Color;
import java.awt.Font;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Editor colours. The dark palette takes its tones from the Hypatia icon:
 * deep night blue, parchment, gold, teal, terracotta and olive.
 */
public record EditorPalette(
        Color background, Color foreground, Color currentLine, Color selection, Color caret,
        Color gutterBackground, Color lineNumber, Color currentLineNumber, Color gutterBorder,
        Color comment, Color command, Color structure, Color envName, Color refKey,
        Color math, Color mathCommand, Color mathDelimiter, Color special, Color brace,
        Color verbatim, Color bracketMatch, Color foldIndicator, Color markAll,
        Color errorLine, Color flashLine) {

    public static final EditorPalette DARK = new EditorPalette(
            rgb(0x16212E), rgb(0xE4DCC8), rgb(0x1C2A3A), rgb(0x2D4B63), rgb(0xE6C27A),
            rgb(0x131C27), rgb(0x4F6478), rgb(0xC9A96A), rgb(0x1F2C3B),
            rgb(0x6C8193), rgb(0x6CC4CC), rgb(0xE0B562), rgb(0xF0D08C), rgb(0xA9C97E),
            rgb(0xE39A78), rgb(0xC5A3E8), rgb(0xEB8E66), rgb(0xC9A96A), rgb(0x8FA6BA),
            rgb(0x9DB8A0), rgb(0x34546E), rgb(0x6A8296), rgb(0x5E4C22),
            new Color(220, 80, 70, 60), new Color(230, 194, 122, 75));

    public static final EditorPalette LIGHT = new EditorPalette(
            rgb(0xFCFAF5), rgb(0x2A2733), rgb(0xF3EEE2), rgb(0xCFE3E6), rgb(0x1F6E7A),
            rgb(0xF4F0E6), rgb(0xA59F90), rgb(0x8A6A28), rgb(0xE6E0D2),
            rgb(0x8A8F98), rgb(0x1D6F7A), rgb(0x8B5E14), rgb(0xA8721E), rgb(0x4F7A28),
            rgb(0xB4502E), rgb(0x6B4BA3), rgb(0xC0461F), rgb(0x8B5E14), rgb(0x6A7380),
            rgb(0x4A6B52), rgb(0xD7E8EA), rgb(0x9A9488), rgb(0xF5DC8C),
            new Color(220, 60, 50, 45), new Color(200, 150, 40, 70));

    private static Color rgb(int v) {
        return new Color(v);
    }

    /** Applies the palette and font to a text area and its gutter. */
    public void apply(RSyntaxTextArea area, RTextScrollPane scroll, Font font) {
        area.setFont(font);
        area.setBackground(background);
        area.setForeground(foreground);
        area.setCaretColor(caret);
        area.setCurrentLineHighlightColor(currentLine);
        area.setFadeCurrentLineHighlight(false);
        area.setSelectionColor(selection);
        area.setUseSelectedTextColor(false);
        area.setMatchedBracketBGColor(bracketMatch);
        area.setMatchedBracketBorderColor(bracketMatch);
        area.setPaintMatchedBracketPair(true);
        area.setMarginLineEnabled(false);
        area.setMarkAllHighlightColor(markAll);

        SyntaxScheme scheme = new SyntaxScheme(font);
        for (int i = 0; i < scheme.getStyleCount(); i++) {
            Style s = scheme.getStyle(i);
            if (s != null) {
                s.foreground = foreground;
                s.background = null;
                s.font = font;
                s.underline = false;
            }
        }
        Font bold = font.deriveFont(Font.BOLD);
        Font italic = font.deriveFont(Font.ITALIC);
        style(scheme, LatexTokenMaker.COMMENT, comment, italic);
        style(scheme, LatexTokenMaker.COMMENT_ENV, comment, italic);
        style(scheme, LatexTokenMaker.COMMAND, command, font);
        style(scheme, LatexTokenMaker.STRUCTURE, structure, bold);
        style(scheme, LatexTokenMaker.ENV_NAME, envName, font);
        style(scheme, LatexTokenMaker.REF_KEY, refKey, font);
        style(scheme, LatexTokenMaker.MATH, math, font);
        style(scheme, LatexTokenMaker.MATH_COMMAND, mathCommand, font);
        style(scheme, LatexTokenMaker.MATH_DELIMITER, mathDelimiter, bold);
        style(scheme, LatexTokenMaker.SPECIAL, special, bold);
        style(scheme, LatexTokenMaker.BRACE, brace, font);
        style(scheme, LatexTokenMaker.VERBATIM, verbatim, font);
        area.setSyntaxScheme(scheme);

        if (scroll != null) {
            Gutter g = scroll.getGutter();
            g.setBackground(gutterBackground);
            g.setLineNumberColor(lineNumber);
            g.setCurrentLineNumberColor(currentLineNumber);
            g.setLineNumberFont(font.deriveFont(font.getSize2D() - 1f));
            g.setBorderColor(gutterBorder);
            g.setFoldIndicatorForeground(foldIndicator);
            g.setFoldBackground(gutterBackground);
            g.setArmedFoldBackground(currentLine);
            g.setFoldIndicatorBackground(gutterBackground);
            scroll.getViewport().setBackground(background);
            scroll.setBorder(null); // the look and feel re-installs one when the theme changes
        }
        area.repaint();
    }

    private static void style(SyntaxScheme scheme, int type, Color fg, Font f) {
        Style s = scheme.getStyle(type);
        s.foreground = fg;
        s.font = f;
    }
}
