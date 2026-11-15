package atlantis.hypatia;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Everything that differs between Linux, macOS and Windows: where settings live, how files
 * are opened with their default program, and where TeX and the PDF viewers are found.
 */
public final class Platform {

    private Platform() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    public static final boolean MAC = OS.startsWith("mac") || OS.startsWith("darwin");
    public static final boolean WINDOWS = OS.startsWith("windows");
    public static final boolean LINUX = !MAC && !WINDOWS;

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /** The settings folder: ~/.config/hypatia, ~/Library/Application Support/Hypatia or %APPDATA%\Hypatia. */
    public static Path configDir() {
        String override = System.getenv("HYPATIA_CONFIG");
        if (override != null && !override.isBlank()) return Path.of(override);
        if (WINDOWS) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null && !appData.isBlank() ? Path.of(appData) : HOME.resolve("AppData").resolve("Roaming");
            return base.resolve("Hypatia");
        }
        if (MAC) return HOME.resolve("Library").resolve("Application Support").resolve("Hypatia");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : HOME.resolve(".config");
        return base.resolve("hypatia");
    }

    /** A path for messages: the home folder shortened to ~ on Linux and macOS. */
    public static String show(Path p) {
        Path abs = p.toAbsolutePath();
        if (!WINDOWS && abs.startsWith(HOME)) {
            Path rel = HOME.relativize(abs);
            return rel.toString().isEmpty() ? "~" : "~/" + rel.toString().replace(File.separatorChar, '/');
        }
        return abs.toString();
    }

    /** {@link #show} for use on a command line: absolute and in double quotes if it contains a space. */
    public static String arg(Path p) {
        String s = show(p);
        return s.contains(" ") ? "\"" + p.toAbsolutePath() + "\"" : s;
    }

    /** The JAR to put on javac's class path for macros and style plugins, as a command-line argument. */
    public static String jarArg() {
        Path jar = jarFile();
        return jar != null && jar.toString().endsWith(".jar") ? arg(jar) : "hypatia.jar";
    }

    /**
     * The absolute, normalised path. On macOS, whose disks usually ignore upper/lower case,
     * an existing file gets its real spelling, so that paths from TeX's log (which follow the
     * spelling in \\input) match open tabs. (Windows paths already compare case-insensitively.)
     */
    public static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (MAC) {
            try {
                return abs.toRealPath();
            } catch (IOException | SecurityException ignored) {
                // does not exist yet
            }
        }
        return abs;
    }

    /** Opens a file or folder with its default program (file manager for folders). */
    public static void open(Path p) throws IOException {
        List<String> cmd;
        if (MAC) cmd = List.of("/usr/bin/open", p.toString());
        else if (WINDOWS) cmd = null;
        else cmd = List.of("xdg-open", p.toString());
        if (cmd != null) {
            try {
                launch(cmd, p.getParent() != null ? p.getParent() : p);
                return;
            } catch (IOException e) {
                // fall back to Java's own mechanism below
            }
        }
        if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            java.awt.Desktop.getDesktop().open(p.toFile());
            return;
        }
        if (WINDOWS) {
            launch(List.of("explorer.exe", p.toString()), p.getParent() != null ? p.getParent() : p);
            return;
        }
        throw new IOException("No program found to open " + p);
    }

    private static void launch(List<String> cmd, Path dir) throws IOException {
        new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    // --- finding programs ------------------------------------------------------------------

    private static volatile List<Path> searchPath;

    /**
     * The folders searched for programs: PATH, then the usual TeX and viewer locations, which
     * matter on macOS (programs started from the Dock do not see the shell's PATH) and on
     * Windows (MiKTeX and TeX Live installed for one user).
     */
    public static List<Path> searchPath() {
        List<Path> sp = searchPath;
        if (sp != null) return sp;
        Set<Path> dirs = new LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                if (!d.isBlank()) dirs.add(Path.of(d.replace("\"", "")));
            }
        }
        if (MAC) {
            dirs.add(Path.of("/Library/TeX/texbin"));
            newestTexLive(Path.of("/usr/local/texlive"), dirs, "universal-darwin", "x86_64-darwin", "x86_64-darwinlegacy");
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/opt/local/bin"));
            dirs.add(Path.of("/Applications/Skim.app/Contents/SharedSupport"));
        } else if (WINDOWS) {
            String local = System.getenv("LOCALAPPDATA");
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            if (local != null) dirs.add(Path.of(local, "Programs", "MiKTeX", "miktex", "bin", "x64"));
            if (pf != null) dirs.add(Path.of(pf, "MiKTeX", "miktex", "bin", "x64"));
            newestTexLive(Path.of("C:\\texlive"), dirs, "windows", "win64", "win32");
            if (local != null) dirs.add(Path.of(local, "SumatraPDF"));
            if (pf != null) dirs.add(Path.of(pf, "SumatraPDF"));
            if (pf86 != null) dirs.add(Path.of(pf86, "SumatraPDF"));
        } else {
            newestTexLive(Path.of("/usr/local/texlive"), dirs, "x86_64-linux", "aarch64-linux");
            dirs.add(HOME.resolve(".local").resolve("bin"));
        }
        sp = List.copyOf(dirs);
        searchPath = sp;
        return sp;
    }

    /** Adds &lt;root&gt;/&lt;newest year&gt;/bin/&lt;arch&gt; for the first architecture that exists. */
    private static void newestTexLive(Path root, Set<Path> dirs, String... arches) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> years = Files.list(root)) {
            years.filter(y -> y.getFileName().toString().matches("\\d{4}"))
                    .max(Comparator.comparing(y -> y.getFileName().toString()))
                    .ifPresent(y -> {
                        for (String a : arches) {
                            Path bin = y.resolve("bin").resolve(a);
                            if (Files.isDirectory(bin)) {
                                dirs.add(bin);
                                return;
                            }
                        }
                    });
        } catch (IOException ignored) {
            // unreadable: skip
        }
    }

    /** The full path of a program on {@link #searchPath()}, or null. Names with a folder are checked as given. */
    public static Path findProgram(String name) {
        if (name == null || name.isBlank()) return null;
        if (name.contains("/") || name.contains(File.separator)) {
            Path p = Path.of(name);
            return Files.isRegularFile(p) ? p : WINDOWS ? withExtension(p) : null;
        }
        for (Path dir : searchPath()) {
            Path p = dir.resolve(name);
            if (WINDOWS) {
                Path q = withExtension(p);
                if (q != null) return q;
            } else if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path withExtension(Path p) {
        String name = p.getFileName().toString();
        if (name.contains(".") && Files.isRegularFile(p)) return p;
        String pathext = System.getenv("PATHEXT");
        String[] exts = (pathext != null && !pathext.isBlank() ? pathext : ".COM;.EXE;.BAT;.CMD").split(";");
        for (String ext : exts) {
            Path q = p.resolveSibling(name + ext.toLowerCase(Locale.ROOT));
            if (Files.isRegularFile(q)) return q;
        }
        return null;
    }

    /**
     * Prepares a process: the program is looked up on {@link #searchPath()} (Java itself only
     * searches the PATH Hypatia was started with), and the extended PATH is passed on so that
     * e.g. latexmk finds pdflatex.
     */
    public static ProcessBuilder processBuilder(List<String> command) {
        List<String> cmd = new ArrayList<>(command);
        if (!cmd.isEmpty()) {
            Path exe = findProgram(cmd.get(0));
            if (exe != null) cmd.set(0, exe.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        String key = WINDOWS ? env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("Path") : "PATH";
        env.put(key, String.join(File.pathSeparator, searchPath().stream().map(Path::toString).toList()));
        return pb;
    }

    // --- Hypatia itself ----------------------------------------------------------------------

    /** The running JAR (or classes folder), or null. */
    public static Path jarFile() {
        try {
            return Path.of(Platform.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The command line that starts Hypatia, for PDF viewers' inverse search: on Linux the
     * launcher installed by install.sh if it is on the PATH, else java -jar with the running
     * JAR. On Windows javaw is used so that no console window flashes up.
     */
    public static List<String> hypatiaCommand() {
        if (LINUX && findProgram("hypatia") != null) return List.of("hypatia");
        String java = ProcessHandle.current().info().command().orElse(WINDOWS ? "javaw" : "java");
        if (WINDOWS) {
            Path j = Path.of(java);
            Path w = j.resolveSibling("javaw.exe");
            if (j.getFileName().toString().equalsIgnoreCase("java.exe") && Files.isRegularFile(w)) java = w.toString();
        }
        Path jar = jarFile();
        if (jar == null || !jar.toString().endsWith(".jar")) return List.of("hypatia");
        return List.of(java, "-jar", jar.toString());
    }

    /** {@link #hypatiaCommand()} as one string, each part quoted when needed. */
    public static String hypatiaCommandLine() {
        StringBuilder sb = new StringBuilder();
        for (String part : hypatiaCommand()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.contains(" ") ? "\"" + part + "\"" : part);
        }
        return sb.toString();
    }
}
