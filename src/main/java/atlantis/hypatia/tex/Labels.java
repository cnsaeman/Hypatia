package atlantis.hypatia.tex;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All \label{...} of a project, with their surroundings, and a word search over them. */
public final class Labels {

    /** Lines of context shown above and below a label. */
    public static final int CONTEXT = 5;

    public static final Set<String> MATH_ENVIRONMENTS = Set.of(
            "equation", "equation*", "align", "align*", "alignat", "alignat*", "gather", "gather*",
            "multline", "multline*", "flalign", "flalign*", "eqnarray", "eqnarray*", "dmath", "dmath*",
            "subequations");

    /**
     * A label. {@code line} and {@code contextStart} are 1-based; {@code environment}
     * is the innermost environment (or the sectioning command) the label belongs to.
     */
    public record Label(String key, Path file, int line, String environment,
                        int contextStart, String context, String searchText) {
        public boolean inMath() {
            return environment != null && MATH_ENVIRONMENTS.contains(environment);
        }
    }

    public record SearchResult(List<Label> labels, int total) {}

    private static final Pattern TOKEN = Pattern.compile(
            "\\\\(begin|end)\\s*\\{([^}]*)\\}|\\\\label\\s*\\{([^}]*)\\}"
            + "|\\\\(part|chapter|section|subsection|subsubsection|paragraph|subparagraph)\\*?\\s*[\\[{]");

    private final List<Label> labels = new ArrayList<>();

    private Labels() {}

    public static Labels collect(List<Path> files, Function<Path, String> reader) {
        Labels l = new Labels();
        for (Path f : files) {
            String text = reader.apply(f);
            if (text != null) l.scan(f, text);
        }
        return l;
    }

    private void scan(Path file, String text) {
        String[] lines = text.split("\n", -1);
        Deque<String> envs = new ArrayDeque<>();
        String lastSection = null;
        int lastSectionLine = -10;
        int depthAtSection = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TOKEN.matcher(ProjectScanner.stripComment(lines[i]));
            while (m.find()) {
                if (m.group(1) != null) {
                    String name = m.group(2).trim();
                    if (m.group(1).equals("begin")) {
                        envs.push(name);
                    } else if (!envs.isEmpty()) {
                        // pop up to (and including) the matching \begin, tolerating mistakes
                        if (envs.contains(name)) {
                            while (!envs.isEmpty() && !envs.pop().equals(name)) { }
                        }
                    }
                } else if (m.group(4) != null) {
                    lastSection = m.group(4);
                    lastSectionLine = i;
                    depthAtSection = envs.size();
                } else {
                    String key = m.group(3).trim();
                    if (key.isEmpty()) continue;
                    // A label right after \section{...} (same or next line) belongs to the section.
                    String env = (i - lastSectionLine <= 1 && envs.size() == depthAtSection) ? lastSection
                            : envs.stream().filter(e -> !e.equals("document")).findFirst().orElse(null);
                    int from = Math.max(0, i - CONTEXT);
                    int to = Math.min(lines.length - 1, i + CONTEXT);
                    String context = String.join("\n", java.util.Arrays.asList(lines).subList(from, to + 1));
                    String search = (key + " " + (env == null ? "" : env) + " " + context).toLowerCase(Locale.ROOT);
                    labels.add(new Label(key, file, i + 1, env, from + 1, context, search));
                }
            }
        }
    }

    public int size() {
        return labels.size();
    }

    /**
     * Labels matching every word of {@code query}. Labels whose key contains all words
     * come first, then those that match in their surroundings; otherwise document order.
     */
    public SearchResult search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Label> keyHits = new ArrayList<>();
        List<Label> contextHits = new ArrayList<>();
        for (Label l : labels) {
            String key = l.key().toLowerCase(Locale.ROOT);
            boolean inKey = true;
            boolean inAll = true;
            for (String w : words) {
                if (!key.contains(w)) inKey = false;
                if (!l.searchText().contains(w)) inAll = false;
            }
            if (inKey) keyHits.add(l);
            else if (inAll) contextHits.add(l);
        }
        keyHits.addAll(contextHits);
        int total = keyHits.size();
        return new SearchResult(keyHits.subList(0, Math.min(limit, total)), total);
    }
}
