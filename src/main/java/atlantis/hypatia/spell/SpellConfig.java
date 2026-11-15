package atlantis.hypatia.spell;

import atlantis.hypatia.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ~/.config/hypatia/spellcheck.cfg: which LaTeX command arguments contain text to check,
 * and which environments are math or not checked at all.
 */
public final class SpellConfig {

    /** One argument of a listed command: optional ([...]) or mandatory ({...}), checked or skipped. */
    public record Arg(boolean optional, boolean checked) {}

    private final Map<String, List<Arg>> commands = new HashMap<>();
    private final Map<String, List<Arg>> environments = new HashMap<>(); // args after \begin{name}
    private final Set<String> mathEnvironments = new HashSet<>();
    private final Set<String> ignoredEnvironments = new HashSet<>();
    private final List<String> problems = new ArrayList<>();

    private static final Pattern COMMAND = Pattern.compile("^\\\\([A-Za-z@]+)\\*?((?:\\s*(?:\\[[^\\]]*\\]|\\{[^}]*\\}))*)\\s*$");
    private static final Pattern BEGIN = Pattern.compile("^\\\\begin\\s*\\{([^}]+)\\}((?:\\s*(?:\\[[^\\]]*\\]|\\{[^}]*\\}))*)\\s*$");
    private static final Pattern ARG = Pattern.compile("\\[([^\\]]*)\\]|\\{([^}]*)\\}");

    private SpellConfig() {}

    public static Path file() {
        return Settings.configDir().resolve("spellcheck.cfg");
    }

    /** Loads spellcheck.cfg, creating it with the defaults if it does not exist. */
    public static SpellConfig load() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, DEFAULT, StandardCharsets.UTF_8);
            }
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            SpellConfig c = parse(DEFAULT);
            c.problems.add("Could not read " + f + ": " + e.getMessage());
            return c;
        }
    }

    static SpellConfig parse(String text) {
        SpellConfig c = new SpellConfig();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m;
            if (line.startsWith("math-environment ")) {
                for (String n : line.substring(17).trim().split("\\s+")) c.mathEnvironments.add(n);
            } else if (line.startsWith("ignore-environment ")) {
                for (String n : line.substring(19).trim().split("\\s+")) c.ignoredEnvironments.add(n);
            } else if ((m = BEGIN.matcher(line)).matches()) {
                c.environments.put(m.group(1).trim(), args(m.group(2)));
            } else if ((m = COMMAND.matcher(line)).matches()) {
                c.commands.put(m.group(1), args(m.group(2)));
            } else {
                c.problems.add("spellcheck.cfg line " + (i + 1) + ": cannot read \"" + line + "\"");
            }
        }
        return c;
    }

    private static List<Arg> args(String spec) {
        List<Arg> out = new ArrayList<>();
        Matcher m = ARG.matcher(spec);
        while (m.find()) {
            boolean optional = m.group(1) != null;
            String inner = optional ? m.group(1) : m.group(2);
            out.add(new Arg(optional, inner.trim().equals("...")));
        }
        return out;
    }

    /** Argument spec of a listed command (name without backslash; a trailing * is ignored), or null. */
    public List<Arg> command(String name) {
        List<Arg> a = commands.get(name);
        if (a == null && name.endsWith("*")) a = commands.get(name.substring(0, name.length() - 1));
        return a;
    }

    /** Argument spec after \begin{name}, or null. */
    public List<Arg> environment(String name) {
        List<Arg> a = environments.get(name);
        if (a == null && name.endsWith("*")) a = environments.get(name.substring(0, name.length() - 1));
        return a;
    }

    public boolean isMath(String env) {
        return mathEnvironments.contains(env)
                || (env.endsWith("*") && mathEnvironments.contains(env.substring(0, env.length() - 1)));
    }

    public boolean isIgnored(String env) {
        return ignoredEnvironments.contains(env)
                || (env.endsWith("*") && ignoredEnvironments.contains(env.substring(0, env.length() - 1)));
    }

    public List<String> problems() {
        return problems;
    }

    static final String DEFAULT = """
            # Hypatia spell checking: which LaTeX arguments contain text to check.
            #
            # Only the document body is checked (after \\begin{document}, or the whole file if it
            # has none); the preamble, comments and math ($...$, \\[...\\], equation, ...) are not.
            #
            # Every LaTeX command is ignored together with the arguments directly following it,
            # unless it is listed here. In a listed command, ... marks an argument that is checked,
            # {} or [] an argument that is skipped:
            #     \\href{}{...}         checks the link text, not the URL
            #     \\section[...]{...}   checks the short title (if present) and the title
            # Starred forms (\\section*) use the same entry.
            # \\begin{name}[...] lists the arguments after \\begin{name} in the same way; arguments of
            # environments not listed (e.g. \\begin{tabular}{ccc}) are skipped. The content of an
            # environment is checked unless it is listed below.
            #
            #     math-environment name ...    content is math: only listed commands are checked
            #                                  inside, e.g. \\text{...}
            #     ignore-environment name ...  content is not checked at all
            #
            # Save this file (Settings > Spelling > Edit list) to apply changes. Lines starting
            # with # are comments.

            # --- text formatting ---
            \\emph{...}
            \\textbf{...}
            \\textit{...}
            \\textsl{...}
            \\textsc{...}
            \\textup{...}
            \\textrm{...}
            \\textsf{...}
            \\textmd{...}
            \\textnormal{...}
            \\underline{...}
            \\uline{...}
            \\uwave{...}
            \\textcolor{}{...}
            \\colorbox{}{...}
            \\fbox{...}
            \\mbox{...}
            \\enquote{...}

            # --- structure ---
            \\title[...]{...}
            \\subtitle[...]{...}
            \\thanks{...}
            \\part[...]{...}
            \\chapter[...]{...}
            \\section[...]{...}
            \\subsection[...]{...}
            \\subsubsection[...]{...}
            \\paragraph[...]{...}
            \\subparagraph[...]{...}
            \\caption[...]{...}
            \\footnote[]{...}
            \\footnotetext[]{...}
            \\marginpar[...]{...}
            \\item[...]
            \\href{}{...}
            \\frametitle{...}
            \\framesubtitle{...}

            # --- text inside math ---
            \\text{...}
            \\textrm{...}
            \\intertext{...}
            \\shortintertext{...}

            # --- environments with a title argument ---
            \\begin{theorem}[...]
            \\begin{lemma}[...]
            \\begin{proposition}[...]
            \\begin{corollary}[...]
            \\begin{definition}[...]
            \\begin{remark}[...]
            \\begin{example}[...]
            \\begin{conjecture}[...]
            \\begin{proof}[...]

            math-environment equation align gather multline flalign alignat eqnarray
            math-environment displaymath math dmath dgroup darray
            ignore-environment verbatim Verbatim lstlisting minted comment filecontents
            ignore-environment tikzpicture tikzcd thebibliography
            """;
}
