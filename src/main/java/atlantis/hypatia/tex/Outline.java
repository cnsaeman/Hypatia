package atlantis.hypatia.tex;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sectioning structure of a project: \part ... \paragraph headings of the main file,
 * with those of \input / \include / \subfile / \import'ed files inserted where they are
 * included.
 */
public final class Outline {

    /** A heading; {@code line} is 1-based in {@code file}. */
    public record Heading(int level, String command, String title, Path file, int line, List<Heading> children) {
        public String key() {
            return command + ":" + title;
        }
    }

    public static final Map<String, Integer> LEVELS = Map.of(
            "part", 0, "chapter", 1, "section", 2, "subsection", 3, "subsubsection", 4, "paragraph", 5);

    private static final Pattern TOKEN = Pattern.compile(
            "\\\\(part|chapter|section|subsection|subsubsection|paragraph)(\\*?)\\s*(?:\\[[^\\]]*\\]\\s*)?\\{"
            + "|\\\\(input|include|subfile|InputIfFileExists)\\s*\\{([^}]+)\\}"
            + "|\\\\(?:sub)?(?:import|includefrom|inputfrom)\\s*\\{([^}]*)\\}\\s*\\{([^}]+)\\}");

    private Outline() {}

    /** Top-level headings of the project rooted at {@code root}, nested by level. */
    public static List<Heading> build(Path root, Function<Path, String> reader) {
        List<Heading> flat = new ArrayList<>();
        scan(root, root.getParent(), reader, flat, new HashSet<>(), 0);
        return nest(flat);
    }

    /** All headings in document order, as found by {@link #build}. */
    public static List<Heading> flatten(List<Heading> roots) {
        List<Heading> out = new ArrayList<>();
        Deque<Heading> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        while (!stack.isEmpty()) {
            Heading h = stack.pop();
            out.add(h);
            for (int i = h.children().size() - 1; i >= 0; i--) stack.push(h.children().get(i));
        }
        return out;
    }

    private static void scan(Path file, Path rootDir, Function<Path, String> reader, List<Heading> out,
                             Set<Path> seen, int depth) {
        if (depth > 30 || !seen.add(file)) return;
        String text = reader.apply(file);
        if (text == null) return;
        String code = blankComments(text);
        Matcher m = TOKEN.matcher(code);
        int line = 1;
        int counted = 0;
        while (m.find()) {
            for (int i = counted; i < m.start(); i++) if (code.charAt(i) == '\n') line++;
            counted = m.start();
            if (m.group(1) != null) {
                int open = m.end() - 1;
                int close = closingBrace(code, open);
                String raw = close < 0 ? code.substring(open + 1, Math.min(code.length(), open + 80))
                        : text.substring(open + 1, close);
                out.add(new Heading(LEVELS.get(m.group(1)), m.group(1) + m.group(2), cleanTitle(raw), file, line,
                        new ArrayList<>()));
            } else {
                Path inc = m.group(3) != null
                        ? ProjectScanner.resolveTex(m.group(4).trim(), rootDir, file.getParent())
                        : resolveImport(file, m.group(5).trim(), m.group(6).trim());
                if (inc != null) scan(inc, rootDir, reader, out, seen, depth + 1);
            }
        }
    }

    private static Path resolveImport(Path file, String dir, String name) {
        Path d = file.getParent().resolve(dir);
        return ProjectScanner.resolveTex(name, d, d);
    }

    /** Nests headings: each becomes a child of the nearest earlier heading with a smaller level. */
    private static List<Heading> nest(List<Heading> flat) {
        List<Heading> roots = new ArrayList<>();
        Deque<Heading> open = new ArrayDeque<>();
        for (Heading h : flat) {
            while (!open.isEmpty() && open.peek().level() >= h.level()) open.pop();
            if (open.isEmpty()) roots.add(h); else open.peek().children().add(h);
            open.push(h);
        }
        return roots;
    }

    private static int closingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length() && i < open + 2000; i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static final Pattern DROP = Pattern.compile("\\\\(label|index|footnote|thanks)\\s*\\{[^{}]*\\}");
    private static final Pattern PDFSTRING = Pattern.compile("\\\\texorpdfstring\\s*\\{(?:[^{}]|\\{[^{}]*\\})*\\}\\s*\\{([^{}]*)\\}");

    /** A heading title for display: labels removed, text commands unwrapped; maths kept as written. */
    static String cleanTitle(String raw) {
        String t = raw.replace('\n', ' ');
        t = DROP.matcher(t).replaceAll("");
        t = PDFSTRING.matcher(t).replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
        StringBuilder sb = new StringBuilder();
        boolean math = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '$') {
                math = !math;
                sb.append(c);
            } else if (math) {
                sb.append(c);
            } else if (c == '\\' && i + 1 < t.length()) {
                char d = t.charAt(i + 1);
                if (Character.isLetter(d)) {
                    int j = i + 1;
                    while (j < t.length() && Character.isLetter(t.charAt(j))) j++;
                    i = j - 1; // drop the command name, keep its argument
                } else {
                    sb.append(d == '\\' ? ' ' : d); // \& \% \_ ... and \\ as a space
                    i++;
                }
            } else if (c == '{' || c == '}') {
                // drop grouping braces
            } else if (c == '~') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "(untitled)" : s;
    }

    /** Replaces % comments by spaces, keeping offsets and line breaks. */
    private static String blankComments(String text) {
        char[] c = text.toCharArray();
        boolean comment = false;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == '\n') {
                comment = false;
            } else if (comment) {
                c[i] = ' ';
            } else if (c[i] == '\\') {
                i++;
            } else if (c[i] == '%') {
                comment = true;
                c[i] = ' ';
            }
        }
        return new String(c);
    }
}
