package atlantis.hypatia.ui;

import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * A JSplitPane without its built-in keyboard bindings: Swing binds F6 and F8 on
 * split panes, which would swallow Hypatia's "View PDF" (F6) and "Log" (F8) keys.
 */
public class QuietSplitPane extends JSplitPane {

    public QuietSplitPane(int orientation, Component a, Component b) {
        super(orientation, a, b);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        SwingUtilities.replaceUIInputMap(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, null);
        SwingUtilities.replaceUIInputMap(this, JComponent.WHEN_FOCUSED, null);
    }
}
