package atlantis.hypatia.style;

import atlantis.hypatia.Settings;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The styles defined in ~/.config/hypatia/styles/styles.cfg, with their regexp files
 * (styles/regexps) and plugins (styles/plugins). Loaded as a whole; plugins are loaded with a
 * fresh class loader each time, so recompiled plugins are picked up by reloading.
 *
 * <pre>
 * >>STYLE:default
 * regexps/typography.txt
 * regexps/*              (wildcards: all regexp files)
 * plugins/LongSentences.class
 * plugins/mychecks.jar   (every StylePlugin in the jar)
 * include other-style
 * </pre>
 */
public final class StyleLibrary {

    /** One regular expression with its explanation. */
    public record Rule(String explanation, Pattern pattern, String file, int line) {}

    /** A loaded plugin. */
    public record Plugin(String name, StylePlugin instance) {}

    /** A style ready to run. */
    public record Style(String name, List<Rule> rules, List<Plugin> plugins) {}

    private static final Pattern MAGIC = Pattern.compile(
            "^\\s*%\\s*!\\s*TeX\\s+style\\s*=\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private URLClassLoader loader;

    private StyleLibrary() {}

    public static Path dir() {
        return Settings.configDir().resolve("styles");
    }

    public static Path configFile() {
        return dir().resolve("styles.cfg");
    }

    public static Path regexpDir() {
        return dir().resolve("regexps");
    }

    public static Path pluginDir() {
        return dir().resolve("plugins");
    }

    /** The style named by "% !TeX style = name" in the first 50 lines, or null. */
    public static String magicStyle(String text) {
        int i = 0;
        for (int n = 0; n < 50 && i < text.length(); n++) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = text.length();
            Matcher m = MAGIC.matcher(text.substring(i, e));
            if (m.matches()) return m.group(1);
            i = e + 1;
        }
        return null;
    }

    // --- loading --------------------------------------------------------------------------------

    /** Loads styles.cfg (creating the folder with examples on first use) and everything it names. */
    public static StyleLibrary load() {
        StyleLibrary lib = new StyleLibrary();
        try {
            DefaultStyles.createIfMissing();
            lib.read(Files.readString(configFile(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            lib.problems.add("Could not read " + configFile() + ": " + e.getMessage());
        }
        return lib;
    }

    public List<String> names() {
        return new ArrayList<>(styles.keySet());
    }

    public Style get(String name) {
        return styles.get(name);
    }

    /** "default" if defined, else the first style, else null. */
    public String defaultName() {
        if (styles.containsKey("default")) return "default";
        return styles.isEmpty() ? null : styles.keySet().iterator().next();
    }

    public List<String> problems() {
        return problems;
    }

    /** Releases the plugin class loader (the library must not be used afterwards). */
    public void close() {
        try {
            if (loader != null) loader.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private void read(String cfg) {
        // 1. entries per style, in file order
        Map<String, List<String[]>> raw = new LinkedHashMap<>(); // entry, line number
        String current = null;
        String[] lines = cfg.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String where = "styles.cfg line " + (i + 1);
            if (line.startsWith(">>")) {
                String h = line.substring(2).strip();
                if (h.toUpperCase().startsWith("STYLE:") && h.substring(6).strip().matches("[A-Za-z0-9_.-]+")) {
                    current = h.substring(6).strip();
                    if (raw.containsKey(current)) problems.add(where + ": style " + current + " is defined twice");
                    raw.putIfAbsent(current, new ArrayList<>());
                } else {
                    problems.add(where + ": expected >>STYLE:name (letters, digits, _ - .)");
                    current = null;
                }
                continue;
            }
            if (current == null) {
                problems.add(where + ": entry outside a style");
                continue;
            }
            raw.get(current).add(new String[] {line, String.valueOf(i + 1)});
        }

        // 2. flatten includes into file lists
        Map<String, Set<Path>> regexpFiles = new LinkedHashMap<>();
        Map<String, Set<Path>> pluginFiles = new LinkedHashMap<>();
        for (String name : raw.keySet()) {
            Set<Path> r = new LinkedHashSet<>();
            Set<Path> p = new LinkedHashSet<>();
            collect(name, raw, r, p, new LinkedHashSet<>());
            regexpFiles.put(name, r);
            pluginFiles.put(name, p);
        }

        // 3. load each regexp file and plugin once
        Map<Path, List<Rule>> rulesByFile = new HashMap<>();
        for (Set<Path> files : regexpFiles.values()) {
            for (Path f : files) rulesByFile.computeIfAbsent(f, this::readRegexps);
        }
        Set<Path> allPlugins = new LinkedHashSet<>();
        pluginFiles.values().forEach(allPlugins::addAll);
        Map<Path, List<Plugin>> pluginsByFile = loadPlugins(allPlugins);

        for (String name : raw.keySet()) {
            List<Rule> rules = new ArrayList<>();
            regexpFiles.get(name).forEach(f -> rules.addAll(rulesByFile.getOrDefault(f, List.of())));
            List<Plugin> plugins = new ArrayList<>();
            pluginFiles.get(name).forEach(f -> plugins.addAll(pluginsByFile.getOrDefault(f, List.of())));
            styles.put(name, new Style(name, List.copyOf(rules), List.copyOf(plugins)));
        }
    }

    private void collect(String name, Map<String, List<String[]>> raw, Set<Path> regexps, Set<Path> plugins,
                         Set<String> visiting) {
        if (!visiting.add(name)) {
            problems.add("styles.cfg: style " + name + " includes itself");
            return;
        }
        for (String[] e : raw.get(name)) {
            String entry = e[0];
            String where = "styles.cfg line " + e[1];
            if (entry.startsWith("include ")) {
                String other = entry.substring(8).strip();
                if (raw.containsKey(other)) {
                    collect(other, raw, regexps, plugins, visiting);
                } else {
                    problems.add(where + ": no style called " + other);
                }
                continue;
            }
            String rel = entry.replace('\\', '/');
            boolean isRegexp = rel.startsWith("regexps/");
            boolean isPlugin = rel.startsWith("plugins/");
            if (!isRegexp && !isPlugin || rel.contains("..")) {
                problems.add(where + ": entries start with regexps/, plugins/ or include");
                continue;
            }
            List<Path> files = expand(rel, isPlugin);
            if (files.isEmpty() && !rel.contains("*")) problems.add(where + ": " + rel + " not found");
            (isRegexp ? regexps : plugins).addAll(files);
        }
        visiting.remove(name);
    }

    /** Files for an entry, with * and ** wildcards. Plugins: only .class and .jar files. */
    private static List<Path> expand(String rel, boolean plugin) {
        Path base = dir();
        if (!rel.contains("*") && !rel.contains("?")) {
            Path p = base.resolve(rel).normalize();
            if (plugin && !rel.endsWith(".class") && !rel.endsWith(".jar") && !Files.exists(p)) {
                p = base.resolve(rel + ".class").normalize(); // "plugins/LongSentences"
            }
            return Files.isRegularFile(p) ? List.of(p) : List.of();
        }
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + rel);
        Path root = base.resolve(rel.startsWith("plugins/") ? "plugins" : "regexps");
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> m.matches(base.relativize(p)))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (n.startsWith(".") || n.endsWith("~")) return false;
                        return !plugin || n.endsWith(".jar") || (n.endsWith(".class") && !n.contains("$"));
                    })
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // --- regexp files -------------------------------------------------------------------------

    private List<Rule> readRegexps(Path f) {
        List<Rule> rules = new ArrayList<>();
        String name = dir().relativize(f).toString();
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            problems.add("Could not read " + name + ": " + e.getMessage());
            return rules;
        }
        String explanation = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) continue;
            if (line.startsWith(">>")) {
                explanation = line.substring(2).strip();
                continue;
            }
            if (explanation == null) {
                problems.add(name + " line " + (i + 1) + ": regular expression before the first >> explanation");
                continue;
            }
            try {
                rules.add(new Rule(explanation, Pattern.compile(line, Pattern.MULTILINE), name, i + 1));
            } catch (PatternSyntaxException e) {
                problems.add(name + " line " + (i + 1) + ": " + e.getDescription() + " in " + line.strip());
            }
        }
        return rules;
    }

    // --- plugins ------------------------------------------------------------------------------

    private Map<Path, List<Plugin>> loadPlugins(Set<Path> files) {
        Map<Path, List<Plugin>> out = new HashMap<>();
        if (files.isEmpty()) return out;
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(pluginDir().toUri().toURL());
            for (Path f : files) {
                if (f.toString().endsWith(".jar")) urls.add(f.toUri().toURL());
            }
        } catch (IOException e) {
            problems.add("Plugins: " + e.getMessage());
            return out;
        }
        loader = new URLClassLoader(urls.toArray(URL[]::new), StylePlugin.class.getClassLoader());
        for (Path f : files) {
            String rel = pluginDir().relativize(f).toString().replace('\\', '/');
            List<Plugin> plugins = new ArrayList<>();
            if (rel.endsWith(".jar")) {
                try (JarFile jar = new JarFile(f.toFile())) {
                    Enumeration<JarEntry> en = jar.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.endsWith(".class") && !n.contains("$") && !n.endsWith("module-info.class")) {
                            Plugin p = instantiate(n.substring(0, n.length() - 6).replace('/', '.'), rel, false);
                            if (p != null) plugins.add(p);
                        }
                    }
                } catch (IOException e) {
                    problems.add("plugins/" + rel + ": " + e.getMessage());
                }
                if (plugins.isEmpty()) problems.add("plugins/" + rel + " contains no StylePlugin");
            } else {
                Plugin p = instantiate(rel.substring(0, rel.length() - 6).replace('/', '.'), rel, true);
                if (p != null) plugins.add(p);
            }
            out.put(f, plugins);
        }
        return out;
    }

    /** Loads and instantiates a class if it is a StylePlugin; {@code explicit}: report if it is not. */
    private Plugin instantiate(String className, String file, boolean explicit) {
        try {
            Class<?> c = Class.forName(className, true, loader);
            if (!StylePlugin.class.isAssignableFrom(c) || c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                if (explicit) problems.add("plugins/" + file + " does not implement atlantis.hypatia.style.StylePlugin");
                return null;
            }
            StylePlugin sp = (StylePlugin) c.getConstructor().newInstance();
            return new Plugin(c.getSimpleName(), sp);
        } catch (NoClassDefFoundError e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            Matcher m = Pattern.compile("wrong name: ([\\w/$]+)").matcher(msg);
            if (m.find()) {
                String real = m.group(1);
                problems.add("plugins/" + file + " is the class " + real.replace('/', '.') + "; it must be at plugins/"
                        + real + ".class (javac -d " + atlantis.hypatia.Platform.arg(pluginDir()) + " puts it there)");
            } else {
                problems.add("plugins/" + file + ": missing class " + msg);
            }
        } catch (NoSuchMethodException e) {
            problems.add("plugins/" + file + ": needs a public constructor without arguments");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            problems.add("plugins/" + file + ": " + t);
        }
        return null;
    }
}
