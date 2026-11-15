package atlantis.hypatia.style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a style on a document: its regular expressions (on the document body, ignoring matches
 * that start in comments) and its plugins (on the whole source).
 */
public final class StyleRunner {

    /** Problems found, sorted by position, and messages about rules or plugins that failed. */
    public record Result(List<Problem> problems, List<String> messages) {}

    private static final long REGEXP_LIMIT_MS = 2000;
    private static final Pattern BEGIN_DOCUMENT = Pattern.compile("\\\\begin\\s*\\{document\\}");
    private static final Pattern END_DOCUMENT = Pattern.compile("\\\\end\\s*\\{document\\}");
    private static final Pattern GROUP_REF = Pattern.compile("\\$(\\d)");

    /** Plugins that timed out; skipped until their style is loaded again (new instances). */
    private static final Set<StylePlugin> DISABLED = ConcurrentHashMap.newKeySet();

    /** Plugin calls run here so a hanging plugin can be abandoned. */
    private static final ExecutorService PLUGIN_THREADS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hypatia-style-plugin");
        t.setDaemon(true);
        return t;
    });

    private StyleRunner() {}

    public static Result run(StyleLibrary.Style style, String text, long pluginTimeoutMs) {
        List<Problem> out = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        int[] body = body(text);

        for (StyleLibrary.Rule rule : style.rules()) {
            try {
                Matcher m = rule.pattern().matcher(LimitedText.of(text, REGEXP_LIMIT_MS));
                while (m.find()) {
                    if (m.end() <= m.start()) continue; // nothing to underline
                    if (m.start() < body[0] || m.start() >= body[1] || inComment(text, m.start())) continue;
                    out.add(new Problem(explanation(rule.explanation(), m), m.start(), m.end()));
                }
            } catch (LimitedText.TooSlow e) {
                messages.add(rule.file() + " line " + rule.line() + ": regular expression too slow, skipped");
            } catch (RuntimeException e) {
                messages.add(rule.file() + " line " + rule.line() + ": " + e);
            }
        }

        for (StyleLibrary.Plugin p : style.plugins()) {
            if (DISABLED.contains(p.instance())) continue;
            Future<ArrayList<Problem>> f = PLUGIN_THREADS.submit(() -> p.instance().checkDocument(text));
            try {
                List<Problem> found = f.get(pluginTimeoutMs, TimeUnit.MILLISECONDS);
                int invalid = 0;
                if (found != null) {
                    for (Problem pr : found) {
                        if (pr == null || pr.explanation() == null || pr.start() < 0 || pr.end() > text.length()
                                || pr.end() <= pr.start()) {
                            invalid++;
                        } else {
                            out.add(pr);
                        }
                    }
                }
                if (invalid > 0) messages.add("Plugin " + p.name() + ": " + invalid + " problems with invalid positions ignored");
            } catch (TimeoutException e) {
                f.cancel(true);
                DISABLED.add(p.instance());
                messages.add("Plugin " + p.name() + " took longer than " + pluginTimeoutMs / 1000.0
                        + " s and was switched off; run the style again to retry");
            } catch (InterruptedException e) {
                f.cancel(true);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Throwable t = e.getCause() != null ? e.getCause() : e;
                StackTraceElement[] st = t.getStackTrace();
                messages.add("Plugin " + p.name() + " failed: " + t + (st.length > 0 ? " at " + st[0] : ""));
            }
        }
        out.sort(Comparator.comparingInt(Problem::start).thenComparingInt(Problem::end));
        return new Result(out, messages);
    }

    /** Explanation with $1..$9 replaced by the match's groups. */
    private static String explanation(String template, Matcher m) {
        if (template.indexOf('$') < 0) return template;
        Matcher g = GROUP_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (g.find()) {
            int n = g.group(1).charAt(0) - '0';
            String v = n <= m.groupCount() && m.group(n) != null ? m.group(n) : g.group();
            g.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        g.appendTail(sb);
        return sb.toString();
    }

    /** [start, end) of the document body: after \begin{document}, before \end{document}; else all. */
    static int[] body(String text) {
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
        return new int[] {from, to};
    }

    /** Whether {@code pos} is inside a % comment. */
    static boolean inComment(String t, int pos) {
        int ls = t.lastIndexOf('\n', pos - 1) + 1;
        for (int i = ls; i < pos; i++) {
            char c = t.charAt(i);
            if (c == '\\') i++;
            else if (c == '%') return true;
        }
        return false;
    }

    /** Text that makes a runaway regular expression give up after a time limit. */
    private static final class LimitedText implements CharSequence {
        static final class TooSlow extends RuntimeException {
            TooSlow() {
                super(null, null, false, false);
            }
        }

        private final String s;
        private final long deadline;
        private int calls;

        static LimitedText of(String s, long limitMs) {
            return new LimitedText(s, System.nanoTime() + limitMs * 1_000_000);
        }

        private LimitedText(String s, long deadlineNanos) {
            this.s = s;
            this.deadline = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0xFFFF) == 0 && System.nanoTime() > deadline) throw new TooSlow();
            return s.charAt(index);
        }

        @Override public int length() { return s.length(); }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new LimitedText(s.substring(start, end), deadline);
        }

        @Override public String toString() { return s; }
    }
}
