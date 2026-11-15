package atlantis.hypatia.macro;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Watches typed characters and runs a macro when its text trigger has just been typed
 * within the time window. Only typed characters count (not pasted text), any typed
 * character sequence can be a trigger; checking costs one comparison per text macro per
 * keystroke.
 */
public final class TextTriggers extends KeyAdapter {

    private static final int MAX = 64;

    private final RSyntaxTextArea area;
    private final Supplier<MacroFile> macros;
    private final BooleanSupplier enabled;
    private final IntSupplier windowMs;
    private final Consumer<String> onError;

    private final StringBuilder typed = new StringBuilder();
    private final long[] times = new long[MAX];

    private TextTriggers(RSyntaxTextArea area, Supplier<MacroFile> macros, BooleanSupplier enabled,
                         IntSupplier windowMs, Consumer<String> onError) {
        this.area = area;
        this.macros = macros;
        this.enabled = enabled;
        this.windowMs = windowMs;
        this.onError = onError;
    }

    public static void install(RSyntaxTextArea area, Supplier<MacroFile> macros, BooleanSupplier enabled,
                               IntSupplier windowMs, Consumer<String> onError) {
        area.addKeyListener(new TextTriggers(area, macros, enabled, windowMs, onError));
    }

    @Override
    public void keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        // (Characters typed together with Ctrl that insert nothing are caught by the check
        // against the document below; AltGr combinations such as \ on German keyboards count.)
        if (c == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(c) || !area.isEditable()) {
            typed.setLength(0); // Backspace, Enter, Tab, Ctrl+... break a sequence
            return;
        }
        if (typed.length() == MAX) {
            typed.deleteCharAt(0);
            System.arraycopy(times, 1, times, 0, MAX - 1);
        }
        times[typed.length()] = e.getWhen();
        typed.append(c);
        // The character is inserted after this listener runs; check afterwards.
        SwingUtilities.invokeLater(this::check);
    }

    private void check() {
        if (!enabled.getAsBoolean()) return;
        MacroFile mf = macros.get();
        if (mf == null) return;
        String buf = typed.toString();
        if (buf.isEmpty()) return; // Enter or Backspace came in before this check ran
        long last = times[buf.length() - 1];
        for (Macro m : mf.textMacros()) {
            String t = m.text();
            if (!buf.endsWith(t)) continue;
            if (last - times[buf.length() - t.length()] > windowMs.getAsInt()) continue;
            // The trigger must really be in front of the caret (no clicks or edits in between).
            int caret = area.getCaretPosition();
            try {
                if (area.getSelectionStart() != area.getSelectionEnd() || caret < t.length()
                        || !area.getText(caret - t.length(), t.length()).equals(t)) {
                    continue;
                }
            } catch (BadLocationException ex) {
                continue;
            }
            typed.setLength(0);
            try {
                MacroRunner.run(area, m, t.length());
            } catch (MacroRunner.MacroException ex) {
                onError.accept(ex.getMessage());
            }
            return;
        }
    }
}
