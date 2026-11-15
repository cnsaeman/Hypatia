package atlantis.hypatia.tex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All errors and warnings of the last run, read from the main file's .log (and the
 * bibliography's .blg). Unlike the console output, the .log has the final run only, and
 * TeX's "(file ... )" nesting tells which file a warning belongs to.
 */
public final class LatexLog {

    public enum Category {
        ERROR("Errors", true),
        UNDEFINED_REFERENCE("Undefined references", false),
        UNDEFINED_CITATION("Undefined citations", false),
        MULTIPLY_DEFINED("Multiply defined labels", false),
        OVERFULL("Overfull boxes", false),
        UNDERFULL("Underfull boxes", false),
        BIBLIOGRAPHY("Bibliography", false),
        OTHER("Other warnings", false);

        public final String label;
        public final boolean error;

        Category(String label, boolean error) {
            this.label = label;
            this.error = error;
        }
    }

    /** One entry; {@code file} may be null and {@code line} 0 when unknown. */
    public record Entry(Category category, Path file, int line, String message, boolean error) {}

    private static final Pattern FILE_LINE_ERROR =
            Pattern.compile("^(.+?\\.(?:tex|ltx|sty|cls|bbl|aux|dtx|def|cfg|clo|fd)):(\\d+): (.*)$");
    private static final Pattern BANG = Pattern.compile("^! (.*)$");
    private static final Pattern ERROR_LINE = Pattern.compile("^l\\.(\\d+)\\b.*$");
    private static final Pattern BOX = Pattern.compile("^(Overfull|Underfull) \\\\[hv]box \\(.*");
    private static final Pattern AT_LINES = Pattern.compile("(?:at lines? |detected at line )(\\d+)");
    private static final Pattern WARNING = Pattern.compile(
            "^(LaTeX|LaTeX Font|LaTeX3|Package ([\\w@.-]+)|Class ([\\w@.-]+)|pdfTeX|Module ([\\w@.-]+)) [Ww]arning(?: \\(([^)]*)\\))?: (.*)$");
    private static final Pattern INPUT_LINE = Pattern.compile("\\s*on input line (\\d+)\\.?\\s*$");
    private static final Pattern MISSING_CHAR = Pattern.compile("^Missing character: (.*)$");
    private static final Pattern BIBTEX_WARNING = Pattern.compile("^Warning--(.*)$");
    private static final Pattern BIBTEX_ERROR = Pattern.compile("^(.*)---line (\\d+) of file (.*)$");
    private static final Pattern BIBER = Pattern.compile("^\\[\\d+\\] [^>]*> (WARN|ERROR) - (.*)$");
    private static final Pattern FILE_TOKEN = Pattern.compile(
            "^\"?((?:/|\\./|\\.\\./|[A-Za-z]:|[\\w~-])[^\\s()\"]*\\.[A-Za-z0-9]{1,8})\"?");

    private LatexLog() {}

    /** The .log of {@code root} (main.tex -> main.log), or null if there is none. */
    public static Path logFile(Path root) {
        Path p = root.resolveSibling(RootResolver.baseName(root) + ".log");
        return Files.isRegularFile(p) ? p : null;
    }

    /** Errors and warnings from the .log and .blg of {@code root}; empty if there is no log. */
    public static List<Entry> read(Path root) {
        List<Entry> out = new ArrayList<>();
        Path log = logFile(root);
        if (log != null) {
            try {
                parseLog(lines(Files.readAllBytes(log)), root.getParent(), out);
            } catch (IOException ignored) {
                // unreadable: nothing to show
            }
        }
        Path blg = root.resolveSibling(RootResolver.baseName(root) + ".blg");
        if (Files.isRegularFile(blg)) {
            try {
                parseBlg(lines(Files.readAllBytes(blg)), root.getParent(), out);
            } catch (IOException ignored) {
                // no bibliography log
            }
        }
        return dedupe(out);
    }

    // -------------------------------------------------------------------------
    // .log
    // -------------------------------------------------------------------------

    static void parseLog(List<String> lines, Path dir, List<Entry> out) {
        Deque<Path> files = new ArrayDeque<>(); // null entries for parentheses that are not files
        Path NOT_A_FILE = Path.of("");
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            Matcher m;
            if ((m = FILE_LINE_ERROR.matcher(s)).matches()) {
                i = skipErrorContext(lines, i, null);
                out.add(new Entry(Category.ERROR, dir.resolve(m.group(1)).normalize(),
                        Integer.parseInt(m.group(2)), m.group(3).trim(), true));
            } else if ((m = BANG.matcher(s)).matches()) {
                int[] line = {0};
                i = skipErrorContext(lines, i, line);
                out.add(new Entry(Category.ERROR, current(files, NOT_A_FILE), line[0], m.group(1).trim(), true));
            } else if (BOX.matcher(s).matches()) {
                String msg = s.replaceFirst("\\s*\\[\\]\\s*$", "");
                Matcher at = AT_LINES.matcher(msg);
                int line = at.find() ? Integer.parseInt(at.group(1)) : 0;
                out.add(new Entry(msg.startsWith("Overfull") ? Category.OVERFULL : Category.UNDERFULL,
                        current(files, NOT_A_FILE), line, msg, false));
                i = skipBoxContents(lines, i);
            } else if ((m = WARNING.matcher(s)).matches()) {
                String pkg = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3)
                        : m.group(4) != null ? m.group(4) : m.group(1).startsWith("LaTeX Font") ? "Font" : null;
                StringBuilder msg = new StringBuilder(m.group(6).trim());
                // continuation lines: "(natbib)   Rerun to get ...", "(Font)   ..."
                while (i + 1 < lines.size() && pkg != null && lines.get(i + 1).startsWith("(" + pkg + ")")) {
                    msg.append(' ').append(lines.get(++i).substring(pkg.length() + 2).trim());
                }
                // LaTeX's own warnings continue on lines indented with spaces
                while (pkg == null && i + 1 < lines.size() && lines.get(i + 1).startsWith("          ")
                        && !lines.get(i + 1).isBlank()) {
                    msg.append(' ').append(lines.get(++i).trim());
                }
                String text = msg.toString();
                int line = 0;
                Matcher il = INPUT_LINE.matcher(text);
                if (il.find()) {
                    line = Integer.parseInt(il.group(1));
                    text = text.substring(0, il.start());
                }
                text = text.replaceFirst("\\.$", "");
                String full = (m.group(2) != null ? m.group(2) + ": " : m.group(3) != null ? m.group(3) + ": " : "")
                        + text;
                Category c = categorize(text);
                if (c != null) out.add(new Entry(c, line > 0 ? current(files, NOT_A_FILE) : null, line, full, false));
            } else if ((m = MISSING_CHAR.matcher(s)).matches()) {
                out.add(new Entry(Category.OTHER, current(files, NOT_A_FILE), 0, s, false));
            } else {
                trackFiles(s, dir, files, NOT_A_FILE);
            }
        }
    }

    /** null for summaries that only repeat the individual warnings. */
    private static Category categorize(String text) {
        String t = text.toLowerCase();
        if (t.startsWith("there were undefined references") || t.startsWith("there were undefined citations")
                || t.startsWith("there were multiply-defined labels") || t.startsWith("there were multiply defined")) {
            return null;
        }
        if (t.contains("multiply defined") || t.contains("multiply-defined")) return Category.MULTIPLY_DEFINED;
        if (t.startsWith("citation") && t.contains("undefined")) return Category.UNDEFINED_CITATION;
        if ((t.startsWith("reference") || t.startsWith("hyper reference")) && t.contains("undefined")) {
            return Category.UNDEFINED_REFERENCE;
        }
        if (t.contains("could not be found in the database") || t.contains("empty bibliography")) {
            return Category.UNDEFINED_CITATION;
        }
        return Category.OTHER;
    }

    private static boolean startsMessage(String s) {
        return BANG.matcher(s).matches() || BOX.matcher(s).matches() || WARNING.matcher(s).matches()
                || FILE_LINE_ERROR.matcher(s).matches();
    }

    /**
     * Skips an error's context and help text (up to the next empty line), whose source
     * excerpts may contain unbalanced parentheses. Stores the "l.N" line number if asked.
     */
    private static int skipErrorContext(List<String> lines, int i, int[] line) {
        int j = i + 1;
        for (int n = 0; j < lines.size() && n < 40; j++, n++) {
            String s = lines.get(j);
            if (s.isEmpty()) return j;
            Matcher m = ERROR_LINE.matcher(s);
            if (m.matches()) {
                if (line != null && line[0] == 0) line[0] = Integer.parseInt(m.group(1));
            } else if (startsMessage(s)) {
                return j - 1;
            }
        }
        return j - 1;
    }

    /** Skips the box contents printed after an over/underfull box warning (up to a blank line). */
    private static int skipBoxContents(List<String> lines, int i) {
        int j = i + 1;
        for (int n = 0; j < lines.size() && n < 12; j++, n++) {
            String s = lines.get(j);
            if (s.isBlank()) return j;
            if (startsMessage(s)) return j - 1;
        }
        return j - 1;
    }

    /** Follows TeX's "(./chapter.tex ... )" nesting of input files. */
    private static void trackFiles(String s, Path dir, Deque<Path> files, Path notAFile) {
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') {
                Matcher f = FILE_TOKEN.matcher(s.substring(k + 1));
                if (f.find() && looksLikeFile(f.group(1))) {
                    files.push(dir.resolve(f.group(1)).normalize());
                    k += f.end();
                } else {
                    files.push(notAFile);
                }
            } else if (c == ')') {
                if (!files.isEmpty()) files.pop();
            }
        }
    }

    private static boolean looksLikeFile(String token) {
        return token.contains("/") || token.matches(".*\\.(tex|ltx|sty|cls|clo|cfg|def|fd|aux|toc|lof|lot|bbl|out|nav|snm|ind|gls|dtx|tikz|pgf|code\\.tex|map|enc|pfb|ldf)$");
    }

    private static Path current(Deque<Path> files, Path notAFile) {
        for (Path p : files) {
            if (p != notAFile) return p;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // .blg (BibTeX, Biber)
    // -------------------------------------------------------------------------

    static void parseBlg(List<String> lines, Path dir, List<Entry> out) {
        for (String s : lines) {
            Matcher m;
            if ((m = BIBTEX_ERROR.matcher(s)).matches()) {
                out.add(new Entry(Category.BIBLIOGRAPHY, dir.resolve(m.group(3).trim()).normalize(),
                        Integer.parseInt(m.group(2)), "BibTeX: " + m.group(1).trim(), true));
            } else if ((m = BIBTEX_WARNING.matcher(s)).matches()) {
                out.add(new Entry(Category.BIBLIOGRAPHY, null, 0, "BibTeX: " + m.group(1).trim(), false));
            } else if ((m = BIBER.matcher(s)).matches()) {
                out.add(new Entry(Category.BIBLIOGRAPHY, null, 0, "Biber: " + m.group(2).trim(),
                        m.group(1).equals("ERROR")));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /**
     * The log's lines, with TeX's line breaking undone: TeX breaks lines after
     * max_print_line (79 by default) bytes; the width is taken as the longest line length if
     * several lines have it.
     */
    static List<String> lines(byte[] bytes) {
        List<byte[]> raw = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                int end = i;
                if (end > start && bytes[end - 1] == '\r') end--;
                byte[] l = new byte[end - start];
                System.arraycopy(bytes, start, l, 0, l.length);
                raw.add(l);
                start = i + 1;
            }
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int max = 0;
        for (byte[] l : raw) {
            max = Math.max(max, l.length);
            counts.merge(l.length, 1, Integer::sum);
        }
        int width = max >= 60 && counts.getOrDefault(max, 0) >= 3 ? max : Integer.MAX_VALUE;

        List<String> out = new ArrayList<>();
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < raw.size(); i++) {
            byte[] l = raw.get(i);
            joined.write(l, 0, l.length);
            boolean continues = l.length == width && i + 1 < raw.size();
            if (!continues) {
                out.add(decode(joined.toByteArray()));
                joined.reset();
            }
        }
        if (joined.size() > 0) out.add(decode(joined.toByteArray()));
        return out;
    }

    private static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
    }

    private static List<Entry> dedupe(List<Entry> in) {
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (Entry e : in) {
            if (seen.add(e.category() + "|" + e.file() + "|" + e.line() + "|" + e.message())) out.add(e);
        }
        return out;
    }
}
