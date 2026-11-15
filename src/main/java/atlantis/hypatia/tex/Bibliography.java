package atlantis.hypatia.tex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/** BibTeX parsing (tolerant, no dependencies) and author/title search. */
public final class Bibliography {

    /** One BibTeX entry. Field names are lower case; values keep their LaTeX markup. */
    public static final class Entry {
        private final String type;
        private final String key;
        private final Map<String, String> fields;
        private final Path file;
        private final int line;
        private final String source;
        private final String searchText;

        Entry(String type, String key, Map<String, String> fields, Path file, int line, String source) {
            this.type = type;
            this.key = key;
            this.fields = fields;
            this.file = file;
            this.line = line;
            this.source = source;
            this.searchText = normalize(field("author") + " " + field("editor") + " " + field("title"));
        }

        public String type() { return type; }
        public String key() { return key; }
        public Path file() { return file; }
        public int line() { return line; }
        /** The entry as written in the .bib file. */
        public String source() { return source; }

        public String field(String name) {
            return fields.getOrDefault(name, "");
        }

        public String title() {
            return plain(field("title"));
        }

        public String year() {
            String y = field("year");
            if (y.isEmpty() && field("date").length() >= 4) y = field("date").substring(0, 4);
            return plain(y);
        }

        /** "Baez, Schreiber" or "Baez, Huerta, Schreiber et al." */
        public String shortAuthors() {
            String a = field("author").isEmpty() ? field("editor") : field("author");
            if (a.isBlank()) return "";
            String[] names = plain(a).split("\\s+and\\s+");
            List<String> last = new ArrayList<>();
            for (String n : names) {
                n = n.trim();
                if (n.equalsIgnoreCase("others")) continue;
                int comma = n.indexOf(',');
                if (comma >= 0) {
                    last.add(n.substring(0, comma).trim());
                } else {
                    String[] w = n.split("\\s+");
                    last.add(w[w.length - 1]);
                }
            }
            if (last.size() > 4) return String.join(", ", last.subList(0, 3)) + " et al.";
            return String.join(", ", last);
        }

        /** Journal, book or arXiv number, whatever is there. */
        public String venue() {
            for (String f : new String[] {"journal", "journaltitle", "booktitle", "publisher", "school", "institution"}) {
                if (!field(f).isBlank()) return plain(field(f));
            }
            if (!field("eprint").isBlank()) return "arXiv:" + plain(field("eprint"));
            return "";
        }

        /** True if every word occurs in the author/editor or title field. */
        boolean matches(String[] words) {
            for (String w : words) {
                if (!searchText.contains(w)) return false;
            }
            return true;
        }
    }

    public record SearchResult(List<Entry> entries, int total) {}

    private record Cached(FileTime modified, List<Entry> entries) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private final List<Entry> entries = new ArrayList<>();
    private final List<Path> files;

    private Bibliography(List<Path> files) {
        this.files = files;
    }

    /**
     * Loads the given .bib files. {@code openText} supplies the text of files open
     * in the editor (unsaved changes count); other files are parsed once and cached.
     */
    public static Bibliography load(List<Path> files, Function<Path, String> openText) {
        Bibliography b = new Bibliography(files);
        for (Path f : files) {
            String text = openText.apply(f);
            if (text != null) {
                b.entries.addAll(parse(text, f));
                continue;
            }
            try {
                FileTime mod = Files.getLastModifiedTime(f);
                Cached c = CACHE.get(f);
                if (c == null || !c.modified().equals(mod)) {
                    c = new Cached(mod, parse(TextFiles.read(f), f));
                    CACHE.put(f, c);
                }
                b.entries.addAll(c.entries());
            } catch (IOException e) {
                // unreadable: skip
            }
        }
        return b;
    }

    public List<Path> files() { return files; }
    public List<Entry> entries() { return java.util.Collections.unmodifiableList(entries); }
    public int size() { return entries.size(); }

    /** Entries whose author/editor or title contain all words of {@code query}; at most {@code limit}. */
    public SearchResult search(String query, int limit) {
        String q = normalize(query).trim();
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Entry> out = new ArrayList<>();
        int total = 0;
        for (Entry e : entries) {
            if (e.matches(words)) {
                total++;
                if (out.size() < limit) out.add(e);
            }
        }
        return new SearchResult(out, total);
    }

    // --- parsing --------------------------------------------------------------------------

    static List<Entry> parse(String s, Path file) {
        List<Entry> out = new ArrayList<>();
        Map<String, String> macros = new java.util.HashMap<>(MONTHS);
        int n = s.length();
        int i = 0;
        int line = 1;
        int lineCountedTo = 0;
        while ((i = s.indexOf('@', i)) >= 0) {
            for (int k = lineCountedTo; k < i; k++) if (s.charAt(k) == '\n') line++;
            lineCountedTo = i;
            int start = i;
            int j = i + 1;
            while (j < n && Character.isLetter(s.charAt(j))) j++;
            String type = s.substring(i + 1, j).toLowerCase(Locale.ROOT);
            j = skipSpace(s, j);
            if (type.isEmpty() || j >= n || (s.charAt(j) != '{' && s.charAt(j) != '(')) {
                i = i + 1;
                continue;
            }
            char close = s.charAt(j) == '{' ? '}' : ')';
            int end = matching(s, j, s.charAt(j), close);
            if (end < 0) end = n - 1;
            if (type.equals("string")) {
                parseFields(s, j + 1, end, macros).forEach((k, v) -> macros.put(k, v));
                i = end + 1;
                continue;
            }
            if (type.equals("comment") || type.equals("preamble")) {
                i = end + 1;
                continue;
            }
            int comma = s.indexOf(',', j);
            if (comma < 0 || comma > end) {
                i = end + 1;
                continue;
            }
            String key = s.substring(j + 1, comma).trim();
            Map<String, String> fields = parseFields(s, comma + 1, end, macros);
            if (!key.isEmpty()) {
                out.add(new Entry(type, key, fields, file, line, s.substring(start, Math.min(end + 1, n))));
            }
            i = end + 1;
        }
        return out;
    }

    private static final Map<String, String> MONTHS = Map.ofEntries(
            Map.entry("jan", "January"), Map.entry("feb", "February"), Map.entry("mar", "March"),
            Map.entry("apr", "April"), Map.entry("may", "May"), Map.entry("jun", "June"),
            Map.entry("jul", "July"), Map.entry("aug", "August"), Map.entry("sep", "September"),
            Map.entry("oct", "October"), Map.entry("nov", "November"), Map.entry("dec", "December"));

    /** Fields between {@code i} and {@code end}; bare words are looked up in {@code macros} (@string). */
    private static Map<String, String> parseFields(String s, int i, int end, Map<String, String> macros) {
        Map<String, String> fields = new LinkedHashMap<>();
        while (i < end) {
            while (i < end && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            int nameStart = i;
            while (i < end && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '=' && s.charAt(i) != ',') i++;
            String name = s.substring(nameStart, i).toLowerCase(Locale.ROOT);
            i = skipSpace(s, i);
            if (i >= end || s.charAt(i) != '=') {
                // not a field; skip to next comma
                while (i < end && s.charAt(i) != ',') i++;
                continue;
            }
            i = skipSpace(s, i + 1);
            StringBuilder value = new StringBuilder();
            while (i < end) {
                char c = s.charAt(i);
                if (c == '{') {
                    int e = matching(s, i, '{', '}');
                    if (e < 0 || e > end) e = end;
                    value.append(s, i + 1, e);
                    i = e + 1;
                } else if (c == '"') {
                    int e = i + 1;
                    int depth = 0;
                    while (e < end && !(s.charAt(e) == '"' && depth == 0)) {
                        if (s.charAt(e) == '{') depth++;
                        else if (s.charAt(e) == '}') depth--;
                        e++;
                    }
                    value.append(s, i + 1, e);
                    i = e + 1;
                } else {
                    int e = i;
                    while (e < end && s.charAt(e) != ',' && s.charAt(e) != '#' && !Character.isWhitespace(s.charAt(e))) e++;
                    String word = s.substring(i, e); // number or @string macro name
                    value.append(macros.getOrDefault(word.toLowerCase(Locale.ROOT), word));
                    i = e;
                }
                i = skipSpace(s, i);
                if (i < end && s.charAt(i) == '#') {
                    i = skipSpace(s, i + 1);
                } else {
                    break;
                }
            }
            if (!name.isEmpty()) fields.put(name, value.toString().replaceAll("\\s+", " ").trim());
        }
        return fields;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Index of the bracket closing the one at {@code open}, or -1. */
    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    // --- text clean-up ------------------------------------------------------------------------

    private static final Pattern ACCENT = Pattern.compile("\\\\[`'^\"~=.uvHtcdbkr]\\s*\\{?\\s*\\\\?([A-Za-z])\\}?");
    private static final Pattern COMMAND = Pattern.compile("\\\\[A-Za-z]+\\*?\\s*");

    /** LaTeX markup to readable text: {\"o} -> ö, \emph{x} -> x, braces removed. */
    public static String plain(String s) {
        String t = ACCENT.matcher(s).replaceAll(m -> accent(m.group()));
        t = t.replace("\\ss", "ß").replace("\\o ", "ø").replace("\\o}", "ø}").replace("\\ae", "æ")
             .replace("\\oe", "œ").replace("\\aa", "å").replace("\\l ", "ł").replace("\\&", "&")
             .replace("~", " ").replace("--", "–");
        t = COMMAND.matcher(t).replaceAll("");
        t = t.replace("{", "").replace("}", "").replace("$", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String accent(String m) {
        // \"o -> o + combining diaeresis, etc.
        char kind = m.charAt(1);
        char letter = 0;
        for (int i = m.length() - 1; i >= 0; i--) {
            if (Character.isLetter(m.charAt(i))) {
                letter = m.charAt(i);
                break;
            }
        }
        if (letter == 0) return m;
        String combining = switch (kind) {
            case '`' -> "̀";
            case '\'' -> "́";
            case '^' -> "̂";
            case '~' -> "̃";
            case '=' -> "̄";
            case 'u' -> "̆";
            case '.' -> "̇";
            case '"' -> "̈";
            case 'r' -> "̊";
            case 'H' -> "̋";
            case 'v' -> "̌";
            case 'c' -> "̧";
            case 'k' -> "̨";
            default -> "";
        };
        return Normalizer.normalize(letter + combining, Normalizer.Form.NFC);
    }

    /** Lower case, no markup, no diacritics: "Sämann" and "S{\"a}mann" both become "samann". */
    public static String normalize(String s) {
        String t = Normalizer.normalize(plain(s), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.toLowerCase(Locale.ROOT);
    }
}
