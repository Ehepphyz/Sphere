package com.sphere.components.spherebrowser;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * A picture looked at closely: zoom on the point under the mouse up to the
 * single pixel, with its grid and its colour; turned to any angle; mirrored;
 * a loupe that follows the mouse; in a dark theme, the picture re-inked on
 * the theme's paper (white paper the theme's surface, black ink its text,
 * hues kept), and back to its own colours with a click
 * read on a dark screen. A vector picture (SVG) is drawn again at the scale
 * it is looked at, so it stays sharp however close.
 *
 * Wheel zooms, drag slides, Ctrl-drag turns, double click fits; keys: + - 0
 * (1:1) F (fit) R / Shift-R (turn 90°) H V (mirror) L (loupe) I (theme paper).
 */
public final class PictureView extends JPanel {

    private BufferedImage image;
    private final DoubleFunction<BufferedImage> vector;
    private BufferedImage sharp;
    private double sharpScale;
    private double zoom = 1;
    private double angle;
    private double cx;
    private double cy;
    private boolean flipH;
    private boolean flipV;
    private boolean loupe;
    private boolean invert = ImagingTheme.isDark();
    private boolean grid = true;
    private Point mouse;
    private final Canvas canvas = new Canvas();
    private final JLabel status = new JLabel(" ");
    private final Consumer<String> report;
    private final JSlider rotation = new JSlider(-180, 180, 0);
    private boolean fitted;

    /**
     * @param image  the picture
     * @param vector when not null, draws the picture again at a scale (an SVG)
     */
    public PictureView(BufferedImage image, DoubleFunction<BufferedImage> vector, Consumer<String> report) {
        super(new BorderLayout());
        this.image = image;
        this.vector = vector;
        this.report = report == null ? s -> { } : report;
        cx = image.getWidth() / 2.0;
        cy = image.getHeight() / 2.0;
        setBackground(ImagingTheme.panel());

        final JPanel bar = ImagingTheme.panelOf(new FlowLayout(FlowLayout.LEFT, 4, 3));
        bar.add(button("Fit", "Fit the window (F, double-click)", this::fit));
        bar.add(button("1:1", "One picture pixel per screen pixel (0)", () -> zoomTo(1)));
        bar.add(button("+", "Closer (+, wheel)", () -> zoomAt(1.25, null)));
        bar.add(button("−", "Further (-, wheel)", () -> zoomAt(0.8, null)));
        bar.add(button("⟲ 90°", "Turn left (Shift+R)", () -> turn(-90)));
        bar.add(button("⟳ 90°", "Turn right (R)", () -> turn(90)));
        bar.add(button("⇋", "Mirror left-right (H)", () -> {
            flipH = !flipH;
            canvas.repaint();
        }));
        bar.add(button("⇵", "Mirror up-down (V)", () -> {
            flipV = !flipV;
            canvas.repaint();
        }));
        final JToggleButton lp = toggle("Loupe", "A magnifier that follows the mouse (L)", on -> loupe = on);
        final JToggleButton inv = toggle("Theme paper",
            "The picture on the theme's paper: white paper becomes the theme's ground, black ink its text, hues kept (I)",
            on -> invert = on);
        inv.setSelected(invert);
        final JToggleButton gr = toggle("Pixel grid", "The grid of pixels when close enough", on -> grid = on);
        gr.setSelected(true);
        bar.add(lp);
        bar.add(inv);
        bar.add(gr);
        final JLabel rl = new JLabel("  angle");
        rl.setForeground(ImagingTheme.subduedText());
        bar.add(rl);
        rotation.setOpaque(false);
        rotation.setPreferredSize(new java.awt.Dimension(160, 22));
        rotation.addChangeListener(e -> {
            angle = rotation.getValue();
            canvas.repaint();
        });
        bar.add(rotation);

        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 11.5f));
        status.setForeground(ImagingTheme.subduedText());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(bar, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    public BufferedImage image() {
        return image;
    }

    private JButton button(String label, String tip, Runnable run) {
        final JButton b = ImagingTheme.textButton(label, tip);
        b.addActionListener(e -> {
            run.run();
            canvas.requestFocusInWindow();
        });
        return b;
    }

    private JToggleButton toggle(String label, String tip, Consumer<Boolean> set) {
        final JToggleButton b = new JToggleButton(label);
        ImagingTheme.styleButton(b);
        b.setToolTipText(tip);
        b.addActionListener(e -> {
            set.accept(b.isSelected());
            canvas.repaint();
            canvas.requestFocusInWindow();
        });
        return b;
    }

    /* ------------------------------------------------------------------ */
    /* The view                                                            */
    /* ------------------------------------------------------------------ */

    private void fit() {
        final int w = Math.max(1, canvas.getWidth() - 16);
        final int h = Math.max(1, canvas.getHeight() - 16);
        final double a = Math.toRadians(angle);
        final double iw = Math.abs(image.getWidth() * Math.cos(a)) + Math.abs(image.getHeight() * Math.sin(a));
        final double ih = Math.abs(image.getWidth() * Math.sin(a)) + Math.abs(image.getHeight() * Math.cos(a));
        zoom = Math.min(w / iw, h / ih);
        cx = image.getWidth() / 2.0;
        cy = image.getHeight() / 2.0;
        canvas.repaint();
    }

    private void zoomTo(double z) {
        zoom = z;
        canvas.repaint();
    }

    /** Zooms keeping the picture point under the mouse where it is. */
    private void zoomAt(double factor, Point at) {
        final double nz = Math.max(0.02, Math.min(128, zoom * factor));
        if (at != null) {
            final Point2D before = toImage(at);
            zoom = nz;
            final Point2D after = toImage(at);
            if (before != null && after != null) {
                cx += before.getX() - after.getX();
                cy += before.getY() - after.getY();
            }
        } else {
            zoom = nz;
        }
        canvas.repaint();
    }

    private void turn(double degrees) {
        double a = angle + degrees;
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        rotation.setValue((int) Math.round(a));
    }

    private AffineTransform transform() {
        final AffineTransform t = new AffineTransform();
        t.translate(canvas.getWidth() / 2.0, canvas.getHeight() / 2.0);
        t.rotate(Math.toRadians(angle));
        t.scale(zoom * (flipH ? -1 : 1), zoom * (flipV ? -1 : 1));
        t.translate(-cx, -cy);
        return t;
    }

    private Point2D toImage(Point p) {
        try {
            return transform().inverseTransform(p, null);
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }

    private BufferedImage shown() {
        // A vector picture, drawn again at the scale it is seen, at most 8000 pixels wide.
        if (vector != null && zoom > 1.05) {
            final double want = Math.min(zoom, 8000.0 / Math.max(1, image.getWidth()));
            if (sharp == null || Math.abs(sharpScale - want) / want > 0.25) {
                try {
                    sharp = vector.apply(want);
                    sharpScale = want;
                } catch (RuntimeException e) {
                    sharp = null;
                }
            }
            if (sharp != null) return sharp;
        }
        return image;
    }

    private final class Canvas extends JComponent {
        private Point pressed;
        private double cx0;
        private double cy0;
        private double angle0;

        Canvas() {
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            final MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    pressed = e.getPoint();
                    cx0 = cx;
                    cy0 = cy;
                    angle0 = angle;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressed == null) return;
                    if (e.isControlDown() || SwingUtilities.isRightMouseButton(e)) {
                        final double a0 = Math.atan2(pressed.y - getHeight() / 2.0, pressed.x - getWidth() / 2.0);
                        final double a1 = Math.atan2(e.getY() - getHeight() / 2.0, e.getX() - getWidth() / 2.0);
                        double a = angle0 + Math.toDegrees(a1 - a0);
                        while (a > 180) a -= 360;
                        while (a < -180) a += 360;
                        rotation.setValue((int) Math.round(a));
                    } else {
                        final double dx = e.getX() - pressed.x;
                        final double dy = e.getY() - pressed.y;
                        final double a = Math.toRadians(-angle);
                        final double ux = (dx * Math.cos(a) - dy * Math.sin(a)) / zoom * (flipH ? -1 : 1);
                        final double uy = (dx * Math.sin(a) + dy * Math.cos(a)) / zoom * (flipV ? -1 : 1);
                        cx = cx0 - ux;
                        cy = cy0 - uy;
                    }
                    mouse = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    mouse = e.getPoint();
                    probe();
                    if (loupe) repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    mouse = null;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) fit();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoomAt(Math.pow(1.15, -e.getPreciseWheelRotation()), e.getPoint());
                    probe();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS -> zoomAt(1.25, mouse);
                        case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoomAt(0.8, mouse);
                        case KeyEvent.VK_0 -> zoomTo(1);
                        case KeyEvent.VK_F -> fit();
                        case KeyEvent.VK_R -> turn(e.isShiftDown() ? -90 : 90);
                        case KeyEvent.VK_H -> flipH = !flipH;
                        case KeyEvent.VK_V -> flipV = !flipV;
                        case KeyEvent.VK_L -> loupe = !loupe;
                        case KeyEvent.VK_I -> invert = !invert;
                        default -> {
                            return;
                        }
                    }
                    repaint();
                }
            });
        }

        private void probe() {
            if (mouse == null) return;
            final Point2D p = toImage(mouse);
            if (p == null) return;
            final int x = (int) Math.floor(p.getX());
            final int y = (int) Math.floor(p.getY());
            String s = String.format(Locale.ROOT, "%d x %d   zoom %.0f%%   angle %.0f°", image.getWidth(),
                image.getHeight(), zoom * 100, angle);
            if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
                final int c = image.getRGB(x, y);
                s += String.format(Locale.ROOT, "   pixel (%d, %d)  #%06X  rgb(%d, %d, %d)  α %d", x, y, c & 0xFFFFFF,
                    c >> 16 & 255, c >> 8 & 255, c & 255, c >>> 24);
            }
            status.setText(s);
            report.accept(s);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!fitted && getWidth() > 32) {
                fitted = true;
                fit();
            }
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(ImagingTheme.canvasGround());
                g.fillRect(0, 0, getWidth(), getHeight());
                final BufferedImage src = shown();
                final double k = src == image ? 1 : src.getWidth() / (double) image.getWidth();
                final AffineTransform t = transform();
                if (k != 1) t.scale(1 / k, 1 / k);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, zoom >= 3
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                // A checkerboard under transparent pixels.
                final AffineTransform saved = g.getTransform();
                g.transform(transform());
                checker(g, image.getWidth(), image.getHeight());
                g.setTransform(saved);
                g.drawImage(invert ? inverted(src) : src, t, null);
                if (grid && zoom >= 10) pixelGrid(g);
                if (loupe && mouse != null) loupe(g, src, k);
            } finally {
                g.dispose();
            }
        }

        private void checker(Graphics2D g, int w, int h) {
            final int s = Math.max(4, (int) (12 / Math.max(zoom, 0.01)));
            g.setColor(new Color(0xFFFFFF));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0xE6E6E6));
            if (zoom < 0.05) return;
            for (int y = 0; y < h; y += s) {
                for (int x = ((y / s) % 2) * s; x < w; x += 2 * s) g.fillRect(x, y, Math.min(s, w - x), Math.min(s, h - y));
            }
        }

        private void pixelGrid(Graphics2D g) {
            final Point2D a = toImage(new Point(0, 0));
            final Point2D b = toImage(new Point(getWidth(), getHeight()));
            final Point2D c = toImage(new Point(getWidth(), 0));
            final Point2D d = toImage(new Point(0, getHeight()));
            if (a == null || b == null || c == null || d == null) return;
            final int x0 = Math.max(0, (int) Math.floor(Math.min(Math.min(a.getX(), b.getX()), Math.min(c.getX(), d.getX()))));
            final int x1 = Math.min(image.getWidth(), (int) Math.ceil(Math.max(Math.max(a.getX(), b.getX()), Math.max(c.getX(), d.getX()))));
            final int y0 = Math.max(0, (int) Math.floor(Math.min(Math.min(a.getY(), b.getY()), Math.min(c.getY(), d.getY()))));
            final int y1 = Math.min(image.getHeight(), (int) Math.ceil(Math.max(Math.max(a.getY(), b.getY()), Math.max(c.getY(), d.getY()))));
            final AffineTransform saved = g.getTransform();
            g.transform(transform());
            g.setStroke(new BasicStroke((float) (1 / zoom)));
            g.setColor(new Color(128, 128, 128, 90));
            for (int x = x0; x <= x1; x++) g.drawLine(x, y0, x, y1);
            for (int y = y0; y <= y1; y++) g.drawLine(x0, y, x1, y);
            if (zoom >= 40) {
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont((float) (9 / zoom)));
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        final int col = image.getRGB(x, y);
                        final double lum = 0.3 * (col >> 16 & 255) + 0.59 * (col >> 8 & 255) + 0.11 * (col & 255);
                        g.setColor(lum > 128 ? Color.BLACK : Color.WHITE);
                        g.drawString(String.format("%06X", col & 0xFFFFFF), (float) (x + 0.08), (float) (y + 0.55));
                    }
                }
            }
            g.setTransform(saved);
        }

        private void loupe(Graphics2D g, BufferedImage src, double k) {
            final int r = 90;
            final double lz = Math.max(zoom * 4, 4);
            final Point2D p = toImage(mouse);
            if (p == null) return;
            final java.awt.Shape clip = new java.awt.geom.Ellipse2D.Double(mouse.x - r, mouse.y - r, 2 * r, 2 * r);
            final Graphics2D lg = (Graphics2D) g.create();
            try {
                lg.setClip(clip);
                lg.setColor(Color.WHITE);
                lg.fill(clip);
                final AffineTransform t = new AffineTransform();
                t.translate(mouse.x, mouse.y);
                t.rotate(Math.toRadians(angle));
                t.scale(lz * (flipH ? -1 : 1), lz * (flipV ? -1 : 1));
                t.translate(-p.getX(), -p.getY());
                if (k != 1) t.scale(1 / k, 1 / k);
                lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                lg.drawImage(invert ? inverted(src) : src, t, null);
            } finally {
                lg.dispose();
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ImagingTheme.accent());
            g.setStroke(new BasicStroke(2.5f));
            g.draw(clip);
            g.drawLine(mouse.x - 6, mouse.y, mouse.x + 6, mouse.y);
            g.drawLine(mouse.x, mouse.y - 6, mouse.x, mouse.y + 6);
        }
    }

    private BufferedImage invertedOf;
    private BufferedImage invertedSource;

    /** The picture re-inked on the theme's paper, made once per picture. */
    private BufferedImage inverted(BufferedImage src) {
        if (invertedSource == src && invertedOf != null) return invertedOf;
        invertedSource = src;
        invertedOf = ImagingTheme.onThemePaper(src);
        return invertedOf;
    }
}
