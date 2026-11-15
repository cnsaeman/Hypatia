package atlantis.hypatia.tex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A command line with placeholders such as %f, split into arguments the way a
 * shell would (quotes group words) but run without a shell. Placeholders are
 * substituted per argument, so file names with spaces or quotes need no escaping.
 *
 * Placeholders:
 *   %f main file name (main.tex)     %n main file without extension (main)
 *   %d main file directory           %p PDF path (absolute)
 *   %t current .tex file (absolute)  %l current line   %c current column
 *   %% a literal percent sign
 */
public final class CommandTemplate {

    private CommandTemplate() {}

    public static List<String> build(String template, Map<Character, String> values) {
        List<String> out = new ArrayList<>();
        for (String word : split(template)) {
            out.add(substitute(word, values));
        }
        return out;
    }

    static List<String> split(String s) {
        return split(s, atlantis.hypatia.Platform.WINDOWS);
    }

    /**
     * On Windows a backslash is a path separator and is kept, except in front of a double
     * quote (\" is a literal quote, as in Windows command lines).
     */
    static List<String> split(String s, boolean windows) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < s.length() && (!windows || s.charAt(i + 1) == '"')) cur.append(s.charAt(++i));
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < s.length() && (!windows || s.charAt(i + 1) == '"')) {
                cur.append(s.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
            } else {
                cur.append(c);
                inWord = true;
            }
        }
        if (inWord) words.add(cur.toString());
        return words;
    }

    private static String substitute(String word, Map<Character, String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == '%' && i + 1 < word.length()) {
                char k = word.charAt(i + 1);
                if (k == '%') {
                    sb.append('%');
                    i++;
                    continue;
                }
                String v = values.get(k);
                if (v != null) {
                    sb.append(v);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
