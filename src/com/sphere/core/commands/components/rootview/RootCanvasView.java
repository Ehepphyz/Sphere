package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.Mode;
import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootPadPainter.Zone;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePaletteLight;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A ROOT canvas in Sphere, drawn from its numbers and live to the mouse.
 *
 * Every pad is painted as ROOT would paint it, and then answers the hand the
 * way a ROOT canvas does: a 3D view (LEGO, SURF, a TH3, a TGraph2D) turns when
 * dragged and comes closer with the wheel; a 1D or flat 2D pad zooms on the
 * range dragged over; a double click brings the pad back to how ROOT drew it.
 * The right button opens ROOT's context menu of the object under the mouse
 * (TH1F::hpx, TAxis::xaxis, TPaveStats::stats, TPad, TCanvas...), with every
 * function ROOT declares in it, and Sphere's own quick view settings beneath.
 * What the menus change can be undone (Ctrl+Z) and redone (Ctrl+Y).
 */
public final class RootCanvasView extends JComponent {

    private static final int HISTORY = 60;

    private RootScene scene;
    private final Map<Pad, View> views = new IdentityHashMap<>();
    /** Pads that hold something, in painting order, with where they were painted. */
    private final List<Object[]> painted = new ArrayList<>();
    /** Where every pad was painted, those that hold nothing too. */
    private final Map<Pad, Rectangle> areas = new IdentityHashMap<>();
    private final List<Pad> order = new ArrayList<>();
    private Consumer<String> status = s -> { };
    private Runnable changeListener = () -> { };
    /** The pads in the theme's canvas colours (ThemePalette's canvas keys); off, in ROOT's own. */
    private boolean themePaper = true;
    /** Set while a snapshot is taken: a picture to keep is drawn on ROOT's white paper. */
    private boolean exporting;
    private RootHost host;

    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();

    private Pad active;
    private Pad current;
    private Point pressed;
    private Point mouse;
    private Rectangle band;
    private double theta0;
    private double phi0;
    /** The point of an editable graph being dragged. */
    private Graph dragging;
    private int draggedPoint = -1;

    /** A canvas as it was: its scene, and what each pad's view changed, by the pad's place. */
    private record State(String json, List<View> views) {
    }

    public RootCanvasView() {
        setOpaque(true);
        setFocusable(true);
        final MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressedAt(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                draggedTo(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                releasedAt(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouse = e.getPoint();
                probe(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouse = null;
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                wheel(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    final Pad pad = padAt(e.getPoint());
                    if (pad != null) {
                        view(pad).reset();
                        repaint();
                        status.accept("view of " + pad.name + " reset to how ROOT drew it");
                    }
                }
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
        addMouseWheelListener(mouseAdapter);
        final int menu = java.awt.GraphicsEnvironment.isHeadless() ? InputEvent.CTRL_DOWN_MASK
            : java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "sphere-undo");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "sphere-redo");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK),
            "sphere-redo");
        getActionMap().put("sphere-undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isShowing()) undo();
            }
        });
        getActionMap().put("sphere-redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isShowing()) redo();
            }
        });
    }

    /** Where the view reports what is under the mouse and what it did. */
    public void setStatusListener(Consumer<String> listener) {
        this.status = listener == null ? s -> { } : listener;
    }

    /** Called after a function of a menu changed the scene, an undo or a redo. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener == null ? () -> { } : listener;
    }

    /** The window this canvas is in, for what a function opens, prints or reports. */
    public void setHost(RootHost host) {
        this.host = host;
    }

    /** The host, or a plain one: new canvases in windows of their own, printouts in a dialog. */
    public RootHost host() {
        if (host != null) return host;
        final RootCanvasView self = this;
        return new RootHost() {
            @Override
            public void open(RootScene s, String title) {
                final JFrame f = new JFrame(title);
                final RootCanvasView v = new RootCanvasView();
                v.setScene(s);
                v.setStatusListener(self.status);
                f.getContentPane().add(v);
                f.setSize(820, 620);
                f.setLocationRelativeTo(self);
                f.setVisible(true);
            }

            @Override
            public void show(String title, String text) {
                final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(self), title, java.awt.Dialog.ModalityType.MODELESS);
                final JTextArea a = new JTextArea(text, 30, 100);
                a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                a.setEditable(false);
                a.setCaretPosition(0);
                d.getContentPane().add(new JScrollPane(a));
                d.pack();
                d.setLocationRelativeTo(self);
                d.setVisible(true);
            }

            @Override
            public void status(String text) {
                self.status.accept(text);
            }
        };
    }

    public void setScene(RootScene scene) {
        this.scene = scene;
        views.clear();
        undo.clear();
        redo.clear();
        current = null;
        repaint();
    }

    /** The scene ROOT gave back after a function it did: the views of the pads are kept by their place. */
    void replaceScene(RootScene next) {
        final List<View> kept = new ArrayList<>();
        if (scene != null) for (Pad p : scene.pad.flatten()) kept.add(view(p));
        scene = next;
        views.clear();
        final List<Pad> pads = next.pad.flatten();
        for (int k = 0; k < pads.size() && k < kept.size(); k++) views.put(pads.get(k), kept.get(k));
        changed();
    }

    public RootScene getScene() {
        return scene;
    }

    /**
     * Draws the pads in the theme's canvas colours (the canvas keys of
     * ThemePalette: paper, ink, grid, boxes...), the default; off, in ROOT's
     * own white paper and black ink, which are the light palette's canvas
     * keys. A colour that carries data stays ROOT's either way. Pictures taken
     * with snapshot are always ROOT's.
     */
    public void setThemePaper(boolean on) {
        themePaper = on;
        repaint();
    }

    public boolean isThemePaper() {
        return themePaper;
    }

    /** The pad under the mouse last, or the first that draws something. */
    public Pad focusedPad() {
        if (active != null) return active;
        return painted.isEmpty() ? null : (Pad) painted.get(0)[0];
    }

    /** TPad::cd: the pad the next Draw goes to, framed in the selection colour. */
    public Pad currentPad() {
        return current;
    }

    public void setCurrentPad(Pad pad) {
        current = pad;
        repaint();
    }

    /** Sets the draw option of a pad's main object, as TBrowser's option box does. */
    public void setOption(Pad pad, String option) {
        if (pad == null) return;
        view(pad).option = option == null || option.isBlank() ? null : option.strip();
        final View v = view(pad);
        v.xr = null;
        v.yr = null;
        v.zr = null;
        repaint();
    }

    public String optionOf(Pad pad) {
        return pad == null ? "" : RootPadPainter.option(pad, view(pad));
    }

    View view(Pad pad) {
        return views.computeIfAbsent(pad, p -> new View());
    }

    /** Where a pad was painted last, null when it was not. */
    Rectangle areaOf(Pad pad) {
        final Rectangle r = areas.get(pad);
        return r == null ? null : new Rectangle(r);
    }

    /* ------------------------------------------------------------------ */
    /* Undo, redo, changes                                                 */
    /* ------------------------------------------------------------------ */

    /** The scene as it is, kept for an undo before a function changes it. */
    public void checkpoint() {
        if (scene == null) return;
        undo.push(state());
        while (undo.size() > HISTORY) undo.removeLast();
        redo.clear();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean undo() {
        if (undo.isEmpty() || scene == null) return false;
        redo.push(state());
        restore(undo.pop());
        status.accept("undone");
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty() || scene == null) return false;
        undo.push(state());
        restore(redo.pop());
        status.accept("redone");
        return true;
    }

    private State state() {
        final List<View> vs = new ArrayList<>();
        for (Pad p : scene.pad.flatten()) vs.add(copy(view(p)));
        return new State(RootSceneJson.write(scene), vs);
    }

    private void restore(State s) {
        scene = RootScene.parse(s.json());
        views.clear();
        final List<Pad> pads = scene.pad.flatten();
        for (int k = 0; k < pads.size() && k < s.views().size(); k++) views.put(pads.get(k), s.views().get(k));
        current = null;
        active = null;
        changed();
    }

    private static View copy(View v) {
        final View c = new View();
        c.option = v.option;
        c.logx = v.logx;
        c.logy = v.logy;
        c.logz = v.logz;
        c.gridx = v.gridx;
        c.gridy = v.gridy;
        c.statsOff = v.statsOff;
        c.xr = v.xr == null ? null : v.xr.clone();
        c.yr = v.yr == null ? null : v.yr.clone();
        c.zr = v.zr == null ? null : v.zr.clone();
        c.xTitle = v.xTitle;
        c.yTitle = v.yTitle;
        c.zTitle = v.zTitle;
        c.theta = v.theta;
        c.phi = v.phi;
        c.zoom = v.zoom;
        c.perspective = v.perspective;
        c.hideAxes3D = v.hideAxes3D;
        return c;
    }

    /** After a function changed the scene: painted again, and the window told. */
    public void changed() {
        repaint();
        if (scene != null) for (Pad p : scene.pad.flatten()) if (p.showProjectionX > 0 || p.showProjectionY > 0) showProjections(p);
        changeListener.run();
    }

    /** TH2::SetShowProjectionX/Y: the windows that follow the mouse over the pad. */
    void showProjections(Pad pad) {
        if (isShowing()) RootPanels.projections(this, pad);
    }

    /** The objects of the canvas that are of a class (or derive from it), for a function's object parameter. */
    List<RootArgsDialog.Choice> objectsOf(String baseClass) {
        final List<RootArgsDialog.Choice> out = new ArrayList<>();
        if (scene == null) return out;
        for (Pad p : scene.pad.flatten()) {
            for (Item i : p.items) {
                final List<Item> all = new ArrayList<>();
                all.add(i);
                if (i instanceof Group g) all.addAll(g.items);
                if (i instanceof Hist h) all.addAll(h.fits);
                for (Item m : all) {
                    final String cls = m.className == null || m.className.isBlank() ? "TObject" : m.className;
                    if (baseClass == null || baseClass.isBlank() || RootMethod.inherits(cls, baseClass)
                        || baseClass.equals("TObject")) {
                        out.add(new RootArgsDialog.Choice(cls + "::" + m.name + "  (" + p.name + ")", m));
                    }
                }
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Painting                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    protected void paintComponent(Graphics graphics) {
        final Graphics2D screen = (Graphics2D) graphics.create();
        BufferedImage gray = null;
        Graphics2D g = screen;
        if (scene != null && scene.grayscale && getWidth() > 0 && getHeight() > 0) {
            gray = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
            g = gray.createGraphics();
        }
        try {
            // The theme's canvas colours, or ROOT's own (the light palette's) for ROOT's paper and for a snapshot.
            RootPadPainter.palette = themePaper && !exporting ? ThemeManager.getCurrentPalette()
                : ThemePaletteLight.INSTANCE;
            g.setColor(RootPadPainter.palette.getCanvasGround());
            g.fillRect(0, 0, getWidth(), getHeight());
            painted.clear();
            areas.clear();
            order.clear();
            if (scene == null) {
                message(g, "Select a canvas or an object");
                return;
            }
            if (scene.error != null) {
                message(g, scene.error);
                return;
            }
            final Rectangle all = canvasArea();
            RootFonts.scale = scene.height > 0 ? all.height / (double) scene.height : 1;
            paintPad(g, scene.pad, all);
            if (!exporting) live(g);
            if (band != null) {
                g.setColor(RootPadPainter.palette.getCanvasSelection());
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f}, 0f));
                g.draw(band);
            }
        } finally {
            if (gray != null) {
                g.dispose();
                // TCanvas::SetGrayscale: each colour by its luminance, as ROOT converts the colour table.
                final int[] px = gray.getRGB(0, 0, gray.getWidth(), gray.getHeight(), null, 0, gray.getWidth());
                for (int k = 0; k < px.length; k++) {
                    final int r = px[k] >> 16 & 255;
                    final int gg = px[k] >> 8 & 255;
                    final int b = px[k] & 255;
                    final int l = (int) Math.round(0.299 * r + 0.587 * gg + 0.114 * b);
                    px[k] = 0xFF000000 | l << 16 | l << 8 | l;
                }
                gray.setRGB(0, 0, gray.getWidth(), gray.getHeight(), px, 0, gray.getWidth());
                screen.drawImage(gray, 0, 0, null);
            }
            screen.dispose();
        }
    }

    /** The canvas keeps ROOT's proportions inside the component. */
    private Rectangle canvasArea() {
        final int w = getWidth() - 8;
        final int h = getHeight() - 8;
        final double ratio = scene.width > 0 && scene.height > 0 ? scene.width / (double) scene.height : 1.4;
        int cw = w;
        int ch = (int) Math.round(w / ratio);
        if (ch > h) {
            ch = h;
            cw = (int) Math.round(h * ratio);
        }
        return new Rectangle(4 + (w - cw) / 2, 4 + (h - ch) / 2, Math.max(1, cw), Math.max(1, ch));
    }

    private void paintPad(Graphics2D g, Pad pad, Rectangle area) {
        areas.put(pad, new Rectangle(area));
        order.add(pad);
        if (pad.drawsSomething() || pad.pads.isEmpty() || !pad.items.isEmpty()) {
            RootPadPainter.paint(g, area, pad, view(pad), scene);
            if (pad.drawsSomething()) painted.add(new Object[]{pad, new Rectangle(area)});
        } else {
            g.setColor(RootPadPainter.paper(pad.fill));
            g.fill(area);
        }
        border(g, pad, area);
        for (Pad sub : pad.pads) {
            final Rectangle r = new Rectangle(
                area.x + (int) Math.round(sub.px * area.width),
                area.y + area.height - (int) Math.round((sub.py + sub.ph) * area.height),
                Math.max(1, (int) Math.round(sub.pw * area.width)),
                Math.max(1, (int) Math.round(sub.ph * area.height)));
            paintPad(g, sub, r);
        }
        if (pad == current && !exporting) {
            g.setColor(RootPadPainter.palette.getCanvasSelection());
            g.setStroke(new BasicStroke(2f));
            g.drawRect(area.x + 1, area.y + 1, area.width - 3, area.height - 3);
            g.setStroke(new BasicStroke(1f));
        }
    }

    /** TPad::SetBorderMode and SetBorderSize: a raised (1) or sunken (-1) bevel. */
    private static void border(Graphics2D g, Pad pad, Rectangle a) {
        if (pad.borderMode == 0 || pad.borderSize <= 0) return;
        final Color base = RootPadPainter.paper(pad.fill);
        final Color light = base.brighter();
        final Color dark = base.darker().darker();
        final int n = Math.min(pad.borderSize, Math.max(1, Math.min(a.width, a.height) / 8));
        for (int k = 0; k < n; k++) {
            g.setColor(pad.borderMode > 0 ? light : dark);
            g.drawLine(a.x + k, a.y + k, a.x + a.width - 1 - k, a.y + k);
            g.drawLine(a.x + k, a.y + k, a.x + k, a.y + a.height - 1 - k);
            g.setColor(pad.borderMode > 0 ? dark : light);
            g.drawLine(a.x + k, a.y + a.height - 1 - k, a.x + a.width - 1 - k, a.y + a.height - 1 - k);
            g.drawLine(a.x + a.width - 1 - k, a.y + k, a.x + a.width - 1 - k, a.y + a.height - 1 - k);
        }
    }

    /** What follows the mouse: the crosshair (TPad::SetCrosshair) and the highlighted bin or point (SetHighlight). */
    private void live(Graphics2D g) {
        if (mouse == null) return;
        final Pad pad = padAt(mouse);
        if (pad == null) return;
        final View v = view(pad);
        if (v.mode != Mode.ONE_D && v.mode != Mode.FLAT) return;
        final Rectangle f = v.frame;
        if (!f.contains(mouse)) return;
        final Item main = pad.main();
        if (main != null && main.highlight) {
            g.setColor(new Color(255, 200, 0, 90));
            if (main instanceof Hist h && h.dim == 1) {
                final int bin = h.x.bin(v.dataX(mouse.x));
                if (bin >= 0) {
                    final int x0 = RootPadPainter.px(v, h.x.edge(bin));
                    final int x1 = RootPadPainter.px(v, h.x.edge(bin + 1));
                    g.fillRect(Math.min(x0, x1), f.y, Math.max(2, Math.abs(x1 - x0)), f.height);
                }
            } else if (main instanceof Hist h && h.dim == 2) {
                final int bx = h.x.bin(v.dataX(mouse.x));
                final int by = h.y.bin(v.dataY(mouse.y));
                if (bx >= 0 && by >= 0) {
                    final int x0 = RootPadPainter.px(v, h.x.edge(bx));
                    final int x1 = RootPadPainter.px(v, h.x.edge(bx + 1));
                    final int y0 = RootPadPainter.py(v, h.y.edge(by));
                    final int y1 = RootPadPainter.py(v, h.y.edge(by + 1));
                    g.fillRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(2, Math.abs(x1 - x0)), Math.max(2, Math.abs(y1 - y0)));
                }
            } else if (main instanceof Graph gr) {
                final int k = nearestPoint(v, gr, mouse);
                if (k >= 0) {
                    g.setStroke(new BasicStroke(2f));
                    g.setColor(new Color(255, 160, 0));
                    g.drawOval(RootPadPainter.px(v, gr.x[k]) - 7, RootPadPainter.py(v, gr.y[k]) - 7, 14, 14);
                    g.setStroke(new BasicStroke(1f));
                }
            }
        }
        if (pad.crosshair) {
            g.setColor(RootPadPainter.palette.getCanvasSelection());
            g.drawLine(f.x, mouse.y, f.x + f.width, mouse.y);
            g.drawLine(mouse.x, f.y, mouse.x, f.y + f.height);
        }
    }

    private void message(Graphics2D g, String text) {
        g.setColor(RootPadPainter.palette.getCanvasInkMuted());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        final int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, Math.max(8, (getWidth() - w) / 2), getHeight() / 2);
    }

    /** The whole canvas as a picture, at the size given. */
    public BufferedImage snapshot(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        final int w = getWidth();
        final int h = getHeight();
        exporting = true;
        try {
            setSize(width, height);
            paintComponent(g);
        } finally {
            exporting = false;
            g.dispose();
            if (w > 0 && h > 0) setSize(w, h);
        }
        return image;
    }

    /* ------------------------------------------------------------------ */
    /* The mouse                                                           */
    /* ------------------------------------------------------------------ */

    private Pad padAt(Point p) {
        for (int k = painted.size() - 1; k >= 0; k--) {
            if (((Rectangle) painted.get(k)[1]).contains(p)) return (Pad) painted.get(k)[0];
        }
        return null;
    }

    /** The innermost pad under a point, whether it holds something or not. */
    private Pad anyPadAt(Point p) {
        for (int k = order.size() - 1; k >= 0; k--) {
            final Rectangle r = areas.get(order.get(k));
            if (r != null && r.contains(p)) return order.get(k);
        }
        return null;
    }

    private static int nearestPoint(View v, Graph g, Point p) {
        int best = -1;
        double bd = 12;
        for (int i = 0; i < g.x.length; i++) {
            final double d = Math.hypot(RootPadPainter.px(v, g.x[i]) - p.x, RootPadPainter.py(v, g.y[i]) - p.y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private void pressedAt(MouseEvent e) {
        requestFocusInWindow();
        if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
            contextMenu(e.getPoint());
            return;
        }
        final Pad pad = padAt(e.getPoint());
        active = pad;
        if (pad == null) return;
        pressed = e.getPoint();
        final View v = view(pad);
        if (v.mode == Mode.THREE_D) {
            theta0 = Double.isNaN(v.theta) ? pad.theta : v.theta;
            phi0 = Double.isNaN(v.phi) ? pad.phi : v.phi;
        } else if (pad.editable && v.frame.contains(pressed)) {
            // TGraph::SetEditable: a point near the mouse follows it.
            for (Item i : pad.items) {
                if (i instanceof Graph g && g.editable) {
                    final int k = nearestPoint(v, g, pressed);
                    if (k >= 0) {
                        checkpoint();
                        dragging = g;
                        draggedPoint = k;
                        return;
                    }
                }
            }
        }
    }

    private void draggedTo(MouseEvent e) {
        if (active == null || pressed == null) return;
        final View v = view(active);
        mouse = e.getPoint();
        if (dragging != null) {
            dragging.x[draggedPoint] = v.dataX(e.getX());
            dragging.y[draggedPoint] = v.dataY(e.getY());
            status.accept(String.format(Locale.ROOT, "%s point %d at (%.6g, %.6g)", dragging.name, draggedPoint,
                dragging.x[draggedPoint], dragging.y[draggedPoint]));
            repaint();
            return;
        }
        if (v.mode == Mode.THREE_D) {
            v.phi = phi0 - (e.getX() - pressed.x) * 0.5;
            v.theta = Math.max(-90, Math.min(90, theta0 + (e.getY() - pressed.y) * 0.5));
            status.accept(String.format(Locale.ROOT, "theta %.0f°, phi %.0f°", v.theta, v.phi));
            repaint();
        } else if (v.mode == Mode.ONE_D || v.mode == Mode.FLAT) {
            final Rectangle f = v.frame;
            final int x0 = Math.max(f.x, Math.min(pressed.x, e.getX()));
            final int x1 = Math.min(f.x + f.width, Math.max(pressed.x, e.getX()));
            if (v.mode == Mode.ONE_D) {
                band = new Rectangle(x0, f.y, Math.max(1, x1 - x0), f.height);
            } else {
                final int y0 = Math.max(f.y, Math.min(pressed.y, e.getY()));
                final int y1 = Math.min(f.y + f.height, Math.max(pressed.y, e.getY()));
                band = new Rectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
            }
            repaint();
        }
    }

    private void releasedAt(MouseEvent e) {
        if (e.isPopupTrigger()) {
            contextMenu(e.getPoint());
            return;
        }
        if (dragging != null) {
            dragging = null;
            draggedPoint = -1;
            pressed = null;
            changed();
            return;
        }
        if (active != null && band != null && band.width > 4) {
            final View v = view(active);
            v.xr = new double[]{v.dataX(band.x), v.dataX(band.x + band.width)};
            if (v.mode == Mode.FLAT && band.height > 4) {
                v.yr = new double[]{v.dataY(band.y + band.height), v.dataY(band.y)};
            }
            status.accept(String.format(Locale.ROOT, "zoomed to x %.4g .. %.4g; double-click to come back",
                v.xr[0], v.xr[1]));
        }
        band = null;
        pressed = null;
        repaint();
    }

    private void wheel(MouseWheelEvent e) {
        final Pad pad = padAt(e.getPoint());
        if (pad == null) return;
        final View v = view(pad);
        final double factor = Math.pow(1.12, -e.getPreciseWheelRotation());
        if (v.mode == Mode.THREE_D) {
            v.zoom = Math.max(0.2, Math.min(8, v.zoom * factor));
        } else if (v.mode == Mode.ONE_D || v.mode == Mode.FLAT) {
            if (!v.frame.contains(e.getPoint())) return;
            final double cx = v.dataX(e.getX());
            v.xr = new double[]{cx - (cx - v.x0) / factor, cx + (v.x1 - cx) / factor};
            if (v.mode == Mode.FLAT) {
                final double cy = v.dataY(e.getY());
                v.yr = new double[]{cy - (cy - v.y0) / factor, cy + (v.y1 - cy) / factor};
            }
        }
        repaint();
    }

    /** What is under the mouse: the bin and its content, or how to handle a 3D view. */
    private void probe(Point p) {
        final Pad pad = padAt(p);
        if (pad == null) return;
        final View v = view(pad);
        final Item main = pad.main();
        if (pad.crosshair || main != null && main.highlight) repaint();
        if (v.mode == Mode.THREE_D) {
            status.accept(pad.name + ": drag to turn, wheel to zoom, double-click to reset, right-click for ROOT's menu");
            return;
        }
        if (!v.frame.contains(p) || main == null) {
            status.accept(pad.name + ": " + (main == null ? "" : main.className + " " + main.name));
            return;
        }
        final double x = v.dataX(p.x);
        final double y = v.dataY(p.y);
        if (main instanceof Hist h && h.dim == 1) {
            final int bin = h.x.bin(x);
            status.accept(bin < 0 ? String.format(Locale.ROOT, "x = %.4g", x)
                : String.format(Locale.ROOT, "%s  bin %d  [%.4g, %.4g)  content %.6g ± %.3g", h.name, bin + 1,
                    h.x.edge(bin), h.x.edge(bin + 1), h.at(bin, 0), h.error(bin)));
        } else if (main instanceof Hist h && h.dim == 2) {
            final int bx = h.x.bin(x);
            final int by = h.y.bin(y);
            status.accept(bx < 0 || by < 0 ? String.format(Locale.ROOT, "x = %.4g, y = %.4g", x, y)
                : String.format(Locale.ROOT, "%s  bin (%d, %d)  x %.4g  y %.4g  content %.6g", h.name, bx + 1, by + 1,
                    x, y, h.at(bx, by)));
            if (bx >= 0 && by >= 0 && (pad.showProjectionX > 0 || pad.showProjectionY > 0)) RootPanels.follow(pad, h, bx, by);
        } else if (main instanceof Graph g) {
            int nearest = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < g.x.length; i++) {
                final double d = Math.hypot(RootPadPainter.px(v, g.x[i]) - p.x, RootPadPainter.py(v, g.y[i]) - p.y);
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            status.accept(nearest >= 0 && best < 12
                ? String.format(Locale.ROOT, "%s  point %d  (%.6g, %.6g)", g.name, nearest, g.x[nearest], g.y[nearest])
                : String.format(Locale.ROOT, "x = %.4g, y = %.4g", x, y));
        } else {
            status.accept(String.format(Locale.ROOT, "x = %.4g, y = %.4g", x, y));
        }
    }

    /* ------------------------------------------------------------------ */
    /* The right button: ROOT's context menu                               */
    /* ------------------------------------------------------------------ */

    /** What a right click at p points at, as TCanvas's picking finds it: the object drawn last there first. */
    RootTarget targetAt(Point p) {
        if (scene == null) return null;
        final Pad pad = anyPadAt(p);
        if (pad == null) return new RootTarget("TCanvas", scene, scene.pad, scene, this, p, Double.NaN, Double.NaN);
        final View v = view(pad);
        double x = Double.NaN;
        double y = Double.NaN;
        if ((v.mode == Mode.ONE_D || v.mode == Mode.FLAT) && v.frame.contains(p)) {
            x = v.dataX(p.x);
            y = v.dataY(p.y);
        }
        for (int k = v.zones.size() - 1; k >= 0; k--) {
            final Zone z = v.zones.get(k);
            if (z.area().contains(p)) return new RootTarget(z.cls(), z.target(), pad, scene, this, p, x, y);
        }
        if (pad == scene.pad) return new RootTarget("TCanvas", scene, pad, scene, this, p, x, y);
        return new RootTarget("TPad", pad, pad, scene, this, p, x, y);
    }

    private void contextMenu(Point p) {
        final RootTarget t = targetAt(p);
        if (t == null) return;
        active = t.pad;
        final JPopupMenu menu = RootContextMenu.build(t, host(), sphereMenu(t.pad));
        menu.show(this, p.x, p.y);
    }

    /** The draw options that make sense for what the pad holds. */
    static String[] options(Item main) {
        if (main instanceof Hist h && h.dim == 1) return new String[]{"HIST", "E", "E1", "E2", "E3", "P", "L", "C", "BAR", "HIST P"};
        if (main instanceof Hist h && h.dim == 2) {
            return new String[]{"COLZ", "COL", "CONTZ", "CONT1", "BOX", "SCAT", "TEXT", "COLZ TEXT",
                "LEGO", "LEGO1", "LEGO2", "LEGO2Z", "SURF", "SURF1", "SURF1Z", "SURF2", "SURF2Z", "SURF4"};
        }
        if (main instanceof Hist h && h.dim == 3) return new String[]{"BOX", "BOX2", "BOX2Z", "SCAT"};
        if (main instanceof Graph2D) return new String[]{"TRI1", "TRI1Z", "TRI2", "TRI2Z", "TRI", "P0", "PCOL", "TRI1 P0"};
        if (main instanceof Graph) return new String[]{"ALP", "AP", "AL", "AC", "ACP", "A*", "AB", "AF"};
        return new String[0];
    }

    /** Sphere's quick settings of a pad's view, beneath ROOT's functions. */
    private JMenu sphereMenu(Pad pad) {
        final JMenu menu = new JMenu("Sphere view");
        if (pad == null) return menu;
        final View v = view(pad);
        final Item main = pad.main();
        final String current = RootPadPainter.option(pad, v);

        final JMenu draw = new JMenu("Draw option");
        final ButtonGroup group = new ButtonGroup();
        for (String o : options(main)) {
            final JRadioButtonMenuItem item = new JRadioButtonMenuItem(o, o.equalsIgnoreCase(current));
            item.addActionListener(e -> {
                checkpoint();
                setOption(pad, o);
            });
            group.add(item);
            draw.add(item);
        }
        draw.addSeparator();
        draw.add(action("Other option...", () -> {
            final String o = JOptionPane.showInputDialog(this, "Draw option for " + (main == null ? pad.name : main.name),
                current);
            if (o != null) {
                checkpoint();
                setOption(pad, o);
            }
        }));
        draw.add(action("As ROOT drew it", () -> setOption(pad, null)));
        menu.add(draw);

        if (main instanceof Hist h && h.dim == 2) {
            final boolean flat = v.mode == Mode.FLAT;
            menu.add(action(flat ? "View in 3D (LEGO2)" : "Flatten (COLZ)", () -> setOption(pad, flat ? "LEGO2" : "COLZ")));
        } else if (v.mode == Mode.THREE_D) {
            menu.add(action("Flatten (seen from above)", () -> {
                v.theta = 90;
                v.phi = 0;
                repaint();
            }));
        }
        if (v.mode == Mode.THREE_D) {
            menu.add(action("Front view", () -> {
                v.theta = 0;
                v.phi = 0;
                repaint();
            }));
        }
        menu.addSeparator();
        menu.add(check("Log X", RootPadPainter.log(v.logx, pad.logx), on -> v.logx = on));
        menu.add(check("Log Y", RootPadPainter.log(v.logy, pad.logy), on -> v.logy = on));
        menu.add(check("Log Z", RootPadPainter.log(v.logz, pad.logz), on -> v.logz = on));
        menu.add(check("Grid X", RootPadPainter.log(v.gridx, pad.gridx), on -> v.gridx = on));
        menu.add(check("Grid Y", RootPadPainter.log(v.gridy, pad.gridy), on -> v.gridy = on));
        menu.add(check("Statistics", !v.statsOff, on -> v.statsOff = !on));
        menu.addSeparator();
        menu.add(action("Axes...", () -> axesDialog(pad)));
        menu.add(action("Reset view", () -> {
            v.reset();
            repaint();
        }));
        menu.addSeparator();
        final JMenuItem undoItem = action("Undo", this::undo);
        undoItem.setEnabled(canUndo());
        final JMenuItem redoItem = action("Redo", this::redo);
        redoItem.setEnabled(canRedo());
        menu.add(undoItem);
        menu.add(redoItem);
        return menu;
    }

    private JMenuItem action(String label, Runnable run) {
        final JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> run.run());
        return item;
    }

    private JCheckBoxMenuItem check(String label, boolean on, Consumer<Boolean> set) {
        final JCheckBoxMenuItem item = new JCheckBoxMenuItem(label, on);
        item.addActionListener(e -> {
            checkpoint();
            set.accept(item.isSelected());
            repaint();
        });
        return item;
    }

    /** Ranges and titles of the three axes, the way ROOT's axis editor sets them. */
    private void axesDialog(Pad pad) {
        final View v = view(pad);
        final boolean threeD = v.mode == Mode.THREE_D;
        final JTextField[] fields = new JTextField[9];
        final JPanel panel = new JPanel(new GridLayout(4, 4, 6, 4));
        panel.add(new JLabel(""));
        panel.add(new JLabel("from"));
        panel.add(new JLabel("to"));
        panel.add(new JLabel("title"));
        final String[] names = {"X", "Y", "Z"};
        final double[][] now = {
            v.xr != null ? v.xr : new double[]{v.x0, v.x1},
            v.yr != null ? v.yr : new double[]{v.y0, v.y1},
            v.zr != null ? v.zr : new double[]{Double.NaN, Double.NaN}};
        final String[] titles = {v.xTitle, v.yTitle, v.zTitle};
        for (int a = 0; a < 3; a++) {
            panel.add(new JLabel(names[a]));
            fields[3 * a] = new JTextField(threeD && a < 2 && v.xr == null && v.yr == null ? "" : text(now[a][0]), 8);
            fields[3 * a + 1] = new JTextField(threeD && a < 2 && v.xr == null && v.yr == null ? "" : text(now[a][1]), 8);
            fields[3 * a + 2] = new JTextField(titles[a] == null ? "" : titles[a], 14);
            panel.add(fields[3 * a]);
            panel.add(fields[3 * a + 1]);
            panel.add(fields[3 * a + 2]);
        }
        if (JOptionPane.showConfirmDialog(this, panel, "Axes of " + pad.name, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        checkpoint();
        v.xr = range(fields[0], fields[1]);
        v.yr = range(fields[3], fields[4]);
        v.zr = range(fields[6], fields[7]);
        v.xTitle = blankToNull(fields[2].getText());
        v.yTitle = blankToNull(fields[5].getText());
        v.zTitle = blankToNull(fields[8].getText());
        repaint();
    }

    private static String text(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.6g", v) : "";
    }

    private static double[] range(JTextField a, JTextField b) {
        try {
            final double lo = Double.parseDouble(a.getText().strip());
            final double hi = Double.parseDouble(b.getText().strip());
            return hi > lo ? new double[]{lo, hi} : null;
        } catch (NumberFormatException empty) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
