package atlantis.hypatia.tex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The keys in citation commands: \cite{a,b}, \citep[p.~3]{a}, \parencite*{a},
 * \cites[5]{a}[7]{b}, \nocite{a}, ... Comments are skipped. Offsets refer to the text.
 */
public final class Citations {

    /** A key and where it is in the text. */
    public record Key(String key, int start, int end) {}

    private static final Pattern COMMAND = Pattern.compile("\\\\([A-Za-z]*[Cc]ite[A-Za-z]*)\\*?");
    /** Commands with "cite" in their name that take no keys. */
    private static final Set<String> NOT_CITATIONS = Set.of(
            "citestyle", "citeindextrue", "citeindexfalse", "citeindextype", "citetrackertrue",
            "citetrackerfalse", "citereset", "citesetup", "citeauthorstyle");

    private Citations() {}

    public static List<Key> find(String text) {
        String code = blankComments(text);
        List<Key> out = new ArrayList<>();
        Matcher m = COMMAND.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            if (NOT_CITATIONS.contains(name.toLowerCase(Locale.ROOT))) continue;
            boolean multi = name.endsWith("cites") || name.endsWith("Cites"); // \cites{a}{b}, \parencites...
            int i = m.end();
            int groups = 0;
            while (true) {
                i = skipSpace(code, i);
                if (i < code.length() && (code.charAt(i) == '[' || code.charAt(i) == '(')) {
                    int e = close(code, i, code.charAt(i), code.charAt(i) == '[' ? ']' : ')');
                    if (e < 0) break;
                    i = e + 1;
                } else if (i < code.length() && code.charAt(i) == '{') {
                    int e = close(code, i, '{', '}');
                    if (e < 0) break;
                    keys(text, i + 1, e, out);
                    i = e + 1;
                    groups++;
                    if (!multi) break;
                } else {
                    break;
                }
            }
            if (groups == 0) continue;
        }
        return out;
    }

    private static void keys(String text, int from, int to, List<Key> out) {
        int s = from;
        for (int i = from; i <= to; i++) {
            if (i == to || text.charAt(i) == ',') {
                int a = s;
                int b = i;
                while (a < b && Character.isWhitespace(text.charAt(a))) a++;
                while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
                String key = text.substring(a, b);
                if (!key.isEmpty() && !key.equals("*") && key.indexOf('\\') < 0 && key.indexOf('#') < 0) {
                    out.add(new Key(key, a, b));
                }
                s = i + 1;
            }
        }
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int close(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length() && i < open + 5000; i++) {
            char ch = s.charAt(i);
            if (ch == '\\') i++;
            else if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    /** Replaces % comments by spaces, keeping all offsets. */
    public static String blankComments(String text) {
        char[] c = text.toCharArray();
        boolean comment = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\n') comment = false;
            else if (comment) c[i] = ' ';
            else if (c[i] == '\\') i++;
            else if (c[i] == '%') {
                comment = true;
                c[i] = ' ';
            }
        }
        return new String(c);
    }
}
