package atlantis.hypatia.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;

/** Small line icons drawn with Java2D, so they stay crisp at any scale and follow the theme. */
public final class Icons {

    private Icons() {}

    public enum Glyph { NEW, OPEN, SAVE, BUILD, STOP, VIEW, FIND, SETTINGS, LOG, RECORD, WRAP, CITE, LABEL, OUTLINE }

    public static Icon of(Glyph glyph) {
        return new GlyphIcon(glyph, 18);
    }

    /** Application icon in several sizes (for the window and task bar). */
    public static List<Image> appImages() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            Image img = appImage(s);
            if (img != null) list.add(img);
        }
        return list;
    }

    public static Image appImage(int size) {
        try (var in = Icons.class.getResourceAsStream("/atlantis/hypatia/icons/hypatia-" + size + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static Icon appIcon(int size) {
        Image img = appImage(size);
        return img == null ? null : new ImageIcon(img);
    }

    private record GlyphIcon(Glyph glyph, int size) implements Icon {

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            g.scale(size / 18.0, size / 18.0);
            Color fg = (c != null && !c.isEnabled())
                    ? UIManager.getColor("Label.disabledForeground")
                    : UIManager.getColor("Label.foreground");
            g.setColor(fg);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (glyph) {
                case NEW -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(4, 2); p.lineTo(11, 2); p.lineTo(14, 5); p.lineTo(14, 16); p.lineTo(4, 16); p.closePath();
                    g.draw(p);
                    g.draw(new Line2D.Double(9, 7.5, 9, 12.5));
                    g.draw(new Line2D.Double(6.5, 10, 11.5, 10));
                }
                case OPEN -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(2, 14.5); p.lineTo(2, 4); p.lineTo(7, 4); p.lineTo(8.5, 5.5); p.lineTo(15, 5.5); p.lineTo(15, 8);
                    g.draw(p);
                    Path2D q = new Path2D.Double();
                    q.moveTo(2, 14.5); q.lineTo(4.5, 8); q.lineTo(16.5, 8); q.lineTo(14, 14.5); q.closePath();
                    g.draw(q);
                }
                case SAVE -> {
                    g.draw(new RoundRectangle2D.Double(2.5, 2.5, 13, 13, 3, 3));
                    g.draw(new Line2D.Double(9, 5, 9, 11));
                    Path2D p = new Path2D.Double();
                    p.moveTo(6.5, 8.5); p.lineTo(9, 11); p.lineTo(11.5, 8.5);
                    g.draw(p);
                    g.draw(new Line2D.Double(5.5, 13, 12.5, 13));
                }
                case BUILD -> {
                    g.setColor(Theme.success());
                    Path2D p = new Path2D.Double();
                    p.moveTo(5, 3); p.lineTo(15, 9); p.lineTo(5, 15); p.closePath();
                    if (c == null || c.isEnabled()) g.fill(p); else g.draw(p);
                }
                case STOP -> {
                    g.setColor(Theme.error());
                    g.fill(new RoundRectangle2D.Double(4, 4, 10, 10, 3, 3));
                }
                case VIEW -> {
                    Path2D p = new Path2D.Double();
                    p.moveTo(1.5, 9); p.quadTo(9, 1, 16.5, 9); p.quadTo(9, 17, 1.5, 9); p.closePath();
                    g.draw(p);
                    g.setColor(Theme.gold());
                    g.fill(new Ellipse2D.Double(6.5, 6.5, 5, 5));
                }
                case FIND -> {
                    g.draw(new Ellipse2D.Double(3, 3, 9, 9));
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(11, 11, 15.5, 15.5));
                }
                case SETTINGS -> {
                    g.draw(new Ellipse2D.Double(6.5, 6.5, 5, 5));
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        g.draw(new Line2D.Double(9 + 4.6 * Math.cos(a), 9 + 4.6 * Math.sin(a),
                                9 + 7 * Math.cos(a), 9 + 7 * Math.sin(a)));
                    }
                    g.draw(new Arc2D.Double(4.4, 4.4, 9.2, 9.2, 0, 360, Arc2D.OPEN));
                }
                case LOG -> {
                    g.draw(new RoundRectangle2D.Double(2, 3, 14, 12, 3, 3));
                    Path2D p = new Path2D.Double();
                    p.moveTo(5, 7); p.lineTo(7.5, 9); p.lineTo(5, 11);
                    g.draw(p);
                    g.draw(new Line2D.Double(9, 11.5, 13, 11.5));
                }
                case WRAP -> {
                    g.draw(new Line2D.Double(2.5, 4, 15.5, 4));
                    Path2D p = new Path2D.Double();
                    p.moveTo(2.5, 9); p.lineTo(13, 9); p.quadTo(15.5, 9, 15.5, 11.5);
                    p.quadTo(15.5, 14, 13, 14); p.lineTo(9, 14);
                    g.draw(p);
                    Path2D a = new Path2D.Double();
                    a.moveTo(10.5, 12.2); a.lineTo(8.7, 14); a.lineTo(10.5, 15.8);
                    g.draw(a);
                    g.draw(new Line2D.Double(2.5, 14, 6, 14));
                }
                case OUTLINE -> {
                    // a sidebar with a tree
                    g.draw(new RoundRectangle2D.Double(2, 2.5, 14, 13, 3, 3));
                    g.draw(new Line2D.Double(7, 2.5, 7, 15.5));
                    g.draw(new Line2D.Double(9.5, 6, 14, 6));
                    g.draw(new Line2D.Double(11, 9, 14, 9));
                    g.draw(new Line2D.Double(11, 12, 14, 12));
                }
                                case CITE -> {
                    // an open book
                    Path2D l = new Path2D.Double();
                    l.moveTo(9, 5); l.quadTo(6, 3, 2, 3.5); l.lineTo(2, 14); l.quadTo(6, 13.5, 9, 15.5);
                    l.quadTo(12, 13.5, 16, 14); l.lineTo(16, 3.5); l.quadTo(12, 3, 9, 5); l.lineTo(9, 15.5);
                    g.draw(l);
                }
                case LABEL -> {
                    // a tag
                    Path2D t = new Path2D.Double();
                    t.moveTo(2.5, 4); t.lineTo(10, 4); t.lineTo(15.5, 9); t.lineTo(10, 14); t.lineTo(2.5, 14); t.closePath();
                    g.draw(t);
                    g.setColor(Theme.gold());
                    g.fill(new Ellipse2D.Double(9, 7.6, 2.8, 2.8));
                }
                case RECORD -> {
                    g.setColor(Theme.error());
                    g.fill(new Ellipse2D.Double(4.5, 4.5, 9, 9));
                }
            }
            g.dispose();
        }
    }
}
