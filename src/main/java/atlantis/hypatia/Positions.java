package atlantis.hypatia;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The last editing position of every file, in ~/.config/hypatia/positions.properties:
 * {@code /path/to/file.tex = caretOffset,topLine,timeMillis}. Only the most recent
 * {@value #MAX} files are kept.
 */
public final class Positions {

    public record Position(int caret, int topLine) {}

    private static final int MAX = 1000;

    private final Path file;
    private final Properties p = new Properties();

    private Positions(Path file) {
        this.file = file;
    }

    public static Positions load() {
        Positions ps = new Positions(Settings.configDir().resolve("positions.properties"));
        if (Files.exists(ps.file)) {
            try (Reader r = Files.newBufferedReader(ps.file, StandardCharsets.UTF_8)) {
                ps.p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Hypatia: could not read " + ps.file + ": " + e.getMessage());
            }
        }
        return ps;
    }

    public Position get(Path f) {
        String v = p.getProperty(f.toString());
        if (v == null) return null;
        String[] parts = v.split(",");
        try {
            return new Position(Integer.parseInt(parts[0].trim()),
                    parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void put(Path f, int caret, int topLine) {
        p.setProperty(f.toString(), caret + "," + topLine + "," + System.currentTimeMillis());
    }

    public void save() {
        if (p.size() > MAX) trim();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "Hypatia: last editing position per file (caret offset, top line, time)");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Hypatia: could not save " + file + ": " + e.getMessage());
        }
    }

    /** Drops the oldest entries. */
    private void trim() {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(p.entrySet());
        entries.sort(Comparator.comparingLong(e -> -time(e.getValue().toString())));
        p.clear();
        for (int i = 0; i < Math.min(MAX, entries.size()); i++) {
            p.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    private static long time(String v) {
        String[] parts = v.split(",");
        try {
            return parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
