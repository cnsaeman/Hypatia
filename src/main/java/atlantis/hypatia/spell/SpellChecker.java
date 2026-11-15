package atlantis.hypatia.spell;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Highlighter;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SquiggleUnderlineHighlightPainter;

/**
 * Spell checking as you type for one text area: underlines unknown words and offers
 * corrections on right-click. The check runs on a background thread shortly after typing
 * stops; only the underlines that changed are updated, so long documents stay responsive.
 */
public final class SpellChecker {

    /** A misspelled word: source range, source text and the word as checked. */
    public record Misspelling(int start, int end, String source, String word) {}

    /** An underline in the document; its highlight moves with edits. */
    private record Mark(Highlighter.Highlight highlight, Misspelling m) {}

    private static final int DELAY_MS = 400;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** All checks run here, one at a time. */
    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hypatia-spell-check");
        t.setDaemon(true);
        return t;
    });

    private final RSyntaxTextArea area;
    private final Supplier<SpellConfig> config;
    private final Function<String, String> languageFor; // document text -> language, or null for none
    private final Supplier<Color> squiggle;
    private final Consumer<String> onProblem;
    private final Runnable onDictionaryChanged;
    private final SquiggleUnderlineHighlightPainter painter;
    private final Timer timer = new Timer(DELAY_MS, e -> runNow());
    private final List<Mark> marks = new ArrayList<>();

    private volatile String language;
    private long version;
    private boolean running;

    public SpellChecker(RSyntaxTextArea area, Supplier<SpellConfig> config, Function<String, String> languageFor,
                        Supplier<Color> squiggle, Consumer<String> onProblem, Runnable onDictionaryChanged) {
        this.area = area;
        this.config = config;
        this.languageFor = languageFor;
        this.squiggle = squiggle;
        this.onProblem = onProblem;
        this.onDictionaryChanged = onDictionaryChanged;
        this.painter = new SquiggleUnderlineHighlightPainter(squiggle.get());
        timer.setRepeats(false);
    }

    /** Starts checking: now, and after each pause in typing. */
    public void install() {
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(); }
            @Override public void removeUpdate(DocumentEvent e) { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        timer.restart();
    }

    /** The language of the last check, or null if checking is off for this document. */
    public String language() {
        return language;
    }

    /** Checks again now (dictionary, language or settings changed). */
    public void recheck() {
        version++; // results of a check already running are outdated
        runNow();
    }

    private void edited() {
        version++;
        timer.restart();
    }

    private void runNow() {
        timer.stop();
        if (running) { // one check at a time; try again shortly
            timer.restart();
            return;
        }
        String text = area.getText();
        String lang = languageFor.apply(text); // on the event thread: may look at other open tabs
        language = lang;
        if (lang == null) {
            apply(List.of());
            return;
        }
        SpellDictionary dict = Dictionaries.ifLoaded(lang);
        if (dict == null) {
            Dictionaries.get(lang).whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
                if (ex != null) {
                    if (REPORTED.add(lang)) onProblem.accept(rootMessage(ex));
                } else {
                    recheck();
                }
            }));
            apply(List.of());
            return;
        }
        SpellConfig cfg = config.get();
        long v = version;
        running = true;
        CHECKS.submit(() -> {
            List<Misspelling> found;
            try {
                found = check(text, dict, cfg);
            } catch (RuntimeException e) {
                found = List.of();
            }
            List<Misspelling> result = found;
            SwingUtilities.invokeLater(() -> {
                running = false;
                if (v != version) { // edited meanwhile: check again after the pause
                    if (!timer.isRunning()) timer.restart();
                    return;
                }
                if (!lang.equals(language)) return;
                apply(result);
            });
        });
    }

    /** Shows {@code found}, keeping the underlines that are still right. */
    private void apply(List<Misspelling> found) {
        Color c = squiggle.get();
        if (!c.equals(painter.getPaint())) {
            painter.setPaint(c);
            area.repaint();
        }
        Map<Long, Mark> current = new HashMap<>();
        for (Mark mk : marks) current.put(key(mk.highlight().getStartOffset(), mk.highlight().getEndOffset()), mk);
        Highlighter h = area.getHighlighter();
        List<Mark> next = new ArrayList<>(found.size());
        for (Misspelling m : found) {
            Mark old = current.remove(key(m.start(), m.end()));
            if (old != null && old.m().source().equals(m.source())) {
                next.add(new Mark(old.highlight(), m));
                continue;
            }
            if (old != null) h.removeHighlight(old.highlight());
            try {
                next.add(new Mark((Highlighter.Highlight) h.addHighlight(m.start(), m.end(), painter), m));
            } catch (BadLocationException ignored) {
                // outside the document
            }
        }
        for (Mark gone : current.values()) h.removeHighlight(gone.highlight());
        marks.clear();
        marks.addAll(next);
    }

    private static long key(int start, int end) {
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    /** Tooltip for the mouse at {@code offset}, or null. */
    public String tooltipAt(int offset) {
        Misspelling m = at(offset);
        return m == null || language == null ? null
                : "“" + m.word() + "” is not in the " + language + " dictionary (right-click for suggestions)";
    }

    private static String rootMessage(Throwable ex) {
        while (ex.getCause() != null && ex.getMessage() != null && ex.getMessage().contains("Exception")) {
            ex = ex.getCause();
        }
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        if (msg.startsWith("No ")) {
            msg += " (use LaTeX > Download Dictionary, or put LANG.aff and LANG.dic into "
                    + atlantis.hypatia.Platform.show(Dictionaries.userDir())
                    + (atlantis.hypatia.Platform.LINUX ? "; or install hunspell/myspell dictionaries, e.g. myspell-en_GB)" : ")");
        }
        return msg;
    }

    /** All misspelled words of a document. */
    public static List<Misspelling> check(String text, SpellDictionary dict, SpellConfig config) {
        List<Misspelling> out = new ArrayList<>();
        for (LatexWordScanner.Word w : LatexWordScanner.scan(text, config, dict.language())) {
            if (!dict.isCorrect(w.text())) {
                out.add(new Misspelling(w.start(), w.end(), text.substring(w.start(), w.end()), w.text()));
            }
        }
        return out;
    }

    // --- right-click menu ---------------------------------------------------------------------

    /** Menu items for the word at {@code offset}, or an empty list if it is spelled correctly. */
    public List<JComponent> popupItems(int offset) {
        Misspelling m = at(offset);
        SpellDictionary dict = language == null ? null : Dictionaries.ifLoaded(language);
        if (m == null || dict == null) return List.of();
        List<JComponent> items = new ArrayList<>();
        List<String> suggestions = dict.suggest(m.word(), 7);
        if (suggestions.isEmpty()) {
            JMenuItem none = new JMenuItem("(no suggestions)");
            none.setEnabled(false);
            items.add(none);
        }
        for (String s : suggestions) {
            JMenuItem it = new JMenuItem(s);
            it.setFont(it.getFont().deriveFont(Font.BOLD));
            it.addActionListener(e -> replace(m, s));
            items.add(it);
        }
        items.add(new JPopupMenu.Separator());
        JMenuItem add = new JMenuItem("Add “" + m.word() + "” to Dictionary (" + dict.language() + ")");
        add.addActionListener(e -> addToDictionary(dict, m.word()));
        items.add(add);
        JMenuItem ignore = new JMenuItem("Ignore “" + m.word() + "” for Now");
        ignore.addActionListener(e -> {
            dict.ignore(m.word());
            onDictionaryChanged.run();
        });
        items.add(ignore);
        items.add(new JPopupMenu.Separator());
        return items;
    }

    /** The underlined word at {@code offset} (where it is now, after any edits), or null. */
    private Misspelling at(int offset) {
        for (Mark mk : marks) {
            int s = mk.highlight().getStartOffset();
            int e = mk.highlight().getEndOffset();
            if (offset < s || offset > e) continue;
            try {
                // still the same text?
                if (area.getText(s, e - s).equals(mk.m().source())) {
                    return new Misspelling(s, e, mk.m().source(), mk.m().word());
                }
            } catch (BadLocationException ignored) {
                return null;
            }
        }
        return null;
    }

    private void addToDictionary(SpellDictionary dict, String word) {
        try {
            dict.addWord(word);
        } catch (IOException e) {
            onProblem.accept("Could not write " + dict.userFile() + ": " + e.getMessage());
        }
        onDictionaryChanged.run();
    }

    private void replace(Misspelling m, String replacement) {
        replace(area, m.start(), m.end(), encodeLike(replacement, m.source()));
        recheck();
    }

    /** Replaces [start, end) as one undoable edit. */
    public static void replace(RSyntaxTextArea area, int start, int end, String text) {
        area.beginAtomicEdit();
        try {
            area.getDocument().remove(start, end - start);
            area.getDocument().insertString(start, text, null);
            area.setCaretPosition(start + text.length());
        } catch (BadLocationException ignored) {
            // the document changed meanwhile
        } finally {
            area.endAtomicEdit();
        }
    }

    // --- writing replacements in the source's style --------------------------------------------

    private static final Map<Character, Character> ACCENT_COMMANDS = Map.ofEntries(
            Map.entry('̀', '`'), Map.entry('́', '\''), Map.entry('̂', '^'),
            Map.entry('̃', '~'), Map.entry('̄', '='), Map.entry('̆', 'u'),
            Map.entry('̇', '.'), Map.entry('̈', '"'), Map.entry('̊', 'r'),
            Map.entry('̋', 'H'), Map.entry('̌', 'v'), Map.entry('̧', 'c'),
            Map.entry('̨', 'k'), Map.entry('̣', 'd'), Map.entry('̱', 'b'));

    private static final Map<Character, String> SPECIAL = Map.ofEntries(
            Map.entry('ß', "\\ss{}"), Map.entry('ø', "\\o{}"), Map.entry('Ø', "\\O{}"),
            Map.entry('æ', "\\ae{}"), Map.entry('Æ', "\\AE{}"), Map.entry('œ', "\\oe{}"),
            Map.entry('Œ', "\\OE{}"), Map.entry('å', "\\aa{}"), Map.entry('Å', "\\AA{}"),
            Map.entry('ł', "\\l{}"), Map.entry('Ł', "\\L{}"));

    /**
     * Writes a suggestion the way the original was written: with LaTeX accents if the
     * original used them (\"o), with babel shorthands if it used those ("o), else as is.
     */
    public static String encodeLike(String replacement, String source) {
        boolean latex = source.contains("\\");
        boolean babel = !latex && source.matches(".*\"[aouAOUsz].*");
        if (!latex && !babel) return replacement;
        StringBuilder sb = new StringBuilder();
        for (char c : replacement.toCharArray()) {
            if (c < 128) {
                sb.append(c);
                continue;
            }
            if (babel) {
                String b = switch (c) {
                    case 'ä' -> "\"a"; case 'ö' -> "\"o"; case 'ü' -> "\"u";
                    case 'Ä' -> "\"A"; case 'Ö' -> "\"O"; case 'Ü' -> "\"U"; case 'ß' -> "\"s";
                    default -> null;
                };
                sb.append(b != null ? b : String.valueOf(c));
                continue;
            }
            String special = SPECIAL.get(c);
            if (special != null) {
                sb.append(special);
                continue;
            }
            String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            Character cmd = d.length() == 2 ? ACCENT_COMMANDS.get(d.charAt(1)) : null;
            if (cmd == null) {
                sb.append(c);
            } else if (Character.isLetter(cmd)) {
                sb.append('\\').append(cmd).append('{').append(d.charAt(0)).append('}');
            } else {
                sb.append('\\').append(cmd).append(d.charAt(0));
            }
        }
        return sb.toString();
    }
}
