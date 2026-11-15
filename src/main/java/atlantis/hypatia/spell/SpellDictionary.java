package atlantis.hypatia.spell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.apache.lucene.analysis.hunspell.Dictionary;
import org.apache.lucene.analysis.hunspell.Hunspell;
import org.apache.lucene.analysis.hunspell.TimeoutPolicy;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * A Hunspell dictionary (.aff/.dic) plus the user's additions for that language
 * (~/.config/hypatia/dictionaries/LANG.txt, one word per line) and the words ignored
 * in this session.
 */
public final class SpellDictionary {

    private final String language;
    private final Hunspell hunspell;
    private final Path userFile;
    private final Set<String> userWords = ConcurrentHashMap.newKeySet();
    private final Set<String> ignored = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    private SpellDictionary(String language, Hunspell hunspell, Path userFile) {
        this.language = language;
        this.hunspell = hunspell;
        this.userFile = userFile;
    }

    /** Loads the Hunspell files (takes up to a second for large dictionaries). */
    static SpellDictionary load(String language, Path aff, Path dic, Path userFile) throws IOException {
        Path tmp = Files.createTempDirectory("hypatia-hunspell");
        Hunspell h;
        try (InputStream a = Files.newInputStream(aff); InputStream d = Files.newInputStream(dic);
             NIOFSDirectory dir = new NIOFSDirectory(tmp)) {
            Dictionary dictionary = new Dictionary(dir, "hunspell", a, d);
            h = new Hunspell(dictionary, TimeoutPolicy.RETURN_PARTIAL_RESULT, () -> {});
        } catch (java.text.ParseException e) {
            throw new IOException("cannot read " + aff + ": " + e.getMessage(), e);
        } finally {
            try (Stream<Path> files = Files.walk(tmp)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        SpellDictionary sd = new SpellDictionary(language, h, userFile);
        sd.loadUserWords();
        return sd;
    }

    private void loadUserWords() {
        if (!Files.exists(userFile)) return;
        try {
            for (String w : Files.readAllLines(userFile, StandardCharsets.UTF_8)) {
                w = w.strip();
                if (!w.isEmpty() && !w.startsWith("#")) userWords.add(w);
            }
        } catch (IOException ignored) {
            // unreadable: no additions
        }
    }

    public String language() {
        return language;
    }

    public Path userFile() {
        return userFile;
    }

    public boolean isCorrect(String word) {
        String w = word.replace('’', '\'');
        if (ignored.contains(w) || inUserWords(w)) return true;
        return cache.computeIfAbsent(w, this::spell);
    }

    /** Hunspell is not thread-safe; checks run in the background, suggestions on the event thread. */
    private boolean spell(String w) {
        synchronized (hunspell) {
            return hunspell.spell(w);
        }
    }

    /** User words match exactly, or capitalised ("Homotopy" for "homotopy"), or in capitals. */
    private boolean inUserWords(String w) {
        if (userWords.contains(w)) return true;
        String lower = w.toLowerCase();
        if (w.length() > 1 && Character.isUpperCase(w.charAt(0))) {
            String decap = Character.toLowerCase(w.charAt(0)) + w.substring(1);
            if (userWords.contains(decap)) return true;
        }
        return w.equals(w.toUpperCase()) && userWords.stream().anyMatch(u -> u.toLowerCase().equals(lower));
    }

    /** Up to {@code max} suggestions (may take a moment for long or very wrong words). */
    public List<String> suggest(String word, int max) {
        List<String> s;
        try {
            synchronized (hunspell) {
                s = hunspell.suggest(word.replace('’', '\''));
            }
        } catch (RuntimeException e) {
            s = List.of();
        }
        List<String> out = new ArrayList<>();
        for (String x : s) {
            if (out.size() >= max) break;
            if (!out.contains(x)) out.add(x);
        }
        return out;
    }

    /** Adds a word to the user dictionary file. */
    public void addWord(String word) throws IOException {
        String w = word.replace('’', '\'');
        if (!userWords.add(w)) return;
        Files.createDirectories(userFile.getParent());
        Files.writeString(userFile, w + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Ignores a word until Hypatia is closed. */
    public void ignore(String word) {
        ignored.add(word.replace('’', '\''));
    }

    /** Re-reads the user file (after it was edited by hand). */
    public void reloadUserWords() {
        userWords.clear();
        loadUserWords();
    }
}
