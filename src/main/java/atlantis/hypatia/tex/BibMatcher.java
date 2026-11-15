package atlantis.hypatia.tex;

import atlantis.hypatia.tex.Bibliography.Entry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds, for entries of one .bib file, the same works in another: first by DOI, then by
 * arXiv number, then by title and authors.
 */
public final class BibMatcher {

    /** An old entry with its proposed replacement (null: none) and how it was found. */
    public static final class Row {
        public final Entry old;
        public Entry proposal;
        public String method;

        Row(Entry old, Entry proposal, String method) {
            this.old = old;
            this.proposal = proposal;
            this.method = method;
        }
    }

    private static final Pattern ARXIV_NEW = Pattern.compile("(?<![\\d.])(\\d{4}\\.\\d{4,5})(?:v\\d+)?(?![\\d])");
    private static final Pattern ARXIV_OLD = Pattern.compile(
            "(?i)(?<![\\w-])([a-z][a-z-]*(?:\\.[a-z]{2})?/\\d{7})(?:v\\d+)?");

    private BibMatcher() {}

    public static List<Row> match(List<Entry> olds, List<Entry> news) {
        Map<String, Entry> byDoi = new HashMap<>();
        Map<String, Entry> byArxiv = new HashMap<>();
        List<Info> infos = new ArrayList<>();
        for (Entry e : news) {
            Info in = new Info(e);
            infos.add(in);
            if (in.doi != null) byDoi.putIfAbsent(in.doi, e);
            if (in.arxiv != null) byArxiv.putIfAbsent(in.arxiv, e);
        }
        List<Row> rows = new ArrayList<>();
        for (Entry o : olds) {
            Info oi = new Info(o);
            if (oi.doi != null && byDoi.containsKey(oi.doi)) {
                rows.add(new Row(o, byDoi.get(oi.doi), "DOI"));
                continue;
            }
            if (oi.arxiv != null && byArxiv.containsKey(oi.arxiv)) {
                rows.add(new Row(o, byArxiv.get(oi.arxiv), "arXiv"));
                continue;
            }
            Entry best = null;
            String how = null;
            double bestScore = 0;
            for (Info ni : infos) {
                double t = jaccard(oi.titleWords, ni.titleWords);
                if (t < 0.6) continue;
                boolean authorsKnown = !oi.authors.isEmpty() && !ni.authors.isEmpty();
                double a = authorsKnown ? overlap(oi.authors, ni.authors) : 0;
                boolean sameYear = !oi.year.isEmpty() && oi.year.equals(ni.year);
                String method = null;
                if (t >= 0.85 && a >= 0.5) method = "title + authors";
                else if (t >= 0.6 && a >= 0.99 && sameYear) method = "title + authors";
                else if (t == 1.0 && !authorsKnown && oi.titleWords.size() >= 4) method = "title";
                if (method == null) continue;
                double score = t + a + (sameYear ? 0.1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = ni.entry;
                    how = method;
                }
            }
            rows.add(new Row(o, best, best == null ? null : how));
        }
        return rows;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String s : a) if (b.contains(s)) common++;
        return (double) common / (a.size() + b.size() - common);
    }

    private static double overlap(Set<String> a, Set<String> b) {
        int common = 0;
        for (String s : a) if (b.contains(s)) common++;
        return (double) common / Math.max(a.size(), b.size());
    }

    /** What is compared, computed once per entry. */
    private static final class Info {
        final Entry entry;
        final String doi;
        final String arxiv;
        final Set<String> titleWords;
        final Set<String> authors;
        final String year;

        Info(Entry e) {
            entry = e;
            doi = doi(e);
            arxiv = arxiv(e);
            titleWords = words(e.field("title"));
            authors = lastNames(e.field("author").isBlank() ? e.field("editor") : e.field("author"));
            year = e.year();
        }
    }

    /** Lower-case DOI without "https://doi.org/" or "doi:", or null. */
    static String doi(Entry e) {
        String d = e.field("doi").trim();
        if (d.isEmpty()) {
            Matcher m = Pattern.compile("(?i)doi\\.org/(10\\.\\S+)").matcher(e.field("url"));
            if (m.find()) d = m.group(1);
        }
        if (d.isEmpty()) return null;
        d = d.replaceFirst("(?i)^\\s*(https?://)?(dx\\.)?doi\\.org/", "").replaceFirst("(?i)^doi:\\s*", "");
        d = d.replace("\\_", "_").replace("{", "").replace("}", "").trim().toLowerCase(Locale.ROOT);
        return d.startsWith("10.") ? d : null;
    }

    /** arXiv number without version (2101.01234, hep-th/9901001), or null. */
    static String arxiv(Entry e) {
        List<String> places = new ArrayList<>();
        String prefix = (e.field("archiveprefix") + " " + e.field("eprinttype")).toLowerCase(Locale.ROOT);
        if (!e.field("eprint").isBlank() && (prefix.isBlank() || prefix.contains("arxiv"))) places.add(e.field("eprint"));
        places.add(e.field("arxiv"));
        places.add(e.field("arxivid"));
        for (String f : new String[] {"journal", "note", "howpublished", "url", "number", "pages"}) {
            String v = e.field(f);
            if (v.toLowerCase(Locale.ROOT).contains("arxiv")) places.add(v);
        }
        for (String p : places) {
            if (p.isBlank()) continue;
            Matcher m = ARXIV_NEW.matcher(p);
            if (m.find()) return m.group(1);
            m = ARXIV_OLD.matcher(p);
            if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "on", "in", "and", "for", "to", "with");

    static Set<String> words(String title) {
        Set<String> out = new HashSet<>();
        for (String w : Bibliography.normalize(title).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty() && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    static Set<String> lastNames(String authors) {
        Set<String> out = new HashSet<>();
        if (authors.isBlank()) return out;
        for (String n : authors.split("\\s+and\\s+")) {
            n = n.trim();
            if (n.isEmpty() || n.equalsIgnoreCase("others")) continue;
            String last;
            int comma = n.indexOf(',');
            if (comma >= 0) {
                last = n.substring(0, comma);
            } else {
                String[] w = n.split("\\s+");
                last = w[w.length - 1];
            }
            String norm = Bibliography.normalize(last).replaceAll("[^\\p{L}\\p{N}]", "");
            if (!norm.isEmpty()) out.add(norm);
        }
        return out;
    }
}
