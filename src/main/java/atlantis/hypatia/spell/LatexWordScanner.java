package atlantis.hypatia.spell;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the words to spell-check in LaTeX source.
 *
 * Only the document body is scanned; comments, math and commands are skipped, except
 * the arguments that {@link SpellConfig} marks as text. LaTeX accents (\"o, {\"o},
 * \"{o}, \ss, ...) and, for German, babel shorthands ("a, "s) are resolved, so the
 * word is checked as "schön" while its source range covers "sch\"on".
 */
public final class LatexWordScanner {

    /** A word: its text (accents resolved, NFC) and its source range [start, end). */
    public record Word(String text, int start, int end) {}

    private static final Pattern BEGIN_DOCUMENT = Pattern.compile("\\\\begin\\s*\\{document\\}");
    private static final Pattern END_DOCUMENT = Pattern.compile("\\\\end\\s*\\{document\\}");

    private static final Map<Character, String> ACCENTS = Map.ofEntries(
            Map.entry('`', "̀"), Map.entry('\'', "́"), Map.entry('^', "̂"),
            Map.entry('~', "̃"), Map.entry('=', "̄"), Map.entry('u', "̆"),
            Map.entry('.', "̇"), Map.entry('"', "̈"), Map.entry('r', "̊"),
            Map.entry('H', "̋"), Map.entry('v', "̌"), Map.entry('c', "̧"),
            Map.entry('k', "̨"), Map.entry('d', "̣"), Map.entry('b', "̱"));

    private static final Map<String, String> LETTERS = Map.ofEntries(
            Map.entry("ss", "ß"), Map.entry("SS", "SS"), Map.entry("o", "ø"), Map.entry("O", "Ø"),
            Map.entry("ae", "æ"), Map.entry("AE", "Æ"), Map.entry("oe", "œ"), Map.entry("OE", "Œ"),
            Map.entry("aa", "å"), Map.entry("AA", "Å"), Map.entry("l", "ł"), Map.entry("L", "Ł"),
            Map.entry("i", "ı"), Map.entry("j", "ȷ"));

    private final String s;
    private final SpellConfig config;
    private final boolean german;
    private final List<Word> words = new ArrayList<>();

    private LatexWordScanner(String text, SpellConfig config, boolean german) {
        this.s = text;
        this.config = config;
        this.german = german;
    }

    /** The words of the document body. {@code language} enables babel shorthands for "de...". */
    public static List<Word> scan(String text, SpellConfig config, String language) {
        LatexWordScanner sc = new LatexWordScanner(text, config, language != null && language.startsWith("de"));
        // Body: after \begin{document} (whole file if there is none, e.g. an \input'ed chapter).
        int from = 0;
        int to = text.length();
        Matcher b = BEGIN_DOCUMENT.matcher(text);
        while (b.find()) {
            if (!inComment(text, b.start())) {
                from = b.end();
                break;
            }
        }
        Matcher e = END_DOCUMENT.matcher(text);
        e.region(from, text.length());
        while (e.find()) {
            if (!inComment(text, e.start())) {
                to = e.start();
                break;
            }
        }
        sc.scanRange(from, to, false);
        return sc.words;
    }

    private static boolean inComment(String t, int pos) {
        int ls = t.lastIndexOf('\n', pos - 1) + 1;
        for (int i = ls; i < pos; i++) {
            if (t.charAt(i) == '\\') i++;
            else if (t.charAt(i) == '%') return true;
        }
        return false;
    }

    // --- scanning ---------------------------------------------------------------------------

    /** An open environment and the math state before it. */
    private record Frame(String env, boolean mathBefore) {}

    /** Result of handling a command: next position and math state. */
    private record Next(int pos, boolean math) {}

    /** Scans [from, to); text is collected unless in math mode. */
    private void scanRange(int from, int to, boolean math) {
        Deque<Frame> envs = new ArrayDeque<>();
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '%') {
                i = lineEnd(i, to);
            } else if (c == '$') {
                i += (i + 1 < to && s.charAt(i + 1) == '$') ? 2 : 1;
                math = !math;
            } else if (c == '\\') {
                if (i + 1 >= to) return;
                char d = s.charAt(i + 1);
                if (!math && startsWord(i, to)) {
                    i = readWord(i, to);
                } else if (d == '(' || d == '[') {
                    math = true;
                    i += 2;
                } else if (d == ')' || d == ']') {
                    math = false;
                    i += 2;
                } else if (isLetter(d)) {
                    int nameEnd = i + 1;
                    while (nameEnd < to && isLetter(s.charAt(nameEnd))) nameEnd++;
                    if (nameEnd < to && s.charAt(nameEnd) == '*') nameEnd++;
                    String name = s.substring(i + 1, nameEnd);
                    Next n = command(name, nameEnd, to, envs, math);
                    i = n.pos();
                    math = n.math();
                } else {
                    // control symbol: \\ \, \% \& ... ; \\[2pt] has an optional argument
                    i += 2;
                    if (d == '\\') {
                        if (i < to && s.charAt(i) == '*') i++;
                        i = skipOptional(i, to);
                    }
                }
            } else if (!math && startsWord(i, to)) {
                i = readWord(i, to);
            } else {
                i++;
            }
        }
    }

    /** Handles \name; {@code i} is the position after the name. */
    private Next command(String name, int i, int to, Deque<Frame> envs, boolean math) {
        if (name.equals("begin") || name.equals("end")) {
            int open = skipSpaces(i, to);
            if (open >= to || s.charAt(open) != '{') return new Next(i, math);
            int close = groupEnd(open, to);
            if (close < 0) return new Next(to, math);
            String env = s.substring(open + 1, close).trim();
            int next = close + 1;
            if (name.equals("begin")) {
                if (config.isIgnored(env)) return new Next(skipToEnd(env, next, to), math);
                List<SpellConfig.Arg> spec = config.environment(env);
                next = spec != null ? arguments(spec, next, to) : skipGroups(next, to);
                envs.push(new Frame(env, math));
                return new Next(next, math || config.isMath(env));
            }
            // \end{env}: back to the state before the matching \begin
            for (Frame f : envs) {
                if (f.env().equals(env)) {
                    Frame top;
                    do {
                        top = envs.pop();
                    } while (top != f);
                    return new Next(next, f.mathBefore());
                }
            }
            // opened before this range (unbalanced): leave math if it was a math environment
            return new Next(next, config.isMath(env) ? false : math);
        }
        if (name.equals("verb") || name.equals("verb*")) {
            if (i >= to) return new Next(to, math);
            char delim = s.charAt(i);
            int end = s.indexOf(delim, i + 1);
            return new Next(end < 0 || end >= to ? to : end + 1, math);
        }
        List<SpellConfig.Arg> spec = config.command(name);
        return new Next(spec != null ? arguments(spec, i, to) : skipGroups(i, to), math);
    }

    /** Parses arguments by {@code spec}, scanning the checked ones as text. */
    private int arguments(List<SpellConfig.Arg> spec, int i, int to) {
        for (SpellConfig.Arg a : spec) {
            int j = skipSpaces(i, to);
            if (j >= to) return to;
            char c = s.charAt(j);
            if (a.optional()) {
                if (c != '[') continue;
                int end = bracketEnd(j, to);
                if (end < 0) return to;
                if (a.checked()) scanRange(j + 1, end, false);
                i = end + 1;
            } else if (c == '{') {
                int end = groupEnd(j, to);
                if (end < 0) return to;
                if (a.checked()) scanRange(j + 1, end, false);
                i = end + 1;
            } else if (c == '\\' && j + 1 < to && isLetter(s.charAt(j + 1))) {
                int k = j + 1;
                while (k < to && isLetter(s.charAt(k))) k++;
                i = k; // a single command as argument
            } else {
                i = j + 1; // a single character as argument
            }
        }
        return i;
    }

    /** Skips the [..] and {..} groups that follow directly (arguments of an unlisted command). */
    private int skipGroups(int i, int to) {
        while (i < to) {
            char c = s.charAt(i);
            int end = c == '{' ? groupEnd(i, to) : c == '[' ? bracketEnd(i, to) : -2;
            if (end == -2) return i;
            if (end < 0) return to;
            i = end + 1;
        }
        return i;
    }

    private int skipOptional(int i, int to) {
        int j = skipSpaces(i, to);
        if (j < to && s.charAt(j) == '[') {
            int end = bracketEnd(j, to);
            return end < 0 ? to : end + 1;
        }
        return i;
    }

    /** Position after the \end{env} matching a \begin{env} that ended just before {@code i}. */
    private int skipToEnd(String env, int i, int to) {
        Pattern p = Pattern.compile("\\\\(begin|end)\\s*\\{" + Pattern.quote(env) + "\\}");
        Matcher m = p.matcher(s);
        m.region(i, to);
        int depth = 1;
        while (m.find()) {
            depth += m.group(1).equals("begin") ? 1 : -1;
            if (depth == 0) return m.end();
        }
        return to;
    }

    // --- words --------------------------------------------------------------------------------

    private boolean startsWord(int i, int to) {
        return unit(i, to) != null;
    }

    /** A letter-like unit at i: [text, endPosition], or null. Accents and shorthands count. */
    private Object[] unit(int i, int to) {
        char c = s.charAt(i);
        if (Character.isLetter(c)) return new Object[] {String.valueOf(c), i + 1};
        if (c == '\\' && i + 1 < to) {
            char d = s.charAt(i + 1);
            String comb = ACCENTS.get(d);
            boolean letterAccent = isLetter(d);
            if (comb != null) {
                // \"o  \"{o}  \'\i  \v{c}: an accent named by a letter (u, v, H, ...) needs a non-letter after it
                int j = i + 2;
                if (letterAccent && j < to && isLetter(s.charAt(j))) {
                    // followed by more letters it is some other command (e.g. \ref), not an accent
                    return null;
                }
                while (j < to && s.charAt(j) == ' ' && letterAccent) j++;
                String base;
                int end;
                if (j < to && s.charAt(j) == '{') {
                    int close = groupEnd(j, to);
                    if (close < 0) return null;
                    String inner = s.substring(j + 1, close).trim();
                    base = baseLetter(inner);
                    end = close + 1;
                } else if (j + 1 < to && s.charAt(j) == '\\' && (s.charAt(j + 1) == 'i' || s.charAt(j + 1) == 'j')
                        && (j + 2 >= to || !isLetter(s.charAt(j + 2)))) {
                    base = String.valueOf(s.charAt(j + 1));
                    end = j + 2;
                } else if (j < to && Character.isLetter(s.charAt(j))) {
                    base = String.valueOf(s.charAt(j));
                    end = j + 1;
                } else {
                    return null;
                }
                if (base == null) return null;
                return new Object[] {Normalizer.normalize(base + comb, Normalizer.Form.NFC), end};
            }
            if (letterAccent) {
                // \ss, \o, \ae ... (must not continue with letters)
                for (int len = 2; len >= 1; len--) {
                    if (i + 1 + len > to) continue;
                    String name = s.substring(i + 1, i + 1 + len);
                    String letter = LETTERS.get(name);
                    int end = i + 1 + len;
                    if (letter != null && (end >= to || !isLetter(s.charAt(end)))) {
                        if (end < to && s.charAt(end) == '{' && end + 1 < to && s.charAt(end + 1) == '}') {
                            end += 2; // \ss{}
                        } else {
                            // TeX drops spaces after a control word: "Gr\"o\ss e" is "Größe"
                            int k = end;
                            while (k < to && s.charAt(k) == ' ') k++;
                            if (k > end && k < to && Character.isLetter(s.charAt(k))) end = k;
                        }
                        return new Object[] {letter, end};
                    }
                }
            }
            return null;
        }
        if (c == '{' && i + 1 < to && s.charAt(i + 1) == '\\') {
            // {\"o}  {\ss}
            int close = groupEnd(i, to);
            if (close < 0) return null;
            Object[] inner = unit(i + 1, close);
            if (inner != null && (int) inner[1] == close) return new Object[] {inner[0], close + 1};
            return null;
        }
        if (german && c == '"' && i + 1 < to) {
            char d = s.charAt(i + 1);
            switch (d) {
                case 'a': return new Object[] {"ä", i + 2};
                case 'o': return new Object[] {"ö", i + 2};
                case 'u': return new Object[] {"ü", i + 2};
                case 'A': return new Object[] {"Ä", i + 2};
                case 'O': return new Object[] {"Ö", i + 2};
                case 'U': return new Object[] {"Ü", i + 2};
                case 's': return new Object[] {"ß", i + 2};
                case 'z': return new Object[] {"ß", i + 2};
                default: return null;
            }
        }
        return null;
    }

    private static String baseLetter(String inner) {
        if (inner.length() == 1 && Character.isLetter(inner.charAt(0))) return inner;
        if (inner.equals("\\i")) return "i";
        if (inner.equals("\\j")) return "j";
        return null;
    }

    /**
     * Reads a word starting at i: letters and accent units, with apostrophes between letters
     * and transparent hyphenation hints (\- and, in German, "- and "|). Hyphens split words.
     */
    private int readWord(int i, int to) {
        int start = i;
        StringBuilder w = new StringBuilder();
        while (i < to) {
            Object[] u = unit(i, to);
            if (u != null) {
                w.append((String) u[0]);
                i = (int) u[1];
                continue;
            }
            char c = s.charAt(i);
            if ((c == '\'' || c == '’') && w.length() > 0 && i + 1 < to && unit(i + 1, to) != null) {
                w.append('\'');
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < to && s.charAt(i + 1) == '-' && i + 2 < to && unit(i + 2, to) != null) {
                i += 2; // \- discretionary hyphen
                continue;
            }
            if (german && c == '"' && i + 1 < to && (s.charAt(i + 1) == '-' || s.charAt(i + 1) == '|')
                    && i + 2 < to && unit(i + 2, to) != null) {
                i += 2;
                continue;
            }
            break;
        }
        add(w.toString(), start, i);
        return i;
    }

    private void add(String word, int start, int end) {
        if (word.isEmpty()) return;
        // Next to digits ("3rd", "x2"): not a word to check.
        if (start > 0 && Character.isDigit(s.charAt(start - 1))) return;
        if (end < s.length() && Character.isDigit(s.charAt(end))) return;
        int letters = word.codePointCount(0, word.length());
        if (letters < 2) return;
        if (word.equals(word.toUpperCase()) && !word.equals(word.toLowerCase())) return; // acronym
        words.add(new Word(Normalizer.normalize(word, Normalizer.Form.NFC), start, end));
    }

    // --- helpers ------------------------------------------------------------------------------

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '@';
    }

    private int skipSpaces(int i, int to) {
        while (i < to && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n')) i++;
        return i;
    }

    private int lineEnd(int i, int to) {
        int e = s.indexOf('\n', i);
        return e < 0 || e > to ? to : e;
    }

    /** Index of the } closing the { at {@code open}, or -1. Comments and escapes respected. */
    private int groupEnd(int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '%') {
                i = lineEnd(i, to) - 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the ] closing the [ at {@code open} (braces inside are skipped), or -1. */
    private int bracketEnd(int open, int to) {
        for (int i = open + 1; i < to; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '{') {
                int e = groupEnd(i, to);
                if (e < 0) return -1;
                i = e;
            } else if (c == ']') {
                return i;
            } else if (c == '\n' && i + 1 < to && s.charAt(i + 1) == '\n') {
                return -1; // a paragraph break: not an optional argument
            }
        }
        return -1;
    }
}
