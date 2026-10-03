package com.sphere.components.spherebrowser;

import com.sphere.components.imaging.ImagingTheme;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Sphere's 3D space: whatever the builder made of a plot, turned by the hand.
 *
 * Drag to turn it (a turntable that keeps z up, or a free arcball), with the
 * right button or Shift to slide it, the wheel to come closer; Ctrl, Shift and
 * Alt with the wheel stretch Z, X and Y, as do the sliders. Let go while
 * turning and it keeps spinning, slowing down. A double click brings the point
 * under the mouse to the centre. While still, the picture is drawn again at
 * three times the resolution and filtered, so edges are smooth.
 *
 * Keys: arrows turn, + - zoom, X Y Z stretch (with Shift shrink), R reset,
 * 1 front 2 side 3 top 4 ROOT's view, Space spins, W wires, S stereo, O
 * orthographic, F fog, L outline, B background, P saves the picture.
 */
public final class Space3DPanel extends JPanel {

    private final Function<SceneBuilder3D.Options, Scene3D> source;
    private final SceneBuilder3D.Options options;
    private final Renderer3D r = new Renderer3D();
    private final View view = new View();
    private final JLabel status = new JLabel(" ");
    private final Consumer<String> report;
    private Scene3D scene;

    private BufferedImage frame;
    private BufferedImage quality;
    private long version;
    private final Timer idle;
    private final Timer spin;
    private double spinX;
    private double spinY;
    private boolean autoRotate;
    private double autoSpeed = 0.6;
    private boolean arcball;
    private Renderer3D.Pick picked;
    private int fps;

    private final double[] initialAngles;

    /**
     * @param source  builds the scene from the options; called again when they change
     * @param theta   the elevation ROOT drew it at, NaN for the default
     * @param phi     the azimuth, NaN for the default
     */
    public Space3DPanel(Function<SceneBuilder3D.Options, Scene3D> source, SceneBuilder3D.Options options,
                        double theta, double phi, Consumer<String> report) {
        super(new BorderLayout());
        this.source = source;
        this.options = options == null ? new SceneBuilder3D.Options() : options;
        this.report = report == null ? s -> { } : report;
        this.initialAngles = new double[]{Double.isNaN(theta) ? 30 : theta, Double.isNaN(phi) ? 30 : phi};
        r.setAngles(initialAngles[0], initialAngles[1]);
        setBackground(ImagingTheme.panel());
        rebuild();

        final JPanel controls = controls();
        final JScrollPane side = new JScrollPane(controls, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        side.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, ImagingTheme.border()));
        side.getVerticalScrollBar().setUnitIncrement(18);
        side.setPreferredSize(new Dimension(286, 100));

        final JToggleButton panelToggle = new JToggleButton("Controls", true);
        ImagingTheme.styleButton(panelToggle);
        panelToggle.addActionListener(e -> {
            side.setVisible(panelToggle.isSelected());
            revalidate();
        });

        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 11.5f));
        status.setForeground(ImagingTheme.subduedText());
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        final JPanel south = ImagingTheme.panelOf(new BorderLayout());
        south.add(status, BorderLayout.CENTER);
        south.add(panelToggle, BorderLayout.EAST);

        add(view, BorderLayout.CENTER);
        add(side, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        idle = new Timer(280, e -> refine());
        idle.setRepeats(false);
        spin = new Timer(33, e -> animate());
    }

    /** The scene shown, after the last rebuild. */
    public Scene3D scene() {
        return scene;
    }

    /** Builds the scene again from the options: palette, style, cuts of the data. */
    public void rebuild() {
        try {
            scene = source.apply(options);
        } catch (RuntimeException failed) {
            scene = SceneBuilder3D.empty("could not build: " + failed.getMessage());
        }
        changed();
    }

    private void changed() {
        version++;
        quality = null;
        frame = null;
        view.repaint();
        if (idle != null) idle.restart();
    }

    /** The theme changed: the ground follows it again. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (r != null && idle != null) {
            r.darkBackground = ImagingTheme.isDark();
            setBackground(ImagingTheme.panel());
            syncChecks();
            moved();
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        spin.stop();
        idle.stop();
    }

    /* ------------------------------------------------------------------ */
    /* Drawing                                                             */
    /* ------------------------------------------------------------------ */

    private final class View extends JComponent {
        private Point last;
        private long lastTime;
        private double vx;
        private double vy;

        View() {
            setFocusable(true);
            setOpaque(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    spinX = 0;
                    spinY = 0;
                    if (!autoRotate) spin.stop();
                    last = e.getPoint();
                    lastTime = System.nanoTime();
                    vx = 0;
                    vy = 0;
                    if (e.isPopupTrigger()) popup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        popup(e);
                        return;
                    }
                    final boolean rotating = SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown();
                    if (rotating && (Math.abs(vx) > 0.15 || Math.abs(vy) > 0.15)
                        && System.nanoTime() - lastTime < 60_000_000L) {
                        spinX = vx;
                        spinY = vy;
                        spin.start();
                    }
                    last = null;
                    idle.restart();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (last == null) return;
                    final int dx = e.getX() - last.x;
                    final int dy = e.getY() - last.y;
                    final long now = System.nanoTime();
                    final double dt = Math.max(1, (now - lastTime) / 1e6);
                    if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) || e.isShiftDown()) {
                        final double k = r.distance / (getHeight() / 2.0 / Math.tan(Math.toRadians(r.fovDeg) / 2));
                        r.panX -= dx * k;
                        r.panY += dy * k;
                    } else if (e.isControlDown()) {
                        r.distance = Math.max(0.8, Math.min(60, r.distance * Math.pow(1.01, dy)));
                    } else {
                        rotate(dx, dy);
                        vx = dx * 16 / dt;
                        vy = dy * 16 / dt;
                    }
                    last = e.getPoint();
                    lastTime = now;
                    moved();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    final Renderer3D.Pick p = scene == null ? null : r.pick(scene, e.getX(), e.getY());
                    picked = p;
                    if (p != null) {
                        report(String.format(Locale.ROOT, "%s   x = %.5g   y = %.5g   z = %.5g   value = %.6g",
                            p.mesh().name, p.x(), p.y(), p.z(), p.value()));
                    } else {
                        report(hint());
                    }
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        final Renderer3D.Pick p = scene == null ? null : r.pick(scene, e.getX(), e.getY());
                        if (p != null) focusOn(p);
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    final double k = Math.pow(1.1, e.getPreciseWheelRotation());
                    if (e.isControlDown()) stretch(2, 1 / k);
                    else if (e.isShiftDown()) stretch(0, 1 / k);
                    else if (e.isAltDown()) stretch(1, 1 / k);
                    else r.distance = Math.max(0.8, Math.min(60, r.distance * k));
                    moved();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    key(e);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final int w = Math.max(16, getWidth());
            final int h = Math.max(16, getHeight());
            if (scene == null) return;
            BufferedImage shown = quality != null && quality.getWidth() == w && quality.getHeight() == h ? quality : null;
            if (shown == null) {
                if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
                    frame = r.render(scene, w, h, 1);
                    fps = (int) Math.min(999, 1e9 / Math.max(1, r.lastFrameNanos));
                }
                shown = frame;
            }
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.drawImage(shown, 0, 0, null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                if (picked != null) {
                    g.setColor(new Color(255, 210, 60));
                    g.setStroke(new BasicStroke(1.6f));
                    g.drawOval(picked.sx() - 7, picked.sy() - 7, 14, 14);
                    g.drawLine(picked.sx() - 11, picked.sy(), picked.sx() - 4, picked.sy());
                    g.drawLine(picked.sx() + 4, picked.sy(), picked.sx() + 11, picked.sy());
                }
                if (r.hud) hudText(g, w, h);
            } finally {
                g.dispose();
            }
        }

        private void hudText(Graphics2D g, int w, int h) {
            final double[] a = r.angles();
            final List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT, "θ %.0f°  φ %.0f°   zoom %.2f   stretch %.2f / %.2f / %.2f",
                a[0], a[1], Renderer3D.HOME / r.distance, r.stretch[0], r.stretch[1], r.stretch[2]));
            lines.add(String.format(Locale.ROOT, "%,d triangles  %,d vertices  %d fps%s%s", scene.triangles(),
                scene.vertices(), fps, r.perspective ? "" : "  ortho", r.anaglyph ? "  stereo" : ""));
            for (String n : scene.notes) lines.add(n);
            g.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
            g.setColor(ImagingTheme.subduedText());
            int y = h - 10 - 14 * (lines.size() - 1);
            for (String s : lines) {
                g.drawString(s, 12, y);
                y += 14;
            }
        }
    }

    private void moved() {
        frame = null;
        quality = null;
        version++;
        view.repaint();
        idle.restart();
    }

    /** Draws the resting view at three times the resolution, off the event thread. */
    private void refine() {
        if (scene == null || view.getWidth() < 16) return;
        final long mine = version;
        final Renderer3D twin = r.twin();
        final Scene3D s = scene;
        final int w = view.getWidth();
        final int h = view.getHeight();
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                final int ss = s.triangles() > 400_000 ? 2 : 3;
                return twin.render(s, w, h, ss);
            }

            @Override
            protected void done() {
                try {
                    if (mine == version) {
                        quality = get();
                        view.repaint();
                    }
                } catch (Exception ignored) {
                    // the quick frame stays
                }
            }
        }.execute();
    }

    private void animate() {
        if (autoRotate) {
            r.turnData(Math.toRadians(autoSpeed));
        }
        if (Math.abs(spinX) > 0.02 || Math.abs(spinY) > 0.02) {
            rotate(spinX, spinY);
            spinX *= 0.95;
            spinY *= 0.95;
        } else if (!autoRotate) {
            spinX = 0;
            spinY = 0;
            spin.stop();
        }
        moved();
    }

    private void rotate(double dx, double dy) {
        if (arcball) {
            final double angle = Math.hypot(dx, dy) * 0.008;
            r.turnScreen(dy, dx, 0, angle);
        } else {
            r.turnData(dx * 0.008);
            r.turnScreen(1, 0, 0, dy * 0.008);
        }
    }

    private void stretch(int axis, double k) {
        r.stretch[axis] = Math.max(0.05, Math.min(20, r.stretch[axis] * k));
        syncSliders();
    }

    /** Slides the view so the point picked is at its centre. */
    private void focusOn(Renderer3D.Pick p) {
        final double[] n = r.normalizedOf(p.x(), p.y(), p.z(), p.mesh().normalized);
        final double[] m = Renderer3D.matrix(r.q);
        final double x = n[0] * r.stretch[0];
        final double y = n[1] * r.stretch[1];
        final double z = n[2] * r.stretch[2];
        r.panX = m[0] * x + m[1] * y + m[2] * z;
        r.panY = m[3] * x + m[4] * y + m[5] * z;
        r.distance = Math.max(0.8, r.distance * 0.7);
        report("centred on " + p.mesh().name + String.format(Locale.ROOT, " (%.4g, %.4g, %.4g)", p.x(), p.y(), p.z()));
        moved();
    }

    private void report(String s) {
        status.setText(s);
        report.accept(s);
    }

    private String hint() {
        return "drag: turn · right/Shift-drag: slide · wheel: zoom · Ctrl/Shift/Alt+wheel: stretch Z/X/Y · "
            + "double-click: centre · Space: spin";
    }

    private void key(KeyEvent e) {
        final boolean shift = e.isShiftDown();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT -> r.turnData(Math.toRadians(-5));
            case KeyEvent.VK_RIGHT -> r.turnData(Math.toRadians(5));
            case KeyEvent.VK_UP -> r.turnScreen(1, 0, 0, Math.toRadians(-5));
            case KeyEvent.VK_DOWN -> r.turnScreen(1, 0, 0, Math.toRadians(5));
            case KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS, KeyEvent.VK_PAGE_UP -> r.distance /= 1.1;
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT, KeyEvent.VK_PAGE_DOWN -> r.distance *= 1.1;
            case KeyEvent.VK_X -> stretch(0, shift ? 1 / 1.15 : 1.15);
            case KeyEvent.VK_Y -> stretch(1, shift ? 1 / 1.15 : 1.15);
            case KeyEvent.VK_Z -> stretch(2, shift ? 1 / 1.15 : 1.15);
            case KeyEvent.VK_R -> resetView();
            case KeyEvent.VK_1 -> r.setAngles(0, 0);
            case KeyEvent.VK_2 -> r.setAngles(0, 90);
            case KeyEvent.VK_3 -> r.setAngles(90, 0);
            case KeyEvent.VK_4 -> r.setAngles(initialAngles[0], initialAngles[1]);
            case KeyEvent.VK_SPACE -> setAutoRotate(!autoRotate);
            case KeyEvent.VK_W -> r.wireframe = !r.wireframe;
            case KeyEvent.VK_S -> r.anaglyph = !r.anaglyph;
            case KeyEvent.VK_O -> r.perspective = !r.perspective;
            case KeyEvent.VK_F -> r.fog = !r.fog;
            case KeyEvent.VK_L -> r.outline = !r.outline;
            case KeyEvent.VK_B -> r.darkBackground = !r.darkBackground;
            case KeyEvent.VK_H -> r.hud = !r.hud;
            case KeyEvent.VK_P -> savePng();
            default -> {
                return;
            }
        }
        syncChecks();
        moved();
    }

    private void resetView() {
        r.setAngles(initialAngles[0], initialAngles[1]);
        r.distance = Renderer3D.HOME;
        r.panX = 0;
        r.panY = 0;
        r.stretch[0] = 1;
        r.stretch[1] = 1;
        r.stretch[2] = 1;
        for (int a = 0; a < 3; a++) {
            r.clipLo[a] = -1;
            r.clipHi[a] = 1;
        }
        syncSliders();
    }

    private void setAutoRotate(boolean on) {
        autoRotate = on;
        if (on) spin.start();
        syncChecks();
    }

    /* ------------------------------------------------------------------ */
    /* The controls                                                        */
    /* ------------------------------------------------------------------ */

    private final List<Runnable> syncers = new ArrayList<>();
    private final List<Runnable> checkSyncers = new ArrayList<>();
    private boolean syncing;

    private void syncSliders() {
        syncing = true;
        try {
            for (Runnable s : syncers) s.run();
        } finally {
            syncing = false;
        }
    }

    private void syncChecks() {
        syncing = true;
        try {
            for (Runnable s : checkSyncers) s.run();
        } finally {
            syncing = false;
        }
    }

    private JPanel controls() {
        final JPanel p = ImagingTheme.stack(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 4, 10, 4));

        section(p, "Representation");
        final JComboBox<SceneBuilder3D.Style> style = new JComboBox<>(SceneBuilder3D.Style.values());
        style.setSelectedItem(options.style);
        style.addActionListener(e -> {
            options.style = (SceneBuilder3D.Style) style.getSelectedItem();
            rebuild();
        });
        row(p, "Style", style);
        final JComboBox<String> palette = new JComboBox<>(Palettes.names());
        palette.setSelectedItem(options.palette);
        palette.addActionListener(e -> {
            options.palette = (String) palette.getSelectedItem();
            rebuild();
        });
        row(p, "Palette", palette);
        p.add(checks(
            check("Log Z", options.logZ, on -> {
                options.logZ = on;
                rebuild();
            }),
            check("Floor map", options.floorMap, on -> {
                options.floorMap = on;
                rebuild();
            })));
        p.add(checks(
            check("Contours", options.contours, on -> {
                options.contours = on;
                rebuild();
            }),
            check("Markers", options.markers, on -> {
                options.markers = on;
                rebuild();
            })));
        slider(p, "Opacity", 5, 100, (int) (options.opacity * 100), v -> {
            options.opacity = v / 100.0;
            rebuild();
        }, null, true);
        slider(p, "Threshold %", 0, 60, (int) (options.threshold * 100), v -> {
            options.threshold = v / 100.0;
            rebuild();
        }, null, true);
        slider(p, "Iso level % (TH3)", 2, 95, (int) (options.iso * 100), v -> {
            options.iso = v / 100.0;
            rebuild();
        }, null, true);
        p.add(checks(check("Nested shells", options.shells, on -> {
            options.shells = on;
            rebuild();
        }), check("Ink = height", options.invertImage, on -> {
            options.invertImage = on;
            rebuild();
        })));
        p.add(checks(check("Decode colours", options.decodePalette, on -> {
            options.decodePalette = on;
            rebuild();
        }), check("Theme paper", options.themePaper, on -> {
            options.themePaper = on;
            rebuild();
        })));
        slider(p, "Explode geometry", 0, 200, (int) (options.explode * 100), v -> {
            options.explode = v / 100.0;
            rebuild();
        }, null, true);
        slider(p, "Relief height", 2, 80, (int) (options.reliefHeight * 100), v -> {
            options.reliefHeight = v / 100.0;
            rebuild();
        }, null, true);

        section(p, "Axes: stretch X, Y, Z");
        for (int a = 0; a < 3; a++) {
            final int axis = a;
            slider(p, new String[]{"X", "Y", "Z"}[a], -100, 230, toSlider(r.stretch[a]), v -> {
                r.stretch[axis] = fromSlider(v);
                moved();
            }, sl -> sl.setValue(toSlider(r.stretch[axis])), false);
        }
        final JButton unit = ImagingTheme.textButton("1 : 1 : 1", "Every axis back to the cube");
        unit.addActionListener(e -> {
            r.stretch[0] = 1;
            r.stretch[1] = 1;
            r.stretch[2] = 1;
            syncSliders();
            moved();
        });
        final JButton flat = ImagingTheme.textButton("Flatten Z", "Squash the height: the map seen in perspective");
        flat.addActionListener(e -> {
            r.stretch[2] = 0.08;
            syncSliders();
            moved();
        });
        p.add(buttons(unit, flat));

        section(p, "Camera");
        p.add(buttons(
            view("Front", 0, 0), view("Side", 0, 90), view("Top", 90, 0), view("ROOT", initialAngles[0], initialAngles[1])));
        final JButton reset = ImagingTheme.textButton("Reset all", "Angles, zoom, stretch and cuts as they were");
        reset.addActionListener(e -> {
            resetView();
            moved();
        });
        p.add(buttons(reset));
        p.add(checks(
            check("Perspective", r.perspective, on -> {
                r.perspective = on;
                moved();
            }, () -> r.perspective),
            check("Free arcball", arcball, on -> arcball = on)));
        p.add(checks(check("Auto-rotate", autoRotate, this::setAutoRotate, () -> autoRotate)));
        slider(p, "Spin speed", 5, 400, (int) (autoSpeed * 100), v -> autoSpeed = v / 100.0, null, true);
        slider(p, "Field of view°", 8, 90, (int) r.fovDeg, v -> {
            r.fovDeg = v;
            moved();
        }, null, false);

        section(p, "Light & rendering");
        slider(p, "Light azimuth°", -180, 180, (int) r.lightAz, v -> {
            r.lightAz = v;
            moved();
        }, null, false);
        slider(p, "Light elevation°", -10, 90, (int) r.lightEl, v -> {
            r.lightEl = v;
            moved();
        }, null, false);
        slider(p, "Shine", 0, 100, (int) (r.specular * 100), v -> {
            r.specular = v / 100.0;
            moved();
        }, null, false);
        slider(p, "Ambient", 0, 100, (int) (r.ambient * 100), v -> {
            r.ambient = v / 100.0;
            moved();
        }, null, false);
        slider(p, "Point size", 20, 400, (int) (r.pointScale * 100), v -> {
            r.pointScale = v / 100.0;
            moved();
        }, null, false);
        p.add(checks(
            check("Wireframe", r.wireframe, on -> {
                r.wireframe = on;
                moved();
            }, () -> r.wireframe),
            check("Outline", r.outline, on -> {
                r.outline = on;
                moved();
            }, () -> r.outline)));
        p.add(checks(
            check("Depth fog", r.fog, on -> {
                r.fog = on;
                moved();
            }, () -> r.fog),
            check("Stereo 3D", r.anaglyph, on -> {
                r.anaglyph = on;
                moved();
            }, () -> r.anaglyph)));
        p.add(checks(
            check("Dark ground", r.darkBackground, on -> {
                r.darkBackground = on;
                moved();
            }, () -> r.darkBackground),
            check("Axes", r.axes, on -> {
                r.axes = on;
                moved();
            }, () -> r.axes)));
        p.add(checks(
            check("Walls", r.walls, on -> {
                r.walls = on;
                moved();
            }, () -> r.walls),
            check("Colour bar", r.colorBar, on -> {
                r.colorBar = on;
                moved();
            }, () -> r.colorBar)));

        section(p, "Cuts (open the box)");
        for (int a = 0; a < 3; a++) {
            final int axis = a;
            final String n = new String[]{"X", "Y", "Z"}[a];
            slider(p, n + " from", -100, 100, -100, v -> {
                r.clipLo[axis] = Math.min(v / 100.0, r.clipHi[axis] - 0.01);
                moved();
            }, sl -> sl.setValue((int) Math.round(r.clipLo[axis] * 100)), false);
            slider(p, n + " to", -100, 100, 100, v -> {
                r.clipHi[axis] = Math.max(v / 100.0, r.clipLo[axis] + 0.01);
                moved();
            }, sl -> sl.setValue((int) Math.round(r.clipHi[axis] * 100)), false);
        }

        section(p, "Export");
        final JButton png = ImagingTheme.textButton("PNG ×4", "The view at four times its size, supersampled");
        png.addActionListener(e -> savePng());
        final JButton gif = ImagingTheme.textButton("Turntable GIF", "A full turn, 48 frames, looping");
        gif.addActionListener(e -> saveGif());
        final JButton obj = ImagingTheme.textButton("OBJ", "The meshes, with vertex colours, for Blender or MeshLab");
        obj.addActionListener(e -> saveObj());
        final JButton copy = ImagingTheme.textButton("Copy", "The view to the clipboard");
        copy.addActionListener(e -> copyImage());
        p.add(buttons(png, gif));
        p.add(buttons(obj, copy));
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JButton view(String label, double theta, double phi) {
        final JButton b = ImagingTheme.textButton(label, String.format(Locale.ROOT, "θ %.0f°, φ %.0f°", theta, phi));
        b.addActionListener(e -> {
            r.setAngles(theta, phi);
            moved();
        });
        return b;
    }

    private static int toSlider(double stretch) {
        return (int) Math.round(Math.log10(stretch) * 100);
    }

    private static double fromSlider(int v) {
        return Math.pow(10, v / 100.0);
    }

    private void section(JPanel p, String title) {
        final JLabel l = ImagingTheme.sectionLabel(title);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
    }

    private void row(JPanel p, String label, JComponent c) {
        final JPanel row = ImagingTheme.panelOf(new BorderLayout(6, 0));
        row.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 8));
        final JLabel l = new JLabel(label);
        l.setForeground(ImagingTheme.text());
        l.setFont(ImagingTheme.uiFont(Font.PLAIN, 11.5f));
        l.setPreferredSize(new Dimension(64, 20));
        row.add(l, BorderLayout.WEST);
        row.add(c, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.add(row);
    }

    /**
     * A labelled slider. A slider that rebuilds the scene waits until it is
     * let go; one that only moves the camera follows the hand.
     */
    private void slider(JPanel p, String label, int min, int max, int value, java.util.function.IntConsumer set,
                        Consumer<JSlider> sync, boolean onRelease) {
        final JPanel box = ImagingTheme.panelOf(new BorderLayout());
        box.setBorder(BorderFactory.createEmptyBorder(1, 10, 1, 8));
        final JLabel l = new JLabel(label);
        l.setForeground(ImagingTheme.subduedText());
        l.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        final JSlider s = new JSlider(min, max, Math.max(min, Math.min(max, value)));
        s.setOpaque(false);
        final JLabel shown = new JLabel();
        shown.setForeground(ImagingTheme.text());
        shown.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        final Runnable label2 = () -> shown.setText(label.length() == 1
            ? String.format(Locale.ROOT, "×%.2f", fromSlider(s.getValue())) : String.valueOf(s.getValue()));
        label2.run();
        s.addChangeListener(e -> {
            label2.run();
            if (syncing) return;
            if (onRelease && s.getValueIsAdjusting()) return;
            set.accept(s.getValue());
        });
        if (sync != null) syncers.add(() -> {
            sync.accept(s);
            label2.run();
        });
        final JPanel head = ImagingTheme.panelOf(new BorderLayout());
        head.add(l, BorderLayout.WEST);
        head.add(shown, BorderLayout.EAST);
        box.add(head, BorderLayout.NORTH);
        box.add(s, BorderLayout.CENTER);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        p.add(box);
    }

    private JCheckBox check(String label, boolean on, Consumer<Boolean> set) {
        return check(label, on, set, null);
    }

    private JCheckBox check(String label, boolean on, Consumer<Boolean> set, java.util.function.BooleanSupplier now) {
        final JCheckBox c = new JCheckBox(label, on);
        c.setOpaque(false);
        c.setForeground(ImagingTheme.text());
        c.setFont(ImagingTheme.uiFont(Font.PLAIN, 11.5f));
        c.addActionListener(e -> {
            if (!syncing) set.accept(c.isSelected());
        });
        if (now != null) checkSyncers.add(() -> c.setSelected(now.getAsBoolean()));
        return c;
    }

    private JPanel checks(JComponent... cs) {
        final JPanel row = ImagingTheme.panelOf(new GridLayout(1, 2, 4, 0));
        row.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        for (JComponent c : cs) row.add(c);
        if (cs.length == 1) row.add(new JLabel());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return row;
    }

    private JPanel buttons(JButton... bs) {
        final JPanel row = ImagingTheme.panelOf(new GridLayout(1, bs.length, 4, 0));
        row.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 8));
        for (JButton b : bs) row.add(b);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return row;
    }

    private void popup(MouseEvent e) {
        final JPopupMenu menu = new JPopupMenu();
        final String[][] items = {
            {"Front view", "1"}, {"Side view", "2"}, {"Top view", "3"}, {"ROOT's view", "4"}, {"Reset (R)", "r"},
            {"Spin (Space)", " "}, {"Wireframe (W)", "w"}, {"Stereo anaglyph (S)", "s"}, {"Save PNG ×4 (P)", "p"},
            {"Turntable GIF", "gif"}, {"Export OBJ", "obj"}};
        for (String[] it : items) {
            final javax.swing.JMenuItem mi = new javax.swing.JMenuItem(it[0]);
            mi.addActionListener(a -> {
                switch (it[1]) {
                    case "gif" -> saveGif();
                    case "obj" -> saveObj();
                    default -> {
                        final int code = KeyEvent.getExtendedKeyCodeForChar(it[1].charAt(0));
                        key(new KeyEvent(view, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, it[1].charAt(0)));
                    }
                }
            });
            menu.add(mi);
        }
        menu.show(view, e.getX(), e.getY());
    }

    /* ------------------------------------------------------------------ */
    /* Export                                                              */
    /* ------------------------------------------------------------------ */

    private File choose(String suggested) {
        final JFileChooser chooser = new JFileChooser(com.sphere.components.rootview.RootPlotsPanel.plotsFolder().toFile());
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), suggested));
        return chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private String baseName() {
        final String t = scene == null || scene.title == null || scene.title.isBlank() ? "sphere3d" : scene.title;
        return t.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private void savePng() {
        final File f = choose(baseName() + "_3d.png");
        if (f == null) return;
        final Renderer3D twin = r.twin();
        final int w = view.getWidth() * 4;
        final int h = view.getHeight() * 4;
        background("Saving " + f.getName(), () -> {
            ImageIO.write(twin.render(scene, w, h, 2), "png", f);
            return "saved " + f;
        });
    }

    private void copyImage() {
        final BufferedImage img = quality != null ? quality : frame;
        if (img == null) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return img;
            }
        }, null);
        report("view copied to the clipboard");
    }

    private void saveObj() {
        final File f = choose(baseName() + ".obj");
        if (f == null) return;
        final Scene3D s = scene;
        background("Writing " + f.getName(), () -> {
            final StringBuilder out = new StringBuilder("# Sphere 3D space: ").append(s.title).append('\n');
            int offset = 0;
            for (Mesh3D m : s.meshes) {
                if (m.normalized || m.isEmpty()) continue;
                m.writeObj(out, offset);
                offset += m.vertexCount();
            }
            Files.writeString(f.toPath(), out, StandardCharsets.UTF_8);
            return "wrote " + f + " (" + offset + " vertices)";
        });
    }

    /** A full turn about z, as an animated GIF that loops. */
    private void saveGif() {
        final File f = choose(baseName() + "_turntable.gif");
        if (f == null) return;
        final Renderer3D twin = r.twin();
        final Scene3D s = scene;
        final int w = Math.min(720, view.getWidth());
        final int h = (int) Math.round(view.getHeight() * (w / (double) Math.max(1, view.getWidth())));
        background("Turning " + f.getName(), () -> {
            final int frames = 48;
            final ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
                writer.setOutput(out);
                writer.prepareWriteSequence(null);
                for (int k = 0; k < frames; k++) {
                    final BufferedImage img = twin.render(s, w, h, 2);
                    final ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(img);
                    final IIOMetadata meta = writer.getDefaultImageMetadata(type, null);
                    loop(meta, 6, k == 0);
                    writer.writeToSequence(new IIOImage(img, null, meta), null);
                    twin.turnData(2 * Math.PI / frames);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            return "wrote " + f + " (" + frames + " frames)";
        });
    }

    private static void loop(IIOMetadata meta, int delayCs, boolean first) throws IOException {
        final String format = meta.getNativeMetadataFormatName();
        final IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
        final IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", Integer.toString(delayCs));
        gce.setAttribute("transparentColorIndex", "0");
        if (first) {
            final IIOMetadataNode apps = child(root, "ApplicationExtensions");
            final IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[]{1, 0, 0});
            apps.appendChild(app);
        }
        meta.setFromTree(format, root);
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        final IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private interface Work {
        String run() throws Exception;
    }

    private void background(String what, Work work) {
        report(what + " ...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return work.run();
            }

            @Override
            protected void done() {
                try {
                    report(get());
                } catch (Exception e) {
                    report("failed: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                }
            }
        }.execute();
    }

    /** For a caller holding only the panel: a picture of the view as it is. */
    public BufferedImage snapshot() {
        return quality != null ? quality : frame;
    }
}
