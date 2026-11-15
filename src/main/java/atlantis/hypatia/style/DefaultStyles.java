package atlantis.hypatia.style;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import atlantis.hypatia.Platform;

/** The styles folder Hypatia creates on first use, with examples. */
final class DefaultStyles {

    private DefaultStyles() {}

    static void createIfMissing() throws IOException {
        Files.createDirectories(StyleLibrary.regexpDir());
        Files.createDirectories(StyleLibrary.pluginDir());
        if (Files.exists(StyleLibrary.configFile())) return;
        write(StyleLibrary.configFile(), STYLES);
        write(StyleLibrary.regexpDir().resolve("typography.txt"), TYPOGRAPHY);
        write(StyleLibrary.regexpDir().resolve("latex.txt"), LATEX);
        write(StyleLibrary.regexpDir().resolve("academic.txt"), ACADEMIC);
        write(StyleLibrary.pluginDir().resolve("LongSentences.java"), LONG_SENTENCES
                .replace("%JAR%", Platform.jarArg())
                .replace("%PLUGINS%", Platform.arg(StyleLibrary.pluginDir()))
                .replace("%SOURCE%", Platform.arg(StyleLibrary.pluginDir().resolve("LongSentences.java"))));
    }

    private static void write(Path p, String text) throws IOException {
        if (!Files.exists(p)) Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    static final String STYLES = """
            # Hypatia styles. Each style starts with >>STYLE:name and lists, one per line:
            #   regexps/<file>          a file of regular expressions in regexps/
            #   plugins/<Name>.class    a compiled StylePlugin (in a package: plugins/pkg/Name.class)
            #   plugins/<file>.jar      every StylePlugin in a jar
            #   include <style>         everything of another style
            # Wildcards: regexps/*   plugins/**  (all plugins, including package folders)
            #
            # A .tex file chooses its style with "% !TeX style = name" near the top (or in its main
            # file); otherwise the style called "default" is used. Save this file to apply changes.
            # Lines starting with # are comments.

            >>STYLE:default
            regexps/typography.txt
            regexps/latex.txt

            >>STYLE:paper
            include default
            regexps/academic.txt
            # plugins/LongSentences.class    (compile plugins/LongSentences.java first, see there)
            """;

    private static final String REGEXP_HEADER = """
            # Regular expressions for Hypatia's style checks.
            #
            # A line starting with >> gives the explanation for the regular expressions below it,
            # one Java regular expression per line. $1, $2, ... in the explanation are replaced by
            # the matched groups. Start a regexp with (?i) to ignore case; ^ and $ match at line
            # starts and ends. Only the document body is searched, and matches that start in a
            # % comment are ignored. Lines starting with # are comments.
            # Syntax: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html

            """;

    static final String TYPOGRAPHY = REGEXP_HEADER + """
            >>Repeated word: "$1"
            (?i)\\b(\\p{L}+)\\s+\\1\\b

            >>No space before a punctuation mark
            (?<=\\p{L}) +[,;:!?](?=\\s)
            (?<=\\p{L}) +\\.(?=\\s|$)

            >>Use \\dots (\\ldots, or \\cdots in maths) instead of three full stops
            \\.\\.\\.
            """;

    static final String LATEX = REGEXP_HEADER + """
            >>Use a non-breaking space ~ before $1
            (?<=[\\p{L})]) (\\\\(?:ref|eqref|cref|autoref|pageref|cite)\\b)

            >>Use \\eqref for equations: it adds the parentheses
            \\\\ref\\{eq

            >>Use \\[ ... \\] instead of $$ ... $$ (plain TeX, wrong spacing in LaTeX)
            \\$\\$

            >>A space after an abbreviation's full stop is taken as the end of a sentence: write "\\ " after it (or a comma)
            \\b(?:e\\.g|i\\.e|cf|resp|vs|etc)\\.(?= \\p{Ll})
            """;

    static final String ACADEMIC = REGEXP_HEADER + """
            >>Contraction: write it out in a paper
            (?i)\\b\\p{L}+n't\\b
            (?i)\\b(?:it|that|there|what|here)'s\\b

            >>"$1" adds little; consider removing it
            (?i)\\b(very|really|quite|basically|actually)\\b

            >>"$1": what is obvious to you may not be to the reader
            (?i)\\b(obviously|clearly|trivially|of course)\\b

            >>Sentence starts with a conjunction
            (?<=[.!?]\\s{1,3})(?:And|But|So)\\b
            """;

    static final String LONG_SENTENCES = """
            // Example style plugin for Hypatia: marks sentences with more than 40 words.
            //
            // Compile it (needs a JDK, which includes javac) with
            //   javac -cp %JAR% -d %PLUGINS%
            //         %SOURCE%
            // (all on one line).
            // and enable it in styles.cfg with the line
            //   plugins/LongSentences.class
            // A plugin receives the whole source and returns problems with offsets into it.
            // Run the style again (F9) after recompiling to load the new version.

            import atlantis.hypatia.style.Problem;
            import atlantis.hypatia.style.StylePlugin;
            import java.util.ArrayList;
            import java.util.regex.Matcher;
            import java.util.regex.Pattern;

            public class LongSentences implements StylePlugin {

                private static final int MAX_WORDS = 40;
                private static final Pattern WORD = Pattern.compile("\\\\p{L}+");
                private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\\\s|$)|\\\\n[ \\\\t]*\\\\n");

                @Override
                public ArrayList<Problem> checkDocument(String s) {
                    ArrayList<Problem> problems = new ArrayList<>();
                    int begin = s.indexOf("\\\\begin{document}");
                    int from = begin < 0 ? 0 : begin + "\\\\begin{document}".length();
                    int end = s.indexOf("\\\\end{document}", from);
                    String prose = prose(s.substring(from, end < 0 ? s.length() : end));

                    int start = 0;
                    Matcher m = SENTENCE_END.matcher(prose);
                    while (true) {
                        boolean found = m.find();
                        int stop = found ? m.end() : prose.length();
                        String sentence = prose.substring(start, stop);
                        int words = 0;
                        Matcher w = WORD.matcher(sentence);
                        while (w.find()) words++;
                        if (words > MAX_WORDS) {
                            int a = start;
                            while (a < stop && Character.isWhitespace(prose.charAt(a))) a++;
                            int b = stop;
                            while (b > a && Character.isWhitespace(prose.charAt(b - 1))) b--;
                            problems.add(new Problem("Long sentence: " + words + " words (more than "
                                    + MAX_WORDS + ")", from + a, from + b));
                        }
                        if (!found) break;
                        start = m.end();
                    }
                    return problems;
                }

                /** Blanks out comments, maths and command names, keeping all offsets. */
                private static String prose(String t) {
                    char[] c = t.toCharArray();
                    boolean math = false;
                    for (int i = 0; i < c.length; i++) {
                        if (c[i] == '%') {
                            while (i < c.length && c[i] != '\\n') c[i++] = ' ';
                        } else if (c[i] == '$') {
                            math = !math;
                            c[i] = ' ';
                        } else if (c[i] == '\\\\') {
                            c[i] = ' ';
                            if (i + 1 < c.length && !Character.isLetter(c[i + 1])) {
                                c[++i] = ' '; // \\% \\$ \\, ...
                            }
                            while (i + 1 < c.length && Character.isLetter(c[i + 1])) c[++i] = ' ';
                        } else if (math && c[i] != '\\n') {
                            c[i] = ' ';
                        }
                    }
                    return new String(c);
                }
            }
            """;
}
