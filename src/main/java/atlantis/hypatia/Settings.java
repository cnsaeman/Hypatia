package atlantis.hypatia;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * User settings, stored as hypatia.properties in the settings folder
 * ({@link Platform#configDir()}, on Linux usually ~/.config/hypatia).
 */
public final class Settings {

    public static final String DEFAULT_COMPILE =
            "latexmk -pdf -synctex=1 -interaction=nonstopmode -file-line-error %f";
    public static final String DEFAULT_LATEX =
            "pdflatex -synctex=1 -interaction=nonstopmode -file-line-error %f";
    public static final String DEFAULT_BIBTEX = "bibtex %n";
    private static final int MAX_RECENT = 12;

    private final Path file;
    private final Properties p = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    /** See {@link Platform#configDir()}. */
    public static Path configDir() {
        return Platform.configDir();
    }

    public static Settings load() {
        Settings s = new Settings(configDir().resolve("hypatia.properties"));
        if (Files.exists(s.file)) {
            try (Reader r = Files.newBufferedReader(s.file, StandardCharsets.UTF_8)) {
                s.p.load(r);
            } catch (IOException e) {
                System.err.println("Hypatia: could not read settings: " + e.getMessage());
            }
        }
        return s;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "Hypatia settings");
            }
        } catch (IOException e) {
            System.err.println("Hypatia: could not save settings: " + e.getMessage());
        }
    }

    // --- typed accessors -------------------------------------------------

    public String get(String key, String def) {
        return p.getProperty(key, def);
    }

    public void set(String key, String value) {
        if (value == null) p.remove(key); else p.setProperty(key, value);
    }

    public boolean getBool(String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void setBool(String key, boolean v) {
        p.setProperty(key, Boolean.toString(v));
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    public void setInt(String key, int v) {
        p.setProperty(key, Integer.toString(v));
    }

    // --- named settings ----------------------------------------------------

    public boolean isDark() { return !"light".equals(get("theme", "dark")); }
    public void setDark(boolean dark) { set("theme", dark ? "dark" : "light"); }

    public String fontFamily() { return get("editor.font.family", ""); }
    public int fontSize() { return getInt("editor.font.size", 14); }
    public boolean softWrap() { return getBool("editor.softWrap", true); }
    public boolean autoIndent() { return getBool("editor.autoIndent", true); }
    public boolean spellEnabled() { return getBool("spell.enabled", true); }
    /** Language for files without "% !TeX spellcheck"; empty means no checking. */
    public String spellLanguage() { return get("spell.defaultLanguage", "en_GB").trim(); }
    public boolean indentDocument() { return getBool("editor.indentDocument", true); }
    public boolean autoCloseEnvironments() { return getBool("editor.autoCloseEnvironments", true); }

    public String compileCommand() { return get("compile.command", DEFAULT_COMPILE); }
    public String latexCommand() { return get("compile.latexCommand", DEFAULT_LATEX); }
    public String bibtexCommand() { return get("compile.bibtexCommand", DEFAULT_BIBTEX); }
    /** Forward search; without a setting, the first installed viewer of {@link Viewers#presets()}. */
    public String viewerCommand() {
        String v = get("viewer.command", "");
        return v.isBlank() ? Viewers.defaultViewer().forward() : v;
    }
    /** A viewer window of its own (e.g. Okular without --unique). */
    public String newViewerCommand() {
        String v = get("viewer.newWindowCommand", "");
        return v.isBlank() ? Viewers.defaultViewer().newWindow() : v;
    }
    /** Okular: a window of its own for each PDF instead of one window for all (see {@link OkularWindows}). */
    public boolean separateViewerWindows() { return getBool("viewer.separateWindows", true); }
    public boolean outlineVisible() { return getBool("outline.visible", true); }
    public int outlineWidth() { return getInt("outline.width", 240); }
    public boolean syncAfterCompile() { return getBool("compile.showPdfAfter", true); }
    public boolean restoreSession() { return getBool("session.restore", true); }

    // --- main (root) file memory -------------------------------------------

    public Path rootFor(Path texFile) {
        String v = p.getProperty("root." + texFile);
        return v == null ? null : Path.of(v);
    }

    public void setRootFor(Path texFile, Path root) {
        set("root." + texFile, root == null ? null : root.toString());
    }

    // --- recent files --------------------------------------------------------

    public List<Path> recentFiles() {
        List<Path> list = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String v = p.getProperty("recent." + i);
            if (v != null) list.add(Path.of(v));
        }
        return list;
    }

    public void addRecent(Path f) {
        List<Path> list = recentFiles();
        list.remove(f);
        list.add(0, f);
        for (int i = 0; i < MAX_RECENT; i++) {
            set("recent." + i, i < list.size() ? list.get(i).toString() : null);
        }
    }

    // --- session ----------------------------------------------------------------

    public record SessionEntry(Path file, int caret) {}

    public List<SessionEntry> session() {
        List<SessionEntry> list = new ArrayList<>();
        for (int i = 0; ; i++) {
            String v = p.getProperty("session.file." + i);
            if (v == null) break;
            list.add(new SessionEntry(Path.of(v), getInt("session.caret." + i, 0)));
        }
        return list;
    }

    public void setSession(List<SessionEntry> entries, int active) {
        p.keySet().removeIf(k -> k.toString().startsWith("session.file.")
                || k.toString().startsWith("session.caret."));
        for (int i = 0; i < entries.size(); i++) {
            set("session.file." + i, entries.get(i).file().toString());
            setInt("session.caret." + i, entries.get(i).caret());
        }
        setInt("session.active", active);
    }
}
