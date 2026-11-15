package atlantis.hypatia.tex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the files of a LaTeX project: the main file, everything it pulls in with
 * \input, \include, \subfile or \import, and the bibliography databases named in
 * \bibliography / \addbibresource.
 */
public final class ProjectScanner {

    private static final Pattern INCLUDE = Pattern.compile(
            "\\\\(input|include|subfile|InputIfFileExists)\\s*\\{([^}]+)\\}"
            + "|\\\\(?:sub)?(?:import|includefrom|inputfrom)\\s*\\{([^}]*)\\}\\s*\\{([^}]+)\\}");
    public static final Pattern BIBLIOGRAPHY = Pattern.compile("\\\\bibliography\\s*\\{([^}]*)\\}");
    public static final Pattern BIBRESOURCE = Pattern.compile(
            "\\\\add(?:bibresource|globalbib|sectionbib)\\s*(?:\\[[^\\]]*\\])?\\s*\\{([^}]*)\\}");

    /** "% !TeX bibliography = refs.bib" in the first 50 lines of a file. */
    public static final Pattern MAGIC_BIB = Pattern.compile(
            "^[ \\t]*%\\s*!\\s*TEX\\s+bibliography\\s*=[ \\t]*(.*?)[ \\t]*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final Function<Path, String> reader;

    /** @param reader returns a file's current text (e.g. from an open editor), or null */
    public ProjectScanner(Function<Path, String> reader) {
        this.reader = reader;
    }

    /** The main file followed by the files it includes, in reading order. */
    public List<Path> texFiles(Path root) {
        Set<Path> seen = new LinkedHashSet<>();
        visit(root, root.getParent(), seen, 0);
        return new ArrayList<>(seen);
    }

    private void visit(Path file, Path rootDir, Set<Path> seen, int depth) {
        if (depth > 30 || !seen.add(file)) return;
        String text = reader.apply(file);
        if (text == null) return;
        Matcher m = INCLUDE.matcher(stripComments(text));
        while (m.find()) {
            Path p;
            if (m.group(2) != null) {
                p = resolveTex(m.group(2).trim(), rootDir, file.getParent());
            } else {
                Path dir = file.getParent().resolve(m.group(3).trim());
                p = resolveTex(m.group(4).trim(), dir, dir);
            }
            if (p != null) visit(p, rootDir, seen, depth + 1);
        }
    }

    /** LaTeX looks up \input relative to the main file's folder; try the including file's folder too. */
    public static Path resolveTex(String name, Path rootDir, Path fileDir) {
        for (Path dir : new Path[] {rootDir, fileDir}) {
            Path p = dir.resolve(name).normalize();
            if (Files.isRegularFile(p)) return p;
            Path tex = dir.resolve(name + ".tex").normalize();
            if (Files.isRegularFile(tex)) return tex;
        }
        return null;
    }

    /**
     * Bibliography files named in the project; if none are named, every .bib file
     * in the main file's folder. Names that are not found next to the main file are
     * looked up with kpsewhich (e.g. a central ~/texmf/bibtex/bib/refs.bib).
     */
    public List<Path> bibFiles(Path root, List<Path> texFiles) {
        // "% !TeX bibliography = ..." in any file of the project replaces the detection
        Set<Path> magic = new LinkedHashSet<>();
        for (Path f : texFiles) {
            String text = reader.apply(f);
            if (text != null) magic.addAll(magicBibFiles(f, text));
        }
        if (!magic.isEmpty()) return new ArrayList<>(magic);

        Set<Path> found = new LinkedHashSet<>();
        Path dir = root.getParent();
        for (Path f : texFiles) {
            String text = reader.apply(f);
            if (text == null) continue;
            String code = stripComments(text);
            Matcher m = BIBLIOGRAPHY.matcher(code);
            while (m.find()) {
                for (String name : m.group(1).split(",")) addBib(found, dir, name.trim());
            }
            m = BIBRESOURCE.matcher(code);
            while (m.find()) addBib(found, dir, m.group(1).trim());
        }
        if (found.isEmpty()) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".bib")).sorted().forEach(found::add);
            } catch (Exception ignored) {
                // unreadable folder: no bibliography
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * The files named by "% !TeX bibliography = path" lines in the first 50 lines of
     * {@code text}: relative to the file's folder, ~ for the home folder; a name without
     * folder that is not found there is looked up with kpsewhich. Missing files are left out.
     */
    public static List<Path> magicBibFiles(Path file, String text) {
        List<Path> out = new ArrayList<>();
        Matcher m = MAGIC_BIB.matcher(firstLines(text, 50));
        while (m.find()) {
            String name = m.group(1).trim();
            if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
            if (name.isEmpty()) continue;
            if (name.equals("~") || name.startsWith("~/")) name = System.getProperty("user.home") + name.substring(1);
            Path dir = file.getParent() == null ? Path.of(".") : file.getParent();
            Path p = dir.resolve(name).normalize();
            if (!Files.isRegularFile(p) && !name.endsWith(".bib")) p = dir.resolve(name + ".bib").normalize();
            if (!Files.isRegularFile(p) && !name.contains("/")) {
                Path k = kpsewhich(name.endsWith(".bib") ? name : name + ".bib");
                if (k != null) p = k;
            }
            if (Files.isRegularFile(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private static String firstLines(String text, int n) {
        int i = -1;
        for (int k = 0; k < n; k++) {
            i = text.indexOf('\n', i + 1);
            if (i < 0) return text;
        }
        return text.substring(0, i);
    }

    private static void addBib(Set<Path> found, Path dir, String name) {
        Path p = resolveBib(name, dir);
        if (p != null) found.add(p);
    }

    /** A name from \bibliography or \addbibresource: next to the main file, else via kpsewhich; or null. */
    public static Path resolveBib(String name, Path dir) {
        if (name.isEmpty()) return null;
        String file = name.endsWith(".bib") ? name : name + ".bib";
        Path p = dir.resolve(file).normalize();
        if (Files.isRegularFile(p)) return p;
        return kpsewhich(file);
    }

    /** The file kpsewhich finds for {@code file}, or null. */
    public static Path kpsewhich(String file) {
        try {
            Process pr = atlantis.hypatia.Platform.processBuilder(List.of("kpsewhich", file)).redirectErrorStream(true).start();
            pr.getOutputStream().close();
            if (!pr.waitFor(2, TimeUnit.SECONDS)) {
                pr.destroy();
                return null;
            }
            String out = new String(pr.getInputStream().readAllBytes()).trim();
            if (pr.exitValue() == 0 && !out.isEmpty()) {
                Path p = Path.of(out.lines().findFirst().orElse(""));
                return Files.isRegularFile(p) ? p.toAbsolutePath().normalize() : null;
            }
        } catch (Exception ignored) {
            // kpsewhich not installed
        }
        return null;
    }

    /** Removes % comments (but not \%) from every line. */
    public static String stripComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            sb.append(stripComment(line)).append('\n');
        }
        return sb.toString();
    }

    public static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '%') {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
