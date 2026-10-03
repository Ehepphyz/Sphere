package com.sphere.components.rootview;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * A ROOT canvas drawn in the theme's colours rather than on white paper.
 *
 * Every colour the painters ask for passes through here. What ROOT means as
 * paper and ink (white, black and the greys between: pad fills, frames,
 * axes, labels, a black histogram line) takes the theme's surface and text
 * colours, light and dark swapped; what carries data (a colour scale, a
 * coloured line, a fill) keeps its exact colour, so a COLZ map still reads on
 * its colour bar. Text is checked against the pixels it is about to cover:
 * dark text on the theme's dark paper is lightened, the same text on a light
 * pastel fill is left as it was, so a title stays readable wherever it lies.
 *
 * It draws into a picture it can read back, which is how the text knows its
 * ground.
 */
final class InkGraphics extends Graphics2D {

    private final Graphics2D g;
    private final BufferedImage target;
    private final Color paper;
    private final Color text;
    private Color asked = Color.BLACK;

    InkGraphics(Graphics2D g, BufferedImage target, Color paper, Color text) {
        this.g = g;
        this.target = target;
        this.paper = paper;
        this.text = text;
    }

    /* ------------------------------------------------------------------ */
    /* The colours                                                         */
    /* ------------------------------------------------------------------ */

    /** Paper and ink to the theme; a colour that carries data left alone. */
    Color ink(Color c) {
        if (c == null) return null;
        final int r = c.getRed();
        final int gr = c.getGreen();
        final int b = c.getBlue();
        final int max = Math.max(r, Math.max(gr, b));
        final int min = Math.min(r, Math.min(gr, b));
        final double saturation = max == 0 ? 0 : (max - min) / (double) max;
        if (max - min >= 30 && saturation >= 0.18) return c;
        // A grey: black becomes the text, white the paper, the greys between.
        final double t = (0.2126 * r + 0.7152 * gr + 0.0722 * b) / 255.0;
        return new Color(mix(text.getRed(), paper.getRed(), t), mix(text.getGreen(), paper.getGreen(), t),
            mix(text.getBlue(), paper.getBlue(), t), c.getAlpha());
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private static double luminance(int r, int g, int b) {
        return lin(r) * 0.2126 + lin(g) * 0.7152 + lin(b) * 0.0722;
    }

    private static double lin(int v) {
        final double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double contrast(double a, double b) {
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * The colour a string is drawn in: its own when it reads on the ground
     * under it (a contrast of 3:1), else moved toward white or black, its hue
     * kept, until it does.
     */
    private Color forText(Color c, Rectangle device) {
        if (c == null || target == null) return c;
        final Rectangle r = device.intersection(new Rectangle(0, 0, target.getWidth(), target.getHeight()));
        if (r.isEmpty()) return c;
        double sum = 0;
        int n = 0;
        final int step = Math.max(1, Math.max(r.width, r.height) / 24);
        for (int y = r.y; y < r.y + r.height; y += step) {
            for (int x = r.x; x < r.x + r.width; x += step) {
                final int p = target.getRGB(x, y);
                if ((p >>> 24) < 16) continue;
                sum += luminance(p >> 16 & 255, p >> 8 & 255, p & 255);
                n++;
            }
        }
        final double ground = n == 0 ? luminance(paper.getRed(), paper.getGreen(), paper.getBlue()) : sum / n;
        Color out = c;
        final Color toward = ground < 0.18 ? Color.WHITE : Color.BLACK;
        for (int k = 0; k < 8 && contrast(luminance(out.getRed(), out.getGreen(), out.getBlue()), ground) < 3; k++) {
            out = new Color(mix(out.getRed(), toward.getRed(), 0.3), mix(out.getGreen(), toward.getGreen(), 0.3),
                mix(out.getBlue(), toward.getBlue(), 0.3), c.getAlpha());
        }
        return out;
    }

    /** Where a string drawn at x, y will lie on the picture. */
    private Rectangle bounds(String s, float x, float y) {
        final FontMetrics fm = g.getFontMetrics();
        final Rectangle local = new Rectangle((int) x, (int) (y - fm.getAscent()), Math.max(1, fm.stringWidth(s)),
            Math.max(1, fm.getAscent() + fm.getDescent()));
        return g.getTransform().createTransformedShape(local).getBounds();
    }

    /* ------------------------------------------------------------------ */
    /* Colour state                                                        */
    /* ------------------------------------------------------------------ */

    @Override
    public void setColor(Color c) {
        asked = c;
        g.setColor(ink(c));
    }

    @Override
    public Color getColor() {
        return asked;
    }

    @Override
    public void setPaint(Paint paint) {
        if (paint instanceof Color c) setColor(c);
        else g.setPaint(paint);
    }

    @Override
    public Paint getPaint() {
        return g.getPaint() instanceof Color ? asked : g.getPaint();
    }

    @Override
    public void setBackground(Color color) {
        g.setBackground(ink(color));
    }

    @Override
    public Color getBackground() {
        return g.getBackground();
    }

    /* ------------------------------------------------------------------ */
    /* Text                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public void drawString(String str, int x, int y) {
        drawString(str, (float) x, (float) y);
    }

    @Override
    public void drawString(String str, float x, float y) {
        if (str == null || str.isEmpty()) return;
        final Color was = g.getColor();
        g.setColor(forText(was, bounds(str, x, y)));
        g.drawString(str, x, y);
        g.setColor(was);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, int x, int y) {
        g.drawString(iterator, x, y);
    }

    @Override
    public void drawString(AttributedCharacterIterator iterator, float x, float y) {
        g.drawString(iterator, x, y);
    }

    @Override
    public void drawGlyphVector(GlyphVector gv, float x, float y) {
        g.drawGlyphVector(gv, x, y);
    }

    /* ------------------------------------------------------------------ */
    /* Everything else, as it is                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public Graphics create() {
        return new InkGraphics((Graphics2D) g.create(), target, paper, text).withColor(asked);
    }

    private InkGraphics withColor(Color c) {
        asked = c;
        return this;
    }

    @Override
    public void draw(Shape s) {
        g.draw(s);
    }

    @Override
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) {
        return g.drawImage(img, xform, obs);
    }

    @Override
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {
        g.drawImage(img, op, x, y);
    }

    @Override
    public void drawRenderedImage(RenderedImage img, AffineTransform xform) {
        g.drawRenderedImage(img, xform);
    }

    @Override
    public void drawRenderableImage(RenderableImage img, AffineTransform xform) {
        g.drawRenderableImage(img, xform);
    }

    @Override
    public void fill(Shape s) {
        g.fill(s);
    }

    @Override
    public boolean hit(Rectangle rect, Shape s, boolean onStroke) {
        return g.hit(rect, s, onStroke);
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return g.getDeviceConfiguration();
    }

    @Override
    public void setComposite(Composite comp) {
        g.setComposite(comp);
    }

    @Override
    public void setStroke(Stroke s) {
        g.setStroke(s);
    }

    @Override
    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {
        g.setRenderingHint(hintKey, hintValue);
    }

    @Override
    public Object getRenderingHint(RenderingHints.Key hintKey) {
        return g.getRenderingHint(hintKey);
    }

    @Override
    public void setRenderingHints(Map<?, ?> hints) {
        g.setRenderingHints(hints);
    }

    @Override
    public void addRenderingHints(Map<?, ?> hints) {
        g.addRenderingHints(hints);
    }

    @Override
    public RenderingHints getRenderingHints() {
        return g.getRenderingHints();
    }

    @Override
    public void translate(int x, int y) {
        g.translate(x, y);
    }

    @Override
    public void translate(double tx, double ty) {
        g.translate(tx, ty);
    }

    @Override
    public void rotate(double theta) {
        g.rotate(theta);
    }

    @Override
    public void rotate(double theta, double x, double y) {
        g.rotate(theta, x, y);
    }

    @Override
    public void scale(double sx, double sy) {
        g.scale(sx, sy);
    }

    @Override
    public void shear(double shx, double shy) {
        g.shear(shx, shy);
    }

    @Override
    public void transform(AffineTransform tx) {
        g.transform(tx);
    }

    @Override
    public void setTransform(AffineTransform tx) {
        g.setTransform(tx);
    }

    @Override
    public AffineTransform getTransform() {
        return g.getTransform();
    }

    @Override
    public Composite getComposite() {
        return g.getComposite();
    }

    @Override
    public Stroke getStroke() {
        return g.getStroke();
    }

    @Override
    public void clip(Shape s) {
        g.clip(s);
    }

    @Override
    public FontRenderContext getFontRenderContext() {
        return g.getFontRenderContext();
    }

    @Override
    public void setPaintMode() {
        g.setPaintMode();
    }

    @Override
    public void setXORMode(Color c1) {
        g.setXORMode(ink(c1));
    }

    @Override
    public Font getFont() {
        return g.getFont();
    }

    @Override
    public void setFont(Font font) {
        g.setFont(font);
    }

    @Override
    public FontMetrics getFontMetrics(Font f) {
        return g.getFontMetrics(f);
    }

    @Override
    public Rectangle getClipBounds() {
        return g.getClipBounds();
    }

    @Override
    public void clipRect(int x, int y, int width, int height) {
        g.clipRect(x, y, width, height);
    }

    @Override
    public void setClip(int x, int y, int width, int height) {
        g.setClip(x, y, width, height);
    }

    @Override
    public Shape getClip() {
        return g.getClip();
    }

    @Override
    public void setClip(Shape clip) {
        g.setClip(clip);
    }

    @Override
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {
        g.copyArea(x, y, width, height, dx, dy);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
        g.drawLine(x1, y1, x2, y2);
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
        g.fillRect(x, y, width, height);
    }

    @Override
    public void clearRect(int x, int y, int width, int height) {
        g.clearRect(x, y, width, height);
    }

    @Override
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        g.drawRoundRect(x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        g.fillRoundRect(x, y, width, height, arcWidth, arcHeight);
    }

    @Override
    public void drawOval(int x, int y, int width, int height) {
        g.drawOval(x, y, width, height);
    }

    @Override
    public void fillOval(int x, int y, int width, int height) {
        g.fillOval(x, y, width, height);
    }

    @Override
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        g.drawArc(x, y, width, height, startAngle, arcAngle);
    }

    @Override
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        g.fillArc(x, y, width, height, startAngle, arcAngle);
    }

    @Override
    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
        g.drawPolyline(xPoints, yPoints, nPoints);
    }

    @Override
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        g.drawPolygon(xPoints, yPoints, nPoints);
    }

    @Override
    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        g.fillPolygon(xPoints, yPoints, nPoints);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, ImageObserver observer) {
        return g.drawImage(img, x, y, observer);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) {
        return g.drawImage(img, x, y, width, height, observer);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) {
        return g.drawImage(img, x, y, ink(bgcolor), observer);
    }

    @Override
    public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver observer) {
        return g.drawImage(img, x, y, width, height, ink(bgcolor), observer);
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2,
                             ImageObserver observer) {
        return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
    }

    @Override
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2,
                             Color bgcolor, ImageObserver observer) {
        return g.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, ink(bgcolor), observer);
    }

    @Override
    public void dispose() {
        g.dispose();
    }
}
