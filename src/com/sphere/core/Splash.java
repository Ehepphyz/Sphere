package com.sphere.core;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * The picture the user sees while Sphere is being built.
 *
 * With SplashScreen-Image in the jar manifest the JVM shows it before the first
 * class of Sphere is loaded, which is the earliest anything can appear. Without
 * it the same picture is shown in a window of our own, which costs the time the
 * toolkit needs to start and still comes well before the interface. Sphere then
 * writes on it what the launch is doing.
 */
public final class Splash {

    /**
     * The images the jar carries, used when the JVM shows none. The doubled one
     * comes first: drawn smaller it stays sharp, while the other one enlarged on
     * a dense screen turns to mush.
     */
    private static final String[] IMAGES = {
        "/com/sphere/icons/splash@2x.png",
        "/com/sphere/icons/splash.png"
    };

    /** Height of the band the message is written in. */
    private static final int STRIP = 32;

    private static final Color INK = new Color(0xE6, 0xE6, 0xE6);
    private static final Color SHADE = new Color(0x14, 0x16, 0x1A);

    private static SplashScreen screen;
    private static Graphics2D canvas;
    private static Rectangle area;

    /** Used only when the jar manifest declares no splash. */
    private static Pane own;

    private Splash() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Takes hold of the image the JVM shows, or shows one. Silent with no screen. */
    public static void begin() {
        try {
            screen = SplashScreen.getSplashScreen();
            if (screen == null) {
                showOwnWindow();
                return;
            }
            area = screen.getBounds();
            canvas = screen.createGraphics();
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
            draw("");
        } catch (RuntimeException unusable) {
            screen = null;
            canvas = null;
        }
    }

    /** Says on the image what the launch is doing. */
    public static void step(String what) {
        final String said = (what == null) ? "" : what;
        if (own != null) {
            own.say(said);
            return;
        }
        if (canvas == null) {
            return;
        }
        try {
            draw(said);
        } catch (RuntimeException gone) {
            canvas = null;
        }
    }

    /** Closes the image. The JVM also closes its own when the first window appears. */
    public static void done() {
        final Pane closing = own;
        if (closing != null) {
            own = null;
            closing.dispose();
        }
        try {
            if (screen != null && screen.isVisible()) {
                screen.close();
            }
        } catch (RuntimeException alreadyClosed) {
            // nothing left to close
        } finally {
            screen = null;
            canvas = null;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * The picture in a window of our own, for a jar whose manifest says nothing.
     *
     * Built here rather than posted to the event thread: it is plain AWT, it owns
     * every pixel it draws, and nothing of Swing is touched.
     */
    private static void showOwnWindow() {
        final BufferedImage image = readPacked();
        if (image == null) {
            return;
        }
        // The doubled image is shown at half its pixels, which is its real size.
        final int density = (image.getWidth() >= 1280) ? 2 : 1;
        try {
            Pane pane = new Pane(image, density);
            pane.setAlwaysOnTop(true);
            pane.setLocationRelativeTo(null);
            pane.setVisible(true);
            own = pane;
        } catch (RuntimeException | Error noWindow) {
            own = null;
        }
    }

    /** The best of the images the jar carries. */
    private static BufferedImage readPacked() {
        for (String name : IMAGES) {
            try (InputStream packed = Splash.class.getResourceAsStream(name)) {
                if (packed != null) {
                    BufferedImage image = ImageIO.read(packed);
                    if (image != null) {
                        return image;
                    }
                }
            } catch (Exception unreadable) {
                // the next one, or none at all
            }
        }
        return null;
    }

    /**
     * One pass: the band at the bottom, and the message in it.
     *
     * The band is repainted opaque rather than shaded, so that a second step
     * replaces the first instead of piling up on it.
     */
    private static void draw(String what) {
        canvas.setComposite(AlphaComposite.Src);
        canvas.setColor(SHADE);
        canvas.fillRect(0, area.height - STRIP, area.width, STRIP);

        if (!what.isEmpty()) {
            canvas.setComposite(AlphaComposite.SrcOver);
            canvas.setColor(INK);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            canvas.drawString(what, 16, area.height - 10);
        }
        screen.update();
    }

    /**
     * The window of our own. Plain AWT on purpose: it opens before the look and
     * feel is in place, and Synth has no style for a component it has not seen.
     */
    private static final class Pane extends Window {

        private final transient BufferedImage image;
        private String said = "";

        Pane(BufferedImage image, int density) {
            super((Frame) null);
            this.image = image;
            setSize(image.getWidth() / density, image.getHeight() / density);
        }

        void say(String what) {
            said = what;
            repaint();
        }

        @Override
        public void update(Graphics g) {
            paint(g);
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D paint = (Graphics2D) g.create();
            paint.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                   RenderingHints.VALUE_ANTIALIAS_ON);
            paint.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            paint.setColor(SHADE);
            paint.fillRect(0, getHeight() - STRIP, getWidth(), STRIP);
            if (!said.isEmpty()) {
                paint.setColor(INK);
                paint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                paint.drawString(said, 16, getHeight() - 10);
            }
            paint.dispose();
        }
    }
}
