package atlantis.hypatia.tex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Label keys in the text: those defined by \label{...} and those referred to by \ref{...},
 * \eqref, \pageref, \autoref, \nameref, \cref/\Cref (comma-separated lists, \crefrange's two
 * arguments), \vref, \subref, \hyperref[...] and any other command whose name ends in "ref".
 * Comments are skipped; keys containing # (macro definitions) are ignored.
 */
public final class References {

    private References() {}

    /** A key in the text: {@code start}/{@code end} are offsets of the key itself. */
    public record Key(String key, boolean definition, String command, int start, int end,
                      int commandStart, int commandEnd) {}

    /** A use of a label, with {@link Labels#CONTEXT} lines around it; line and column are 1-based. */
    public record Use(String key, String command, Path file, int line, int column,
                      int contextStart, String context) {}

    private static final Pattern COMMAND = Pattern.compile(
            "\\\\([A-Za-z]*ref(?:range)?|label)(?![A-Za-z])\\*?");
    private static final Pattern OPTIONAL = Pattern.compile("\\s*\\[([^\\[\\]{}]*)\\]");
    private static final Pattern BRACED = Pattern.compile("\\s*\\{([^{}]*)\\}");

    /** Every label key in {@code text}, in order. */
    public static List<Key> scan(String text) {
        String code = blankComments(text);
        List<Key> keys = new ArrayList<>();
        Matcher m = COMMAND.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            if (name.equals("href")) continue; // \href{url}{text}
            int at = m.end();
            int from = keys.size();
            // optional arguments: the key of \hyperref[key]{text}, cleveref's \label[type]{key}
            Matcher o = OPTIONAL.matcher(code).region(at, code.length());
            while (o.lookingAt()) {
                if (name.equals("hyperref")) addKeys(keys, name, o.start(1), o.group(1), m.start(), o.end());
                at = o.end();
                o.region(at, code.length());
            }
            if (name.equals("hyperref")) { // the braced argument is text; \hyperref{url}{...} has no label
                m.region(at, code.length());
                continue;
            }
            int args = name.endsWith("range") ? 2 : 1;
            for (int i = 0; i < args; i++) {
                Matcher b = BRACED.matcher(code).region(at, code.length());
                if (!b.lookingAt()) break;
                at = b.end();
                addKeys(keys, name, b.start(1), b.group(1), m.start(), at);
            }
            // the command's end is known only now: fix it for all its keys
            for (int i = from; i < keys.size(); i++) {
                Key k = keys.get(i);
                keys.set(i, new Key(k.key(), k.definition(), k.command(), k.start(), k.end(), m.start(), at));
            }
            m.region(at, code.length());
        }
        return keys;
    }

    private static void addKeys(List<Key> keys, String name, int argStart, String arg, int cmdStart, int cmdEnd) {
        boolean definition = name.equals("label");
        int pos = 0;
        for (String part : (definition ? new String[] {arg} : arg.split(",", -1))) {
            int lead = part.length() - part.stripLeading().length();
            String key = part.strip();
            int s = argStart + pos + lead;
            pos += part.length() + 1;
            if (key.isEmpty() || key.contains("#")) continue;
            keys.add(new Key(key, definition, name, s, s + key.length(), cmdStart, cmdEnd));
        }
    }

    /**
     * The key at {@code offset}: the one the caret is in or touches, or, if the caret is
     * elsewhere on a command with a single key (e.g. on "\ref"), that key. Null if none.
     */
    public static Key at(String text, int offset) {
        Key onCommand = null;
        int onCommandCount = 0;
        for (Key k : scan(text)) {
            if (k.commandStart() > offset) break;
            if (offset >= k.start() && offset <= k.end()) return k;
            if (offset >= k.commandStart() && offset <= k.commandEnd()) {
                if (onCommand == null || onCommand.commandStart() != k.commandStart()) {
                    onCommand = k;
                    onCommandCount = 1;
                } else {
                    onCommandCount++;
                }
            }
        }
        return onCommandCount == 1 ? onCommand : null;
    }

    /** Every use (not definition) of {@code key} in {@code files}. */
    public static List<Use> uses(List<Path> files, Function<Path, String> reader, String key) {
        List<Use> uses = new ArrayList<>();
        for (Path f : files) {
            String text = reader.apply(f);
            if (text == null) continue;
            String[] lines = null;
            int[] lineStarts = null;
            for (Key k : scan(text)) {
                if (k.definition() || !k.key().equals(key)) continue;
                if (lines == null) {
                    lines = text.split("\n", -1);
                    lineStarts = lineStarts(text);
                }
                int line = lineOf(lineStarts, k.commandStart());
                int from = Math.max(0, line - Labels.CONTEXT);
                int to = Math.min(lines.length - 1, line + Labels.CONTEXT);
                String context = String.join("\n", java.util.Arrays.asList(lines).subList(from, to + 1));
                uses.add(new Use(key, k.command(), f, line + 1, k.start() - lineStarts[line] + 1, from + 1, context));
            }
        }
        return uses;
    }

    /** Every definition of {@code key} in {@code files}: file and 1-based line and column of the key. */
    public record Definition(Path file, int line, int column) {}

    public static List<Definition> definitions(List<Path> files, Function<Path, String> reader, String key) {
        List<Definition> found = new ArrayList<>();
        for (Path f : files) {
            String text = reader.apply(f);
            if (text == null || !text.contains(key)) continue;
            int[] starts = null;
            for (Key k : scan(text)) {
                if (!k.definition() || !k.key().equals(key)) continue;
                if (starts == null) starts = lineStarts(text);
                int line = lineOf(starts, k.start());
                found.add(new Definition(f, line + 1, k.start() - starts[line] + 1));
            }
        }
        return found;
    }

    /** {@code text} with every comment replaced by spaces, so offsets stay the same. */
    static String blankComments(String text) {
        StringBuilder sb = null;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            for (int i = lineStart; i < lineEnd; i++) {
                char c = text.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '%') {
                    if (sb == null) sb = new StringBuilder(text);
                    for (int j = i; j < lineEnd; j++) sb.setCharAt(j, ' ');
                    break;
                }
            }
            if (nl < 0) break;
            lineStart = nl + 1;
        }
        return sb == null ? text : sb.toString();
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 0-based line of {@code offset}. */
    private static int lineOf(int[] starts, int offset) {
        int i = java.util.Arrays.binarySearch(starts, offset);
        return i >= 0 ? i : -i - 2;
    }
}
