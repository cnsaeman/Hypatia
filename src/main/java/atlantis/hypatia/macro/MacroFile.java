package atlantis.hypatia.macro;

import atlantis.hypatia.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads ~/.config/hypatia/macros.cfg.
 *
 * <pre>
 * >>MACRO:name:key            shortcut "runmacro.name" in keys.properties
 * >>MACRO:name:text=\\e       typed character sequence
 * >>SELECT CURRENT BLOCK
 * >>CLEAR ENVIRONMENT
 * >>INSERT text
 * >>INSERT LINES
 * lines...                    (up to the next line starting with >>)
 * >>EXECUTE Class.method      public static void method(Document, int start, int end)
 *                             of a compiled class in ~/.config/hypatia/macros
 * >># comment
 * </pre>
 */
public final class MacroFile {

    private final List<Macro> macros = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private MacroFile() {}

    public static Path file() {
        return Settings.configDir().resolve("macros.cfg");
    }

    /** Loads macros.cfg, creating it with examples if it does not exist. */
    public static MacroFile load() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, DEFAULT
                        .replace("%MACROS%", atlantis.hypatia.Platform.arg(MacroRunner.classFolder())),
                        StandardCharsets.UTF_8);
            }
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            MacroFile m = new MacroFile();
            m.problems.add("Could not read " + f + ": " + e.getMessage());
            return m;
        }
    }

    public List<Macro> macros() {
        return macros;
    }

    public List<String> problems() {
        return problems;
    }

    /** Names of the macros that are triggered by a shortcut. */
    public List<String> keyMacroNames() {
        return macros.stream().filter(m -> m.trigger() == Macro.Trigger.KEY).map(Macro::name).toList();
    }

    public List<Macro> textMacros() {
        return macros.stream().filter(m -> m.trigger() == Macro.Trigger.TEXT).toList();
    }

    public Macro byName(String name) {
        return macros.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
    }

    public int longestTrigger() {
        return textMacros().stream().mapToInt(m -> m.text().length()).max().orElse(0);
    }

    // --- parsing ------------------------------------------------------------------------------

    static MacroFile parse(String text) {
        MacroFile mf = new MacroFile();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);

        String name = null;
        Macro.Trigger trigger = null;
        String triggerText = null;
        int headerLine = 0;
        List<Macro.Command> commands = new ArrayList<>();
        List<String> block = null; // open INSERT LINES block
        boolean skipping = false;  // after a header with an error: ignore its commands

        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : ">>MACRO-END-OF-FILE";
            int no = i + 1;
            if (!line.startsWith(">>")) {
                if (block != null) {
                    block.add(line);
                } else if (!line.isBlank() && !skipping) {
                    mf.problems.add("macros.cfg line " + no + ": text outside >>INSERT LINES is ignored");
                }
                continue;
            }
            if (block != null) {
                while (!block.isEmpty() && block.get(block.size() - 1).isBlank()) block.remove(block.size() - 1);
                commands.add(new Macro.InsertLines(List.copyOf(block)));
                block = null;
            }
            String cmd = line.substring(2);
            if (cmd.startsWith("#")) continue;

            String upper = cmd.trim().toUpperCase(Locale.ROOT);
            if (upper.startsWith("MACRO:") || upper.equals("MACRO-END-OF-FILE")) {
                if (name != null) mf.add(name, trigger, triggerText, commands, headerLine);
                name = null;
                commands = new ArrayList<>();
                if (upper.equals("MACRO-END-OF-FILE")) break;
                headerLine = no;
                skipping = true; // until the header turns out to be valid
                String[] parts = cmd.trim().substring(6).split(":", 2);
                String n = parts[0].trim();
                if (!n.matches("[A-Za-z0-9_.-]+")) {
                    mf.problems.add("macros.cfg line " + no + ": macro name \"" + n
                            + "\" may only contain letters, digits, _ - and .");
                    continue;
                }
                String t = parts.length > 1 ? parts[1].trim() : "";
                if (t.equalsIgnoreCase("key")) {
                    trigger = Macro.Trigger.KEY;
                    triggerText = null;
                } else if (t.toLowerCase(Locale.ROOT).startsWith("text=")) {
                    String v = t.substring(5).trim();
                    if (v.length() >= 3 && v.startsWith("<") && v.endsWith(">")) v = v.substring(1, v.length() - 1);
                    if (v.isEmpty()) {
                        mf.problems.add("macros.cfg line " + no + ": empty text trigger");
                        continue;
                    }
                    trigger = Macro.Trigger.TEXT;
                    triggerText = v;
                } else {
                    mf.problems.add("macros.cfg line " + no + ": trigger must be \"key\" or \"text=...\"");
                    continue;
                }
                name = n;
                skipping = false;
                continue;
            }
            if (name == null) {
                if (!skipping) mf.problems.add("macros.cfg line " + no + ": command outside a macro");
                if (upper.equals("INSERT LINES")) block = new ArrayList<>(); // swallow its lines
                continue;
            }
            if (upper.equals("SELECT CURRENT BLOCK")) {
                commands.add(new Macro.SelectBlock());
            } else if (upper.equals("CLEAR ENVIRONMENT")) {
                commands.add(new Macro.ClearEnvironment());
            } else if (upper.equals("INSERT LINES")) {
                block = new ArrayList<>();
            } else if (upper.startsWith("EXECUTE ")) {
                String target = cmd.trim().substring(8).trim();
                int dot = target.lastIndexOf('.');
                if (dot <= 0 || dot == target.length() - 1
                        || !target.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+")) {
                    mf.problems.add("macros.cfg line " + no + ": EXECUTE needs Class.method, e.g. >>EXECUTE MyMacros.swap");
                } else {
                    commands.add(new Macro.Execute(target.substring(0, dot), target.substring(dot + 1)));
                }
            } else if (upper.startsWith("INSERT ")) {
                commands.add(new Macro.Insert(cmd.substring(cmd.toUpperCase(Locale.ROOT).indexOf("INSERT ") + 7)));
            } else {
                mf.problems.add("macros.cfg line " + no + ": unknown command \"" + cmd.trim() + "\"");
            }
        }
        mf.checkTriggers();
        return mf;
    }

    private void add(String name, Macro.Trigger trigger, String text, List<Macro.Command> commands, int line) {
        if (byName(name) != null) {
            problems.add("macros.cfg line " + line + ": macro \"" + name + "\" is defined twice; the first one is used");
            return;
        }
        if (commands.isEmpty()) problems.add("macros.cfg line " + line + ": macro \"" + name + "\" has no commands");
        macros.add(new Macro(name, trigger, text, List.copyOf(commands), line));
    }

    /** Duplicate triggers, and triggers that make longer ones unreachable. */
    private void checkTriggers() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (Macro m : textMacros()) {
            String other = seen.putIfAbsent(m.text(), m.name());
            if (other != null) problems.add("text trigger " + m.text() + " is used by both " + other + " and " + m.name());
        }
        for (Macro a : textMacros()) {
            for (Macro b : textMacros()) {
                if (a != b && b.text().length() > a.text().length() && b.text().contains(a.text())) {
                    problems.add("macro " + b.name() + " (" + b.text() + ") can never run: "
                            + a.name() + " (" + a.text() + ") fires first");
                }
            }
        }
    }

    // --- the file written on first start ------------------------------------------------------

    static final String DEFAULT = """
            >># Hypatia macros. Save this file (File > Macros) to apply changes.
            >>#
            >># A macro starts with a header line:
            >>#   >>MACRO:<name>:key           run with a shortcut: Hypatia adds "runmacro.<name> ="
            >>#                                 to keys.properties, where you set the key
            >>#   >>MACRO:<name>:text=<chars>  run when <chars> are typed quickly in a .tex file;
            >>#                                 the typed characters are removed first
            >># followed by commands, run in order:
            >>#   >>SELECT CURRENT BLOCK  select the lines around the cursor, up to an empty line
            >>#                           or a line indented less than the current one
            >>#   >>CLEAR ENVIRONMENT     remove the \\begin{...}/\\end{...} around the selection
            >>#                           (or the cursor); the content is de-indented and selected
            >>#   >>INSERT <text>         insert text at the cursor
            >>#   >>INSERT LINES          insert the lines that follow (up to the next >> line),
            >>#                           indented like the current line
            >>#   >>EXECUTE <Class>.<method>  run a Java method of a compiled class in
            >>#                           the folder %MACROS%; it is declared as
            >>#                             public static void method(javax.swing.text.Document doc,
            >>#                                                       int start, int end)
            >>#                           and changes doc directly; start and end are the selection
            >>#                           (equal without a selection). Compile with
            >>#                             javac -d %MACROS% MyMacros.java
            >># In INSERT and INSERT LINES, >CURSOR< marks where the cursor ends up, and where
            >># selected text goes (the cursor then goes after it). Without >CURSOR<, a selection
            >># is replaced. Names: letters, digits, _ - .   Lines starting with >># are comments.
            >># Undo (Ctrl+Z, on a Mac Cmd+Z) undoes a whole macro.

            >># Type \\\\eq for an equation environment.
            >>MACRO:equation:text=\\\\eq
            >>INSERT LINES
            \\begin{equation}
                >CURSOR<
            \\end{equation}

            >># Type \\\\al for an align environment.
            >>MACRO:align:text=\\\\al
            >>INSERT LINES
            \\begin{align}
                >CURSOR<
            \\end{align}

            >># Type \\\\em for \\emph{}: the cursor goes between the braces.
            >>MACRO:emph:text=\\\\em
            >>INSERT \\emph{>CURSOR<}

            >># Put the current paragraph (or block) into an equation environment.
            >>MACRO:wrap-equation:key
            >>SELECT CURRENT BLOCK
            >>INSERT LINES
            \\begin{equation}
                >CURSOR<
            \\end{equation}

            >># Turn the environment around the cursor into align.
            >>MACRO:to-align:key
            >>CLEAR ENVIRONMENT
            >>INSERT LINES
            \\begin{align}
                >CURSOR<
            \\end{align}

            >># Remove the environment around the cursor.
            >>MACRO:unwrap:key
            >>CLEAR ENVIRONMENT
            """;
}
