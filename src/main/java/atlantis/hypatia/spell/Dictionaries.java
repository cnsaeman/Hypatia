package atlantis.hypatia.spell;

import atlantis.hypatia.Platform;
import atlantis.hypatia.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds and loads Hunspell dictionaries. Looked up first in the dictionaries folder of the
 * settings folder (where LaTeX > Download Dictionary puts them), then in the system's places:
 * on Linux ~/.local/share/hunspell, /usr/share/hunspell, /usr/share/myspell(/dicts); on
 * macOS ~/Library/Spelling, /Library/Spelling; and LibreOffice's dictionaries on macOS and
 * Windows. Loading happens in the background, once per language.
 */
public final class Dictionaries {

    private static final Pattern MAGIC = Pattern.compile(
            "^\\s*%\\s*!\\s*TeX\\s+spellcheck\\s*=\\s*([A-Za-z_\\-]+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, CompletableFuture<SpellDictionary>> LOADED = new ConcurrentHashMap<>();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hypatia-dictionary-loader");
        t.setDaemon(true);
        return t;
    });

    private Dictionaries() {}

    public static Path userDir() {
        return Settings.configDir().resolve("dictionaries");
    }

    static List<Path> searchPath() {
        String home = System.getProperty("user.home");
        List<Path> dirs = new ArrayList<>();
        dirs.add(userDir());
        if (Platform.MAC) {
            dirs.add(Path.of(home, "Library", "Spelling"));
            dirs.add(Path.of("/Library/Spelling"));
            libreOffice(Path.of("/Applications/LibreOffice.app/Contents/Resources/extensions"), dirs);
        } else if (Platform.WINDOWS) {
            for (String pf : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (pf != null) libreOffice(Path.of(pf, "LibreOffice", "share", "extensions"), dirs);
            }
        } else {
            dirs.add(Path.of(home, ".local", "share", "hunspell"));
            dirs.add(Path.of("/usr/share/hunspell"));
            dirs.add(Path.of("/usr/share/myspell"));
            dirs.add(Path.of("/usr/share/myspell/dicts"));
            dirs.add(Path.of("/usr/local/share/hunspell"));
        }
        return dirs;
    }

    /** LibreOffice's bundled dictionaries: extensions/dict-xx folders. */
    private static void libreOffice(Path extensions, List<Path> dirs) {
        if (!Files.isDirectory(extensions)) return;
        try (Stream<Path> s = Files.list(extensions)) {
            s.filter(p -> p.getFileName().toString().startsWith("dict-") && Files.isDirectory(p))
                    .sorted().forEach(dirs::add);
        } catch (Exception ignored) {
            // unreadable
        }
    }

    /** Drops a failed or stale load, e.g. after a dictionary was downloaded. */
    public static void forget(String language) {
        LOADED.remove(language);
        LOADED.entrySet().removeIf(e -> e.getValue().isCompletedExceptionally());
    }

    /** The language named by "% !TeX spellcheck = xx_YY" in the first 50 lines, or null. */
    public static String magicLanguage(String text) {
        int lines = 0;
        int i = 0;
        while (i < text.length() && lines < 50) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = text.length();
            Matcher m = MAGIC.matcher(text.substring(i, e));
            if (m.matches()) return normalize(m.group(1));
            i = e + 1;
            lines++;
        }
        return null;
    }

    /** "en-GB" -> "en_GB", "EN_gb" -> "en_GB". */
    public static String normalize(String code) {
        String[] p = code.trim().replace('-', '_').split("_", 2);
        return p.length == 1 ? p[0].toLowerCase() : p[0].toLowerCase() + "_" + p[1].toUpperCase();
    }

    /** All languages for which an .aff/.dic pair exists. */
    public static List<String> available() {
        TreeSet<String> langs = new TreeSet<>();
        for (Path dir : searchPath()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".dic"))
                        .map(n -> n.substring(0, n.length() - 4))
                        .filter(n -> Files.exists(dir.resolve(n + ".aff")))
                        .forEach(langs::add);
            } catch (Exception ignored) {
                // unreadable directory
            }
        }
        return new ArrayList<>(langs);
    }

    /** The .dic file for a language ("de" also finds de_DE), or null. */
    static Path find(String language) {
        for (Path dir : searchPath()) {
            Path dic = dir.resolve(language + ".dic");
            if (Files.exists(dic) && Files.exists(dir.resolve(language + ".aff"))) return dic;
        }
        // "de" finds de_DE, "de_DE" finds LibreOffice's de_DE_frami, "fr_FR" finds fr
        for (String l : available()) {
            if (l.startsWith(language + "_") && !l.equals(language)) return find(l);
        }
        if (language.contains("_")) {
            String base = language.substring(0, language.indexOf('_'));
            for (Path dir : searchPath()) {
                Path dic = dir.resolve(base + ".dic");
                if (Files.exists(dic) && Files.exists(dir.resolve(base + ".aff"))) return dic;
            }
        }
        return null;
    }

    /** Whether a dictionary for {@code language} can be found. */
    public static boolean exists(String language) {
        return find(language) != null;
    }

    /**
     * The dictionary for {@code language}, loading it in the background on first use.
     * The future completes exceptionally if none is installed.
     */
    public static CompletableFuture<SpellDictionary> get(String language) {
        return LOADED.computeIfAbsent(language, l -> CompletableFuture.supplyAsync(() -> {
            Path dic = find(l);
            if (dic == null) throw new IllegalStateException("No " + l + " dictionary found");
            String name = dic.getFileName().toString().replaceFirst("\\.dic$", "");
            Path aff = dic.resolveSibling(name + ".aff");
            try {
                return SpellDictionary.load(l, aff, dic, userDir().resolve(l + ".txt"));
            } catch (Exception e) {
                throw new IllegalStateException("Could not load " + dic + ": " + e.getMessage(), e);
            }
        }, LOADER));
    }

    /** The dictionary if it has finished loading, else null. */
    public static SpellDictionary ifLoaded(String language) {
        CompletableFuture<SpellDictionary> f = LOADED.get(language);
        return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
    }

    /** Re-reads every loaded dictionary's user file (after it was edited by hand). */
    public static void reloadUserWords() {
        for (CompletableFuture<SpellDictionary> f : LOADED.values()) {
            if (f.isDone() && !f.isCompletedExceptionally()) f.join().reloadUserWords();
        }
    }
}
