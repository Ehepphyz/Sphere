package com.sphere.components.imaging;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The drawing surface: zoom, pan, layer compositing and tool input.
 *
 * Image space is what everything else speaks; this component owns the single
 * transform that maps it to the screen, so no other class has to think in
 * pixels-on-screen.
 */
public final class ImageCanvas extends JComponent {

    public enum Tool { SELECT, PAN, CROP, ARROW, LINE, RECTANGLE, ELLIPSE, TEXT, MEASURE,
                       FREEHAND, DIGITIZE }

    private static final double MIN_ZOOM = 0.02;
    private static final double MAX_ZOOM = 40.0;

    private ImageDocument document;
    private Tool tool = Tool.SELECT;

    private double zoom = 1.0;
    private double originX;
    private double originY;
    private boolean fitOnNextLayout = true;

    private Color strokeColor = new Color(0xE11D48);
    private Color fillColor;
    private double strokeWidth = 2.0;
    private int fontSize = 16;
    private double unitsPerPixel = 1.0;
    private String unitName = "px";

    private Annotation drafting;
    private Annotation selected;
    private Rectangle cropBox;
    private Point dragStart;
    private Point2D.Double dragStartImage;
    private boolean panning;
    private boolean movingSelection;

    private final Digitizer digitizer = new Digitizer();
    private Digitizer.Axis pendingAxis;
    private boolean pendingSecond;
    private Runnable digitizerListener;

    private final List<Runnable> changeListeners = new ArrayList<>();
    private Consumer<Point2D> pointerListener;
    private Consumer<Annotation> selectionListener;

    public ImageCanvas() {
        setOpaque(true);
        setFocusable(true);
        setBackground(new Color(0x2B2B2B));
        installInput();
    }

    // ---- document ----------------------------------------------------------

    public void setDocument(ImageDocument document) {
        this.document = document;
        this.selected = null;
        this.drafting = null;
        this.cropBox = null;
        fitOnNextLayout = true;
        revalidate();
        repaint();
        fireChanged();
    }

    public ImageDocument getDocument() {
        return document;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void setPointerListener(Consumer<Point2D> listener) {
        this.pointerListener = listener;
    }

    public void setSelectionListener(Consumer<Annotation> listener) {
        this.selectionListener = listener;
    }

    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    // ---- tool settings -----------------------------------------------------

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
        this.drafting = null;
        if (tool != Tool.CROP) {
            cropBox = null;
        }
        setCursor(Cursor.getPredefinedCursor(switch (tool) {
            case PAN -> Cursor.MOVE_CURSOR;
            case SELECT -> Cursor.DEFAULT_CURSOR;
            case TEXT -> Cursor.TEXT_CURSOR;
            case DIGITIZE -> Cursor.CROSSHAIR_CURSOR;
            default -> Cursor.CROSSHAIR_CURSOR;
        }));
        repaint();
    }

    public void setStrokeColor(Color color) {
        this.strokeColor = color;
        applyToSelection();
    }

    public Color getStrokeColor() {
        return strokeColor;
    }

    public void setFillColor(Color color) {
        this.fillColor = color;
        applyToSelection();
    }

    public Color getFillColor() {
        return fillColor;
    }

    public void setStrokeWidth(double width) {
        this.strokeWidth = Math.max(0.2, width);
        applyToSelection();
    }

    public double getStrokeWidth() {
        return strokeWidth;
    }

    public void setFontSize(int size) {
        this.fontSize = Math.max(4, size);
        applyToSelection();
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setScale(double unitsPerPixel, String unitName) {
        this.unitsPerPixel = unitsPerPixel <= 0 ? 1.0 : unitsPerPixel;
        this.unitName = unitName == null || unitName.isBlank() ? "px" : unitName;
        if (document != null) {
            for (ImageLayer layer : document.getLayers()) {
                for (Annotation a : layer.getAnnotations()) {
                    if (a.kind == Annotation.Kind.MEASURE) {
                        a.unitsPerPixel = this.unitsPerPixel;
                        a.unitName = this.unitName;
                    }
                }
            }
        }
        repaint();
    }

    private void applyToSelection() {
        if (selected == null) {
            return;
        }
        selected.color = strokeColor;
        selected.fillColor = fillColor;
        selected.strokeWidth = strokeWidth;
        selected.fontSize = fontSize;
        repaint();
        fireChanged();
    }

    public Annotation getSelected() {
        return selected;
    }

    public void deleteSelected() {
        if (selected == null || document == null) {
            return;
        }
        document.pushHistory();
        for (ImageLayer layer : document.getLayers()) {
            if (layer.getAnnotations().remove(selected)) {
                break;
            }
        }
        selected = null;
        notifySelection();
        repaint();
        fireChanged();
    }

    // ---- zoom and pan ------------------------------------------------------

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double value) {
        zoomAround(value, new Point(getWidth() / 2, getHeight() / 2));
    }

    public void zoomAround(double value, Point anchor) {
        final double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        if (document == null) {
            zoom = next;
            repaint();
            return;
        }
        // Keeping the point under the cursor fixed is what makes wheel zoom feel
        // right; without it the image slides away as it grows.
        Point2D.Double before = toImage(anchor);
        zoom = next;
        Point2D.Double after = toImage(anchor);
        originX += (after.x - before.x) * zoom;
        originY += (after.y - before.y) * zoom;
        revalidate();
        repaint();
        fireChanged();
    }

    /**
     * Fits against the viewport, not against this component: inside a scroll pane
     * the component is as large as its own preferred size, so measuring itself
     * would never make the image smaller.
     */
    public void zoomToFit() {
        if (document == null) {
            return;
        }
        final java.awt.Dimension visible = viewportSize();
        if (visible.width <= 0 || visible.height <= 0) {
            return;
        }
        final double margin = 24;
        final double sx = (visible.width - margin) / (double) document.getWidth();
        final double sy = (visible.height - margin) / (double) document.getHeight();
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.min(sx, sy)));
        center();
        fireChanged();
    }

    private java.awt.Dimension viewportSize() {
        java.awt.Container parent = getParent();
        if (parent instanceof javax.swing.JViewport viewport) {
            return viewport.getExtentSize();
        }
        return getSize();
    }

    public void zoomToActual() {
        zoom = 1.0;
        center();
        fireChanged();
    }

    private void center() {
        if (document == null) {
            return;
        }
        final java.awt.Dimension visible = viewportSize();
        originX = Math.max(6, (visible.width - document.getWidth() * zoom) / 2.0);
        originY = Math.max(6, (visible.height - document.getHeight() * zoom) / 2.0);
        revalidate();
        repaint();
    }

    private AffineTransform viewTransform() {
        AffineTransform t = new AffineTransform();
        t.translate(originX, originY);
        t.scale(zoom, zoom);
        return t;
    }

    public Point2D.Double toImage(Point screen) {
        try {
            Point2D p = viewTransform().inverseTransform(screen, null);
            return new Point2D.Double(p.getX(), p.getY());
        } catch (NoninvertibleTransformException e) {
            return new Point2D.Double(0, 0);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (document == null) {
            return new Dimension(400, 300);
        }
        final Dimension visible = viewportSize();
        return new Dimension(
            Math.max(visible.width, (int) Math.ceil(document.getWidth() * zoom + originX * 2)),
            Math.max(visible.height, (int) Math.ceil(document.getHeight() * zoom + originY * 2)));
    }

    // ---- painting ----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (document == null) {
                return;
            }
            if (fitOnNextLayout && viewportSize().width > 0) {
                fitOnNextLayout = false;
                zoomToFit();
            }

            ImageDocument.applyQuality(g);
            // Above 4x the pixels themselves are the subject; smoothing them
            // would hide exactly what the user zoomed in to see.
            if (zoom > 4.0) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            }

            final AffineTransform view = viewTransform();
            Graphics2D layers = (Graphics2D) g.create();
            try {
                layers.transform(view);
                Graphics2D ground = (Graphics2D) layers.create();
                try {
                    ImageDocument.paintChecker(ground, document.getWidth(),
                                               document.getHeight(),
                                               (int) Math.max(4, 16 / Math.max(zoom, 0.25)));
                } finally {
                    ground.dispose();
                }
                for (ImageLayer layer : document.getLayers()) {
                    layer.paint(layers, zoom);
                }
                if (drafting != null) {
                    drafting.paint(layers, zoom);
                }
            } finally {
                layers.dispose();
            }

            paintBorder(g, view);
            if (selected != null) {
                paintSelection(g, view);
            }
            if (cropBox != null) {
                paintCrop(g, view);
            }
            if (tool == Tool.DIGITIZE) {
                paintDigitizer(g, view);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintBorder(Graphics2D g, AffineTransform view) {
        Rectangle2D.Double box = new Rectangle2D.Double(
            0, 0, document.getWidth(), document.getHeight());
        g.setColor(new Color(0x60000000, true));
        g.setStroke(new BasicStroke(1f));
        g.draw(view.createTransformedShape(box));
    }

    private void paintSelection(Graphics2D g, AffineTransform view) {
        Rectangle2D bounds = view.createTransformedShape(selected.bounds()).getBounds2D();
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                    1f, new float[] {4f, 4f}, 0f));
        g.setColor(new Color(0x22D3EE));
        g.draw(new Rectangle2D.Double(bounds.getX() - 3, bounds.getY() - 3,
                                      bounds.getWidth() + 6, bounds.getHeight() + 6));
    }

    private void paintCrop(Graphics2D g, AffineTransform view) {
        Rectangle2D box = view.createTransformedShape(cropBox).getBounds2D();

        // Everything outside the box is dimmed, so the framing reads at a glance.
        Graphics2D shade = (Graphics2D) g.create();
        try {
            shade.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            shade.setColor(new Color(0x101010));
            java.awt.geom.Area outside = new java.awt.geom.Area(
                new Rectangle(0, 0, getWidth(), getHeight()));
            outside.subtract(new java.awt.geom.Area(box));
            shade.fill(outside);
        } finally {
            shade.dispose();
        }

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(box);

        // Thirds, the framing guide people expect from a crop tool.
        g.setColor(new Color(0x80FFFFFF, true));
        g.setStroke(new BasicStroke(0.8f));
        for (int i = 1; i <= 2; i++) {
            double x = box.getX() + box.getWidth() * i / 3.0;
            double y = box.getY() + box.getHeight() * i / 3.0;
            g.draw(new java.awt.geom.Line2D.Double(x, box.getY(), x, box.getMaxY()));
            g.draw(new java.awt.geom.Line2D.Double(box.getX(), y, box.getMaxX(), y));
        }
    }

    // ---- input -------------------------------------------------------------

    private void installInput() {
        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (document == null) {
                    return;
                }
                dragStart = e.getPoint();
                dragStartImage = toImage(e.getPoint());

                // The middle button pans whatever tool is selected: reaching for
                // the toolbar to move the image would break every workflow.
                if (SwingUtilities.isMiddleMouseButton(e) || tool == Tool.PAN) {
                    panning = true;
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                switch (tool) {
                    case SELECT -> beginSelect(dragStartImage);
                    case CROP -> cropBox = new Rectangle(
                        (int) dragStartImage.x, (int) dragStartImage.y, 0, 0);
                    case TEXT -> beginText(dragStartImage);
                    case DIGITIZE -> digitizeClick(dragStartImage);
                    default -> beginDraft(dragStartImage);
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (document == null || dragStart == null) {
                    return;
                }
                Point2D.Double image = toImage(e.getPoint());
                reportPointer(image);

                if (panning) {
                    originX += e.getX() - dragStart.x;
                    originY += e.getY() - dragStart.y;
                    dragStart = e.getPoint();
                    repaint();
                    return;
                }
                if (tool == Tool.CROP && cropBox != null) {
                    cropBox.setFrameFromDiagonal(dragStartImage.x, dragStartImage.y,
                                                 image.x, image.y);
                    repaint();
                    return;
                }
                if (movingSelection && selected != null) {
                    selected.moveBy(image.x - dragStartImage.x, image.y - dragStartImage.y);
                    dragStartImage = image;
                    repaint();
                    return;
                }
                if (drafting != null) {
                    if (drafting.kind == Annotation.Kind.FREEHAND) {
                        drafting.freehand.lineTo(image.x, image.y);
                    } else {
                        drafting.x2 = image.x;
                        drafting.y2 = image.y;
                        // Shift keeps a shape square and a line at 45 degrees.
                        if (e.isShiftDown()) {
                            constrain(drafting);
                        }
                    }
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (panning) {
                    panning = false;
                    dragStart = null;
                    return;
                }
                if (movingSelection) {
                    movingSelection = false;
                    dragStart = null;
                    fireChanged();
                    return;
                }
                if (drafting != null) {
                    commitDraft();
                }
                dragStart = null;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                reportPointer(toImage(e.getPoint()));
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (document == null) {
                    return;
                }
                final double step = Math.pow(1.1, -e.getPreciseWheelRotation());
                zoomAround(zoom * step, e.getPoint());
            }
        };

        addMouseListener(handler);
        addMouseMotionListener(handler);
        addMouseWheelListener(handler);
    }

    private void reportPointer(Point2D.Double image) {
        if (pointerListener != null) {
            pointerListener.accept(image);
        }
    }

    private void beginSelect(Point2D.Double at) {
        final double tolerance = 6 / Math.max(zoom, 0.05);
        selected = null;
        // Topmost first, so clicking a stack picks what is visually on top.
        outer:
        for (int i = document.getLayers().size() - 1; i >= 0; i--) {
            ImageLayer layer = document.getLayers().get(i);
            if (!layer.isVisible()) {
                continue;
            }
            List<Annotation> list = layer.getAnnotations();
            for (int j = list.size() - 1; j >= 0; j--) {
                if (list.get(j).hits(at.x, at.y, tolerance)) {
                    selected = list.get(j);
                    document.setActiveIndex(i);
                    movingSelection = true;
                    break outer;
                }
            }
        }
        notifySelection();
    }

    private void notifySelection() {
        if (selectionListener != null) {
            selectionListener.accept(selected);
        }
    }

    private void beginText(Point2D.Double at) {
        String text = javax.swing.JOptionPane.showInputDialog(
            this, "Text", "Add text", javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.isEmpty()) {
            return;
        }
        Annotation a = new Annotation(Annotation.Kind.TEXT);
        a.x1 = at.x;
        a.y1 = at.y;
        a.x2 = at.x;
        a.y2 = at.y;
        a.text = text;
        a.color = strokeColor;
        a.fontSize = fontSize;
        document.pushHistory();
        document.getAnnotationTarget().getAnnotations().add(a);
        selected = a;
        notifySelection();
        fireChanged();
        repaint();
    }

    private void beginDraft(Point2D.Double at) {
        Annotation.Kind kind = switch (tool) {
            case ARROW -> Annotation.Kind.ARROW;
            case LINE -> Annotation.Kind.LINE;
            case RECTANGLE -> Annotation.Kind.RECTANGLE;
            case ELLIPSE -> Annotation.Kind.ELLIPSE;
            case MEASURE -> Annotation.Kind.MEASURE;
            case FREEHAND -> Annotation.Kind.FREEHAND;
            default -> null;
        };
        if (kind == null) {
            return;
        }
        drafting = new Annotation(kind);
        drafting.x1 = at.x;
        drafting.y1 = at.y;
        drafting.x2 = at.x;
        drafting.y2 = at.y;
        drafting.color = strokeColor;
        drafting.fillColor = fillColor;
        drafting.strokeWidth = strokeWidth;
        drafting.fontSize = fontSize;
        drafting.unitsPerPixel = unitsPerPixel;
        drafting.unitName = unitName;
        if (kind == Annotation.Kind.FREEHAND) {
            drafting.freehand.moveTo(at.x, at.y);
        }
    }

    private void commitDraft() {
        Annotation a = drafting;
        drafting = null;
        if (a == null) {
            return;
        }
        // A click that never moved is not a shape.
        final boolean empty = a.kind != Annotation.Kind.FREEHAND
            && Math.hypot(a.x2 - a.x1, a.y2 - a.y1) < 2;
        if (empty) {
            repaint();
            return;
        }
        document.pushHistory();
        document.getAnnotationTarget().getAnnotations().add(a);
        selected = a;
        notifySelection();
        fireChanged();
        repaint();
    }

    private static void constrain(Annotation a) {
        final double dx = a.x2 - a.x1;
        final double dy = a.y2 - a.y1;
        if (a.kind == Annotation.Kind.RECTANGLE || a.kind == Annotation.Kind.ELLIPSE) {
            final double side = Math.max(Math.abs(dx), Math.abs(dy));
            a.x2 = a.x1 + Math.copySign(side, dx == 0 ? 1 : dx);
            a.y2 = a.y1 + Math.copySign(side, dy == 0 ? 1 : dy);
            return;
        }
        final double angle = Math.atan2(dy, dx);
        final double snapped = Math.round(angle / (Math.PI / 4)) * (Math.PI / 4);
        final double length = Math.hypot(dx, dy);
        a.x2 = a.x1 + Math.cos(snapped) * length;
        a.y2 = a.y1 + Math.sin(snapped) * length;
    }

    // ---- crop --------------------------------------------------------------

    public Rectangle getCropBox() {
        return cropBox;
    }

    public void applyCrop() {
        if (document == null || cropBox == null
            || cropBox.width <= 0 || cropBox.height <= 0) {
            return;
        }
        document.crop(cropBox);
        cropBox = null;
        selected = null;
        notifySelection();
        fitOnNextLayout = true;
        revalidate();
        repaint();
        fireChanged();
    }

    public void clearCrop() {
        cropBox = null;
        repaint();
    }

    // ---- digitizer ---------------------------------------------------------

    public Digitizer getDigitizer() {
        return digitizer;
    }

    public void setDigitizerListener(Runnable listener) {
        this.digitizerListener = listener;
    }

    /** The next click sets this axis reference rather than adding a point. */
    public void awaitReference(Digitizer.Axis axis, boolean second) {
        this.pendingAxis = axis;
        this.pendingSecond = second;
        setTool(Tool.DIGITIZE);
    }

    public boolean isAwaitingReference() {
        return pendingAxis != null;
    }

    public void cancelReference() {
        pendingAxis = null;
        repaint();
    }

    private void digitizeClick(Point2D.Double at) {
        if (pendingAxis != null) {
            final String label = pendingAxis == Digitizer.Axis.X ? "x" : "y";
            String entered = javax.swing.JOptionPane.showInputDialog(
                this,
                "Value of this point on the " + label + " axis",
                "Axis reference",
                javax.swing.JOptionPane.PLAIN_MESSAGE);
            if (entered != null) {
                try {
                    digitizer.setReference(pendingAxis, pendingSecond,
                                           at.x, at.y,
                                           Double.parseDouble(entered.trim().replace(',', '.')));
                } catch (NumberFormatException ignored) {
                    // A value that is not a number leaves the reference unset,
                    // and the panel keeps saying what is missing.
                }
            }
            pendingAxis = null;
        } else {
            digitizer.pick(at.x, at.y);
        }
        if (digitizerListener != null) {
            digitizerListener.run();
        }
        repaint();
    }

    private void paintDigitizer(Graphics2D g, AffineTransform view) {
        g.setStroke(new BasicStroke(1.4f));

        for (Digitizer.Axis axis : Digitizer.Axis.values()) {
            for (int i = 0; i < 2; i++) {
                Digitizer.Reference ref = digitizer.getReference(axis, i == 1);
                if (ref == null) {
                    continue;
                }
                Point2D p = view.transform(ref.point, null);
                g.setColor(axis == Digitizer.Axis.X
                    ? new Color(0x38BDF8) : new Color(0xFBBF24));
                final int r = 7;
                g.drawOval((int) p.getX() - r, (int) p.getY() - r, r * 2, r * 2);
                g.drawLine((int) p.getX() - r - 4, (int) p.getY(),
                           (int) p.getX() + r + 4, (int) p.getY());
                g.drawLine((int) p.getX(), (int) p.getY() - r - 4,
                           (int) p.getX(), (int) p.getY() + r + 4);
                g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 11f));
                g.drawString((axis == Digitizer.Axis.X ? "x" : "y") + (i + 1),
                             (int) p.getX() + r + 6, (int) p.getY() - r);
            }
        }

        List<Point2D.Double> points = digitizer.getPicked();
        g.setColor(new Color(0x34D399));
        Point2D previous = null;
        for (Point2D.Double raw : points) {
            Point2D p = view.transform(raw, null);
            g.fillOval((int) p.getX() - 3, (int) p.getY() - 3, 6, 6);
            if (previous != null) {
                g.setColor(new Color(0x8034D399, true));
                g.drawLine((int) previous.getX(), (int) previous.getY(),
                           (int) p.getX(), (int) p.getY());
                g.setColor(new Color(0x34D399));
            }
            previous = p;
        }
    }

    public void refresh() {
        revalidate();
        repaint();
        fireChanged();
    }
}
