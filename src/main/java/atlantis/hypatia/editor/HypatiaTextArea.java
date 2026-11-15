package atlantis.hypatia.editor;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntFunction;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.text.Element;
import javax.swing.text.View;
import org.fife.ui.rsyntaxtextarea.IndentedWrappedSyntaxView;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaUI;
import org.fife.ui.rtextarea.RTextAreaUI;

/**
 * RSyntaxTextArea whose soft-wrapped lines continue at the line's own indentation, and
 * whose right-click menu can be extended (e.g. with spelling suggestions for the clicked word).
 */
public class HypatiaTextArea extends RSyntaxTextArea {

    private static final String EXTRA = "hypatia.popupExtra";

    private Point lastMouse;
    private IntFunction<List<JComponent>> popupExtras;
    private final List<IntFunction<String>> toolTipExtras = new java.util.ArrayList<>();
    private Runnable shiftClick;

    public HypatiaTextArea(int rows, int cols) {
        super(rows, cols);
        FastFoldManager.install(this);
    }

    @Override
    public void foldToggled(org.fife.ui.rsyntaxtextarea.folding.Fold fold) {
        if (getFoldManager() instanceof FastFoldManager f) f.invalidate();
        super.foldToggled(fold);
    }

    @Override
    protected RTextAreaUI createRTextAreaUI() {
        return new RSyntaxTextAreaUI(this) {
            @Override
            public View create(Element elem) {
                return getLineWrap() ? new IndentedWrappedSyntaxView(elem) : super.create(elem);
            }

            /*
             * As BasicTextUI.getPreferredSize, but laid out at the width used for painting.
             * BasicTextUI subtracts the caret width here, so with line wrap the root view's width
             * flipped between two values on every keystroke and all lines of the document were
             * re-wrapped twice (very slow in long documents).
             */
            @Override
            public java.awt.Dimension getPreferredSize(JComponent c) {
                if (!getLineWrap()) return super.getPreferredSize(c);
                View root = getRootView(HypatiaTextArea.this);
                java.awt.Insets i = c.getInsets();
                java.awt.Dimension d = c.getSize();
                javax.swing.text.Document doc = getDocument();
                if (doc instanceof javax.swing.text.AbstractDocument ad) ad.readLock();
                try {
                    if (d.width > i.left + i.right && d.height > i.top + i.bottom) {
                        root.setSize(d.width - i.left - i.right, d.height - i.top - i.bottom);
                    } else if (d.width == 0 && d.height == 0) {
                        root.setSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
                    }
                    d.width = (int) Math.min((long) root.getPreferredSpan(View.X_AXIS) + i.left + i.right,
                            Integer.MAX_VALUE);
                    d.height = (int) Math.min((long) root.getPreferredSpan(View.Y_AXIS) + i.top + i.bottom,
                            Integer.MAX_VALUE);
                } finally {
                    if (doc instanceof javax.swing.text.AbstractDocument ad) ad.readUnlock();
                }
                return d;
            }
        };
    }

    /** Items to put at the top of the right-click menu, for the document offset clicked. */
    public void setPopupExtras(IntFunction<List<JComponent>> extras) {
        this.popupExtras = extras;
    }

    /** Run on Shift+click (instead of extending the selection), after moving the caret there. */
    public void setShiftClickAction(Runnable r) {
        this.shiftClick = r;
    }

    /** Adds a tooltip source for the document offset under the mouse (e.g. a style problem); null: nothing. */
    public void addToolTipExtra(IntFunction<String> extra) {
        toolTipExtras.add(extra);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (!toolTipExtras.isEmpty()) {
            int offset = viewToModel2D(e.getPoint());
            if (offset >= 0) {
                for (IntFunction<String> f : toolTipExtras) {
                    String t = f.apply(offset);
                    if (t != null) return t;
                }
            }
        }
        return super.getToolTipText(e);
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_PRESSED || e.getID() == MouseEvent.MOUSE_RELEASED) {
            lastMouse = e.getPoint(); // before the menu is configured and shown
        }
        int mods = e.getModifiersEx() & (MouseEvent.SHIFT_DOWN_MASK | MouseEvent.CTRL_DOWN_MASK
                | MouseEvent.ALT_DOWN_MASK | MouseEvent.META_DOWN_MASK);
        if (shiftClick != null && e.getButton() == MouseEvent.BUTTON1 && mods == MouseEvent.SHIFT_DOWN_MASK) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                int offset = viewToModel2D(e.getPoint());
                requestFocusInWindow();
                if (offset >= 0) setCaretPosition(offset);
                shiftClick.run();
            }
            e.consume(); // no selection by Shift+click
            return;
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void configurePopupMenu(JPopupMenu menu) {
        super.configurePopupMenu(menu);
        for (Component c : menu.getComponents()) {
            if (c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(EXTRA))) menu.remove(c);
        }
        if (popupExtras == null || lastMouse == null) return;
        int offset = viewToModel2D(lastMouse);
        if (offset < 0) return;
        List<JComponent> extras = popupExtras.apply(offset);
        for (int i = 0; i < extras.size(); i++) {
            JComponent c = extras.get(i);
            c.putClientProperty(EXTRA, Boolean.TRUE);
            menu.insert(c, i);
        }
    }
}
