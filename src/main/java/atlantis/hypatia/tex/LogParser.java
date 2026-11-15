package atlantis.hypatia.tex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts errors and warnings from (pdf)LaTeX / latexmk console output. */
public final class LogParser {

    public enum Severity { ERROR, WARNING }

    /**
     * A problem; {@code file} may be null and {@code line} 0 when unknown. {@code context} is
     * the source text TeX had read when it stopped (from "l.12 ..."), or null.
     */
    public record Problem(Severity severity, Path file, int line, String message, int logLine, String context) {
        public String location() {
            String f = file == null ? "" : file.getFileName().toString();
            return line > 0 ? (f.isEmpty() ? "line " + line : f + ":" + line) : f;
        }
    }

    // ./chapter.tex:12: Undefined control sequence.   (with -file-line-error)
    private static final Pattern FILE_LINE_ERROR =
            Pattern.compile("^(.+?\\.(?:tex|ltx|sty|cls|bbl|aux)):(\\d+): (.*)$");
    // ! Undefined control sequence.                   (classic format)
    private static final Pattern BANG_ERROR = Pattern.compile("^! (.*)$");
    private static final Pattern ERROR_LINE = Pattern.compile("^l\\.(\\d+)(?: (.*))?$");
    private static final Pattern WARNING = Pattern.compile(
            "^((?:LaTeX|Package [\\w-]+|Class [\\w-]+|LaTeX Font|pdfTeX) Warning: .*?)(?: on input line (\\d+))?\\.?$");

    private LogParser() {}

    public static List<Problem> parse(List<String> log, Path root) {
        Path dir = root.getParent();
        List<Problem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String pendingBang = null;
        int pendingBangLine = -1;

        for (int i = 0; i < log.size(); i++) {
            String s = log.get(i);
            Matcher m;
            if ((m = FILE_LINE_ERROR.matcher(s)).matches()) {
                Path f = dir.resolve(m.group(1)).normalize();
                int line = Integer.parseInt(m.group(2));
                add(out, seen, new Problem(Severity.ERROR, f, line, m.group(3).trim(), i, context(log, i, line)));
            } else if ((m = BANG_ERROR.matcher(s)).matches()) {
                pendingBang = m.group(1).trim();
                pendingBangLine = i;
            } else if (pendingBang != null && (m = ERROR_LINE.matcher(s)).matches()) {
                add(out, seen, new Problem(Severity.ERROR, root, Integer.parseInt(m.group(1)),
                        pendingBang, pendingBangLine, m.group(2)));
                pendingBang = null;
            } else if ((m = WARNING.matcher(s)).matches()) {
                StringBuilder msg = new StringBuilder(m.group(1));
                int line = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                // Long warnings continue on following lines, indented with "(package)".
                while (line == 0 && i + 1 < log.size() && log.get(i + 1).startsWith("(")) {
                    String cont = log.get(++i).replaceFirst("^\\([^)]*\\)\\s*", "");
                    Matcher end = Pattern.compile("^(.*?)(?: on input line (\\d+))?\\.?$").matcher(cont);
                    if (end.matches()) {
                        msg.append(' ').append(end.group(1));
                        if (end.group(2) != null) line = Integer.parseInt(end.group(2));
                    }
                }
                // TeX doesn't say which file "input line" refers to; leave it open.
                add(out, seen, new Problem(Severity.WARNING, null, line,
                        msg.toString().trim(), i, null));
            }
        }
        if (pendingBang != null) {
            add(out, seen, new Problem(Severity.ERROR, null, 0, pendingBang, pendingBangLine, null));
        }
        out.sort((a, b) -> a.severity().compareTo(b.severity()));
        return out;
    }

    /** The "l.<line> text" context line following an error at log line {@code from}, or null. */
    private static String context(List<String> log, int from, int line) {
        for (int j = from + 1; j < log.size() && j <= from + 40; j++) {
            String s = log.get(j);
            if (FILE_LINE_ERROR.matcher(s).matches()) return null; // the next error
            Matcher m = ERROR_LINE.matcher(s);
            if (m.matches() && Integer.parseInt(m.group(1)) == line) return m.group(2);
        }
        return null;
    }

    private static void add(List<Problem> out, Set<String> seen, Problem p) {
        String key = p.severity() + "|" + p.file() + "|" + p.line() + "|" + p.message();
        if (seen.add(key)) out.add(p);
    }
}
