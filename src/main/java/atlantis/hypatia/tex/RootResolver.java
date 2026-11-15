package atlantis.hypatia.tex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import atlantis.hypatia.Settings;

/**
 * Finds the main .tex file for a given file:
 *   1. a magic comment "% !TEX root = main.tex" in the first 50 lines,
 *   2. the file itself if it contains an (uncommented) \documentclass,
 *   3. a main file previously chosen for this file (remembered in the settings).
 * Returns null if none applies; the caller then asks the user once.
 */
public final class RootResolver {

    private static final Pattern MAGIC = Pattern.compile(
            "^\\s*%\\s*!\\s*TEX\\s+root\\s*=\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCCLASS = Pattern.compile("^[^%]*\\\\documentclass", Pattern.MULTILINE);

    public enum Source { MAGIC_COMMENT, SELF, REMEMBERED }

    public record Result(Path root, Source source) {}

    private RootResolver() {}

    public static Result resolve(Path file, String text, Settings settings) {
        if (file == null) return null;
        String[] lines = text.split("\n", 51);
        for (int i = 0; i < Math.min(50, lines.length); i++) {
            Matcher m = MAGIC.matcher(lines[i]);
            if (m.matches()) {
                Path root = file.getParent().resolve(m.group(1)).normalize();
                return new Result(root, Source.MAGIC_COMMENT);
            }
        }
        if (DOCCLASS.matcher(text).find()) {
            return new Result(file, Source.SELF);
        }
        Path remembered = settings.rootFor(file);
        if (remembered != null && Files.exists(remembered)) {
            return new Result(remembered, Source.REMEMBERED);
        }
        return null;
    }

    public static Path pdfFor(Path root) {
        return root.resolveSibling(baseName(root) + ".pdf");
    }

    public static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
