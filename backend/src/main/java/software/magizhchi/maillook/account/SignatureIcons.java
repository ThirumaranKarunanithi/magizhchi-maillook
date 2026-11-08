package software.magizhchi.maillook.account;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The small round icons under a royal signature (WhatsApp, LinkedIn, Instagram, YouTube, Facebook, X) and
 * the calendar on its booking button. Drawn from plain shapes at 3x (no fonts, so they look the same on
 * any server), in the template's colour, and kept once drawn.
 */
final class SignatureIcons {

    static final Set<String> NAMES = Set.of("whatsapp", "linkedin", "instagram", "youtube", "facebook", "x", "calendar");
    private static final int S = 66; // 22px shown, 3x
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private SignatureIcons() {}

    /** PNG bytes, or null for an unknown icon or colour. {@code hex}: six hex digits, no '#'. */
    static byte[] png(String name, String hex) {
        if (!NAMES.contains(name) || hex == null || !hex.matches("[0-9a-fA-F]{6}")) return null;
        return CACHE.computeIfAbsent(name + "/" + hex.toLowerCase(), k -> draw(name, Color.decode("#" + hex)));
    }

    private static byte[] draw(String name, Color accent) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        boolean bare = "calendar".equals(name); // the button's icon sits on the button's own colour
        Color ink = Color.WHITE;
        if (!bare) {
            // A coin: the accent with a light top and a darker rim.
            g.setPaint(new java.awt.GradientPaint(0, 0, lighter(accent, 0.28f), 0, S, darker(accent, 0.18f)));
            g.fill(new Ellipse2D.Double(1, 1, S - 2, S - 2));
            g.setColor(new Color(255, 255, 255, 70));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(2.5, 2.5, S - 5, S - 5));
        } else {
            ink = accent;
        }
        g.setColor(ink);
        double c = S / 2.0;
        switch (name) {
            case "linkedin" -> {
                g.fill(new Ellipse2D.Double(19, 17, 7, 7));
                g.fill(new RoundRectangle2D.Double(19.5, 27, 6, 20, 2, 2));
                g.fill(new RoundRectangle2D.Double(29.5, 27, 6, 20, 2, 2));
                g.setStroke(new BasicStroke(6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(32.5, 27.5, 12, 12, 0, 180, Arc2D.OPEN));
                g.fill(new RoundRectangle2D.Double(41.5, 33, 6, 14, 2, 2));
            }
            case "instagram" -> {
                g.setStroke(new BasicStroke(4f));
                g.draw(new RoundRectangle2D.Double(17, 17, 32, 32, 12, 12));
                g.draw(new Ellipse2D.Double(c - 7.5, c - 7.5, 15, 15));
                g.fill(new Ellipse2D.Double(39, 21, 5, 5));
            }
            case "youtube" -> {
                g.fill(new RoundRectangle2D.Double(14, 21, 38, 25, 12, 12));
                g.setColor(accent);
                Path2D t = new Path2D.Double();
                t.moveTo(29, 27); t.lineTo(29, 40); t.lineTo(40, 33.5); t.closePath();
                g.fill(t);
            }
            case "facebook" -> {
                g.fill(new RoundRectangle2D.Double(30, 26, 7, 24, 2, 2));
                g.setStroke(new BasicStroke(7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(33.5, 18.5, 12, 14, 90, 90, Arc2D.OPEN));
                g.fill(new RoundRectangle2D.Double(38.5, 15, 5, 7, 2, 2));
                g.fill(new RoundRectangle2D.Double(25, 30, 18, 5.5, 2, 2));
            }
            case "x" -> {
                g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(22, 20, 44, 46));
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(44, 20, 22, 46));
            }
            case "whatsapp" -> {
                g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(16, 16, 34, 34, -110, 330, Arc2D.OPEN));
                Path2D tail = new Path2D.Double();
                tail.moveTo(21.5, 41); tail.lineTo(17, 50); tail.lineTo(27, 46.5);
                g.draw(tail);
                // the handset inside the bubble
                g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(25, 24, 17, 17, 190, 110, Arc2D.OPEN));
                g.fill(new Ellipse2D.Double(24, 25, 6.5, 6.5));
                g.fill(new Ellipse2D.Double(36, 37, 6.5, 6.5));
            }
            case "calendar" -> {
                g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new RoundRectangle2D.Double(9, 14, 48, 44, 8, 8));
                g.fill(new RoundRectangle2D.Double(9, 14, 48, 13, 8, 8));
                g.draw(new Line2D.Double(21, 7, 21, 17));
                g.draw(new Line2D.Double(45, 7, 45, 17));
                for (int r = 0; r < 2; r++) {
                    for (int col = 0; col < 3; col++) g.fill(new Ellipse2D.Double(18 + col * 12, 33 + r * 11, 6, 6));
                }
            }
            default -> { }
        }
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Color lighter(Color c, float k) {
        return new Color(Math.min(255, Math.round(c.getRed() + (255 - c.getRed()) * k)),
                Math.min(255, Math.round(c.getGreen() + (255 - c.getGreen()) * k)),
                Math.min(255, Math.round(c.getBlue() + (255 - c.getBlue()) * k)));
    }

    private static Color darker(Color c, float k) {
        return new Color(Math.round(c.getRed() * (1 - k)), Math.round(c.getGreen() * (1 - k)), Math.round(c.getBlue() * (1 - k)));
    }
}
