package atlantis.hypatia;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.swing.KeyStroke;

/**
 * Keyboard shortcuts, read from ~/.config/hypatia/keys.properties.
 *
 * The file is created with all defaults on first start; commands added in
 * later versions are appended to it. Format, one per line:
 *
 *   latex.cite = Ctrl+B
 *   view.wrap  = Alt+Z
 *   file.saveAll =            (empty: no shortcut)
 */
public final class Keymap {

    /** A command that can have a shortcut. */
    public record Command(String id, String description, String defaultKey) {}

    public static final List<Command> COMMANDS = List.of(
            new Command("file.new", "New file", "Mod+N"),
            new Command("file.open", "Open file", "Mod+O"),
            new Command("file.save", "Save", "Mod+S"),
            new Command("file.saveAs", "Save as", "Mod+Shift+S"),
            new Command("file.saveAll", "Save all", "Mod+Shift+L"),
            new Command("file.close", "Close tab", "Mod+W"),
            new Command("file.settings", "Settings", "Mod+,"),
            new Command("file.keys", "Edit keyboard shortcuts (this file)", ""),
            new Command("file.macros", "Edit macros (macros.cfg)", ""),
            new Command("file.quit", "Quit", "Mod+Q"),

            new Command("edit.undo", "Undo", "Mod+Z"),
            new Command("edit.redo", "Redo", "Mod+Shift+Z"),
            new Command("edit.cut", "Cut", "Mod+X"),
            new Command("edit.copy", "Copy", "Mod+C"),
            new Command("edit.paste", "Paste", "Mod+V"),
            new Command("edit.selectAll", "Select all", "Mod+A"),
            new Command("edit.find", "Find", "Mod+F"),
            new Command("edit.replace", "Find and replace", "Mod+R"),
            new Command("edit.refactor", "Rename the selected text (e.g. a label) in the whole project", "Tool+R"),
            new Command("edit.findNext", "Find next", "F3"),
            new Command("edit.findPrevious", "Find previous", "Shift+F3"),
            new Command("edit.goToLine", "Go to line", "Mod+G"),
            new Command("edit.reindent", "Re-indent environments (selection or document)", "Mod+Shift+I"),
            new Command("edit.toggleComment", "Comment/uncomment lines", "Mod+/"),

            new Command("view.darkTheme", "Switch between dark and light theme", ""),
            new Command("view.wrap", "Wrap long lines on/off", "Alt+Z"),
            new Command("view.fontLarger", "Larger font", "Mod+="),
            new Command("view.fontSmaller", "Smaller font", "Mod+-"),
            new Command("view.fontReset", "Default font size", "Mod+0"),
            new Command("view.log", "Show/hide build log", "F8"),
            new Command("view.problems", "Show all errors and warnings of the last LaTeX run", "Shift+F8"),
            new Command("view.detach", "Detach the editor into its own window / put it back", "F12"),
            new Command("view.outline", "Show/hide the structure sidebar", "Mod+Shift+O"),

            new Command("latex.build", "Build (again to stop)", "Tool+X"),
            new Command("latex.runLatex", "Run pdfLaTeX once (command in Settings > Building)", "Tool+C"),
            new Command("latex.runBibtex", "Run BibTeX once (command in Settings > Building)", "Tool+B"),
            new Command("latex.view", "Show current line in the PDF viewer", "Tool+V"),
            new Command("latex.viewNew", "Open the PDF in a new viewer window", "Shift+F6"),
            new Command("latex.cite", "Insert citation (BibTeX search)", "Mod+B"),
            new Command("latex.ref", "Insert reference (label search)", "Mod+L"),
            new Command("latex.goToLabel", "Go to the \\label of the reference at the cursor (\\ref, \\cref, ...)", "Mod+D"),
            new Command("latex.labelUses", "List the places that refer to the label at the cursor", "Mod+U"),
            new Command("latex.switchBib", "Switch to another BibTeX file, translating the citation keys", ""),
            new Command("latex.setMain", "Set main file", ""),
            new Command("latex.forgetMain", "Forget main file", ""),
            new Command("latex.spellcheck", "Spellcheck document", "F7"),
            new Command("latex.downloadDictionary", "Download a spell-check dictionary (LibreOffice collection)", ""),
            new Command("latex.userDictionary", "Edit user dictionary of the current language", ""),
            new Command("latex.spellConfig", "Edit spell-check command list (spellcheck.cfg)", ""),
            new Command("view.spelling", "Check spelling as you type on/off", ""),
            new Command("style.check", "Check the file's style (% !TeX style, else \"default\")", "F9"),
            new Command("style.clear", "Clear style marks", "Shift+F9"),
            new Command("style.next", "Go to next style problem", "Alt+Page_Down"),
            new Command("style.previous", "Go to previous style problem", "Alt+Page_Up"),
            new Command("style.edit", "Edit styles (styles.cfg)", ""),
            new Command("help.manual", "Open the manual (manual.pdf)", "F1"));

    private final Map<String, KeyStroke> keys = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    private Keymap() {}

    public static Path file() {
        return Settings.configDir().resolve("keys.properties");
    }

    /**
     * Loads the file, creating it or adding missing commands first. Each macro in
     * {@code keyMacros} gets a "runmacro.name" entry (empty until the user sets a key).
     */
    public static Keymap load(List<String> keyMacros) {
        List<Command> all = new ArrayList<>(COMMANDS);
        for (String m : keyMacros) all.add(new Command("runmacro." + m, "Macro " + m + " (macros.cfg)", ""));
        Keymap km = new Keymap();
        Path f = file();
        Properties p = new Properties();
        try {
            migrate(f);
            if (Files.exists(f)) {
                try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
            appendMissing(f, p, all);
        } catch (IOException e) {
            km.problems.add("Could not read " + f + ": " + e.getMessage());
        }

        Map<KeyStroke, String> used = new HashMap<>();
        for (Command c : all) {
            String value = p.getProperty(c.id(), c.defaultKey()).trim();
            if (value.isEmpty() || value.equalsIgnoreCase("none")) continue;
            KeyStroke ks = parse(value);
            if (ks == null) {
                km.problems.add(c.id() + ": cannot read \"" + value + "\"");
                continue;
            }
            String other = used.put(ks, c.id());
            if (other != null) {
                km.problems.add(format(ks) + " is used by both " + other + " and " + c.id());
            }
            km.keys.put(c.id(), ks);
        }
        return km;
    }

    public KeyStroke get(String id) {
        return keys.get(id);
    }

    /** The command bound to {@code ks}, or null. */
    public String idFor(KeyStroke ks) {
        for (Map.Entry<String, KeyStroke> e : keys.entrySet()) {
            if (e.getValue().equals(ks)) return e.getKey();
        }
        return null;
    }

    public List<String> problems() {
        return problems;
    }

    /** "Ctrl+Shift+S", "ctrl shift S", "Alt+Z", "F5", "Ctrl+," ... */
    public static KeyStroke parse(String text) {
        String[] parts = text.trim().split("\\s*\\+\\s*|\\s+");
        if (parts.length == 0) return null;
        // "Ctrl++" splits into an empty last part
        String key = text.trim().endsWith("+") && !text.trim().equals("+") ? "+" : parts[parts.length - 1];
        int last = key.equals("+") ? parts.length : parts.length - 1;
        StringBuilder mods = new StringBuilder();
        for (int i = 0; i < last; i++) {
            String m = parts[i].toLowerCase(Locale.ROOT);
            switch (m) {
                case "ctrl", "control" -> mods.append("ctrl ");
                case "shift" -> mods.append("shift ");
                case "alt", "option", "opt" -> mods.append("alt ");
                case "meta", "super", "win", "windows", "cmd", "command" -> mods.append("meta ");
                case "mod" -> mods.append(Platform.MAC ? "meta " : "ctrl ");
                case "tool" -> mods.append(Platform.LINUX ? "meta " : Platform.MAC ? "ctrl " : "ctrl shift ");
                case "altgr", "altgraph" -> mods.append("altGraph ");
                case "" -> { }
                default -> { return null; }
            }
        }
        String name = switch (key) {
            case "," -> "COMMA";
            case "." -> "PERIOD";
            case "/" -> "SLASH";
            case "\\" -> "BACK_SLASH";
            case "=" -> "EQUALS";
            case "-" -> "MINUS";
            case "+" -> "PLUS";
            case ";" -> "SEMICOLON";
            case "'" -> "QUOTE";
            case "`" -> "BACK_QUOTE";
            case "[" -> "OPEN_BRACKET";
            case "]" -> "CLOSE_BRACKET";
            default -> key.toUpperCase(Locale.ROOT);
        };
        return KeyStroke.getKeyStroke(mods + name);
    }

    /** Human-readable form, e.g. "Ctrl+Shift+S". */
    public static String format(KeyStroke ks) {
        if (ks == null) return "";
        String mods = InputEvent.getModifiersExText(ks.getModifiers());
        String key = switch (ks.getKeyCode()) {
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_SEMICOLON -> ";";
            default -> KeyEvent.getKeyText(ks.getKeyCode());
        };
        return mods.isEmpty() ? key : mods + "+" + key;
    }

    /** Marks keys.properties files that already have the version-2 defaults. */
    private static final String VERSION_2 = "#! keys version 2";

    /** Old default → new default, applied once to files written by earlier versions. */
    private static final Map<String, String[]> CHANGED_DEFAULTS = Map.of(
            "latex.build", new String[] {"F5", "Tool+X"},
            "latex.runLatex", new String[] {"Shift+F5", "Tool+C"},
            "latex.view", new String[] {"F6", "Tool+V"},
            "latex.runBibtex", new String[] {"Ctrl+F5", "Tool+B"},
            "edit.refactor", new String[] {"Meta+R", "Tool+R"});

    /**
     * Updates a file from an earlier version: shortcuts still at their old default get the new
     * one (a changed shortcut is kept). Done once; the file is then marked with VERSION_2.
     */
    private static void migrate(Path f) throws IOException {
        if (!Files.exists(f)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8));
        if (lines.stream().anyMatch(l -> l.startsWith(VERSION_2))) return;
        java.util.regex.Pattern entry = java.util.regex.Pattern.compile("^\\s*([\\w.]+)\\s*[=:]\\s*(.*?)\\s*$");
        for (int i = 0; i < lines.size(); i++) {
            java.util.regex.Matcher m = entry.matcher(lines.get(i));
            if (!m.matches()) continue;
            String[] change = CHANGED_DEFAULTS.get(m.group(1));
            if (change != null && m.group(2).equalsIgnoreCase(change[0])) {
                lines.set(i, m.group(1) + " = " + change[1]);
            }
        }
        int at = 0;
        while (at < lines.size() && lines.get(at).startsWith("#")) at++; // after the header comment
        lines.add(at, VERSION_2 + ": build keys moved to Tool+X/C/V/B; " + modifierHelp());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    /** What Mod and Tool mean on this system, for the file header and the manual. */
    public static String modifierHelp() {
        if (Platform.MAC) return "Mod is Cmd and Tool is the Control key on this Mac";
        if (Platform.WINDOWS) return "Mod is Ctrl and Tool is Ctrl+Shift on Windows";
        return "Mod is Ctrl and Tool is Meta (the Windows key) on Linux";
    }

    private static void appendMissing(Path f, Properties p, List<Command> all) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean fresh = !Files.exists(f);
        if (fresh) {
            sb.append("""
                    # Hypatia keyboard shortcuts.
                    #
                    # Edit and save (in Hypatia: File > Keyboard Shortcuts); changes apply on save.
                    # Format:   command = Mod+Shift+S
                    # Modifiers: Ctrl, Shift, Alt (Option), Meta (also Super, Win, Cmd), and
                    #           Mod   Cmd on macOS, Ctrl elsewhere (the usual shortcut key)
                    #           Tool  Meta (Windows key) on Linux, Control on macOS, Ctrl+Shift on
                    #                 Windows: Tool+X build, Tool+C pdfLaTeX, Tool+V view, Tool+B BibTeX
                    #           MODHELP.
                    #           The system keeps some combinations for itself; those never reach
                    #           Hypatia (KDE: Meta+V clipboard, Meta+E, Meta+L, Meta+D, Meta+arrows;
                    #           Windows: most Win+ combinations, which is why Tool is Ctrl+Shift there).
                    # Keys:     letters, digits, F1-F12, Enter, Space, Tab, Up, Down, Left, Right,
                    #           Home, End, Page_Up, Page_Down, Insert, Delete, and , . / \\ = - ; ' [ ]
                    # Leave a value empty to remove a shortcut. Lines starting with # are comments.
                    #
                    # Shortcuts set here take precedence over the editor's built-in keys
                    # (e.g. Mod+K, Mod+Up/Down).
                    VERSION
                    """.replace("MODHELP", modifierHelp()).replace("VERSION", VERSION_2));
        }
        String group = "";
        for (Command c : all) {
            if (p.containsKey(c.id())) continue;
            String g = c.id().substring(0, c.id().indexOf('.'));
            if ((fresh || g.equals("runmacro")) && !g.equals(group)) {
                sb.append("\n# --- ").append(g.equals("runmacro")
                        ? "macros from macros.cfg (set a key to use them)" : g).append(" ---\n");
                group = g;
            }
            sb.append("# ").append(c.description()).append('\n')
              .append(c.id()).append(" = ").append(escape(c.defaultKey())).append('\n');
            p.setProperty(c.id(), c.defaultKey());
        }
        if (sb.length() > 0) {
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Backslashes are escapes in .properties files. */
    private static String escape(String v) {
        return v.replace("\\", "\\\\");
    }
}
