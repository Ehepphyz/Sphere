package com.sphere.components.imaging;

import com.sphere.components.imaging.svg.SvgDocument;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The image being edited: its size, its stack of layers, and enough history to
 * undo. An SVG keeps its parsed form alongside the pixels, so it can be redrawn
 * at any size instead of being enlarged from a bitmap.
 */
public final class ImageDocument {

    /** A whole-document snapshot. Simple, and an image editor undoes rarely. */
    private static final class Snapshot {
        final List<ImageLayer> layers = new ArrayList<>();
        final int width;
        final int height;
        final int active;

        Snapshot(ImageDocument doc) {
            for (ImageLayer layer : doc.layers) {
                layers.add(layer.copy());
            }
            width = doc.width;
            height = doc.height;
            active = doc.activeIndex;
        }
    }

    private static final int HISTORY_LIMIT = 24;

    private final List<ImageLayer> layers = new ArrayList<>();
    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();

    private int width;
    private int height;
    private int activeIndex;
    private File file;
    private String format = "png";
    private SvgDocument vector;
    private boolean dirty;

    public ImageDocument(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        layers.add(ImageLayer.raster("Background", this.width, this.height));
    }

    public ImageDocument(BufferedImage image, File file, String format) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.file = file;
        this.format = format;
        layers.add(new ImageLayer(file == null ? "Background" : file.getName(), image));
    }

    // ---- geometry ----------------------------------------------------------

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public SvgDocument getVector() {
        return vector;
    }

    public void setVector(SvgDocument vector) {
        this.vector = vector;
    }

    public boolean isVector() {
        return vector != null;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    // ---- layers ------------------------------------------------------------

    public List<ImageLayer> getLayers() {
        return layers;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int index) {
        if (index >= 0 && index < layers.size()) {
            activeIndex = index;
        }
    }

    public ImageLayer getActiveLayer() {
        if (layers.isEmpty()) {
            layers.add(ImageLayer.raster("Layer 1", width, height));
            activeIndex = 0;
        }
        if (activeIndex >= layers.size()) {
            activeIndex = layers.size() - 1;
        }
        return layers.get(activeIndex);
    }

    /**
     * The layer that receives new annotations. A locked or pixel-only layer would
     * lose them, so one is added rather than silently dropping the work.
     */
    public ImageLayer getAnnotationTarget() {
        ImageLayer active = getActiveLayer();
        if (!active.isLocked()) {
            return active;
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (!layers.get(i).isLocked()) {
                activeIndex = i;
                return layers.get(i);
            }
        }
        return addLayer(ImageLayer.annotations("Annotations"));
    }

    public ImageLayer addLayer(ImageLayer layer) {
        pushHistory();
        layers.add(layer);
        activeIndex = layers.size() - 1;
        dirty = true;
        return layer;
    }

    public void removeActiveLayer() {
        if (layers.size() <= 1) {
            return;
        }
        pushHistory();
        layers.remove(activeIndex);
        activeIndex = Math.max(0, activeIndex - 1);
        dirty = true;
    }

    public void duplicateActiveLayer() {
        pushHistory();
        ImageLayer copy = getActiveLayer().copy();
        copy.setName(copy.getName() + " copy");
        layers.add(activeIndex + 1, copy);
        activeIndex++;
        dirty = true;
    }

    public void moveActiveLayer(int delta) {
        final int target = activeIndex + delta;
        if (target < 0 || target >= layers.size()) {
            return;
        }
        pushHistory();
        ImageLayer layer = layers.remove(activeIndex);
        layers.add(target, layer);
        activeIndex = target;
        dirty = true;
    }

    /** Merges the active layer into the one below it. */
    public void mergeDown() {
        if (activeIndex <= 0) {
            return;
        }
        pushHistory();
        ImageLayer upper = layers.get(activeIndex);
        ImageLayer lower = layers.get(activeIndex - 1);

        BufferedImage target = lower.hasPixels()
            ? lower.getImage() : ImageLayer.blank(width, height);
        Graphics2D g = target.createGraphics();
        try {
            applyQuality(g);
            lower.getAnnotations().forEach(a -> a.paint(g, 1.0));
            upper.paint(g, 1.0);
        } finally {
            g.dispose();
        }
        lower.getAnnotations().clear();
        lower.setImage(target);
        layers.remove(activeIndex);
        activeIndex--;
        dirty = true;
    }

    public void flatten() {
        pushHistory();
        BufferedImage flat = render(false);
        layers.clear();
        layers.add(new ImageLayer("Flattened", flat));
        activeIndex = 0;
        dirty = true;
    }

    // ---- rendering ---------------------------------------------------------

    /** Composites every visible layer. `checker` paints a transparency ground. */
    public BufferedImage render(boolean checker) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            applyQuality(g);
            if (checker) {
                paintChecker(g, width, height, 16);
            }
            for (ImageLayer layer : layers) {
                layer.paint(g, 1.0);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Renders for saving to a format without transparency: the alpha is composited
     * over the given ground rather than turned into black.
     */
    public BufferedImage renderOpaque(java.awt.Color ground) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            applyQuality(g);
            g.setColor(ground);
            g.fillRect(0, 0, width, height);
            for (ImageLayer layer : layers) {
                layer.paint(g, 1.0);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    public static void applyQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                           RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                           RenderingHints.VALUE_STROKE_PURE);
    }

    public static void paintChecker(Graphics2D g, int w, int h, int cell) {
        g.setColor(new java.awt.Color(0xF0F0F0));
        g.fillRect(0, 0, w, h);
        g.setColor(new java.awt.Color(0xD8D8D8));
        for (int y = 0; y < h; y += cell) {
            for (int x = (y / cell) % 2 == 0 ? 0 : cell; x < w; x += cell * 2) {
                g.fillRect(x, y, cell, cell);
            }
        }
    }

    // ---- edits that change the whole document ------------------------------

    public void resize(int newWidth, int newHeight, boolean smooth) {
        final int w = Math.max(1, newWidth);
        final int h = Math.max(1, newHeight);
        if (w == width && h == height) {
            return;
        }
        pushHistory();

        // A vector source is redrawn rather than enlarged: that is the whole
        // point of keeping it.
        if (vector != null && layers.size() == 1 && layers.get(0).getAnnotations().isEmpty()) {
            layers.get(0).setImage(vector.rasterize(w, h));
            width = w;
            height = h;
            dirty = true;
            return;
        }

        final double sx = (double) w / width;
        final double sy = (double) h / height;
        for (ImageLayer layer : layers) {
            if (layer.hasPixels()) {
                BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                try {
                    if (smooth) {
                        applyQuality(g);
                    } else {
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                           RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    }
                    g.drawImage(layer.getImage(), 0, 0, w, h, null);
                } finally {
                    g.dispose();
                }
                layer.setImage(scaled);
            }
            for (Annotation a : layer.getAnnotations()) {
                a.x1 *= sx;
                a.x2 *= sx;
                a.y1 *= sy;
                a.y2 *= sy;
                a.freehand.transform(java.awt.geom.AffineTransform.getScaleInstance(sx, sy));
            }
        }
        width = w;
        height = h;
        dirty = true;
    }

    public void crop(java.awt.Rectangle area) {
        java.awt.Rectangle box = area.intersection(new java.awt.Rectangle(0, 0, width, height));
        if (box.width <= 0 || box.height <= 0) {
            return;
        }
        pushHistory();
        for (ImageLayer layer : layers) {
            if (layer.hasPixels()) {
                BufferedImage cut = new BufferedImage(box.width, box.height,
                                                      BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = cut.createGraphics();
                try {
                    g.drawImage(layer.getImage(), -box.x, -box.y, null);
                } finally {
                    g.dispose();
                }
                layer.setImage(cut);
            }
            for (Annotation a : layer.getAnnotations()) {
                a.moveBy(-box.x, -box.y);
            }
        }
        width = box.width;
        height = box.height;
        vector = null; // the vector no longer describes what is on screen
        dirty = true;
    }

    /** Quarter turns only, so no pixel is resampled. */
    public void rotate(int quarterTurns) {
        final int turns = ((quarterTurns % 4) + 4) % 4;
        if (turns == 0) {
            return;
        }
        pushHistory();
        for (int i = 0; i < turns; i++) {
            rotateOnce();
        }
        vector = null;
        dirty = true;
    }

    private void rotateOnce() {
        final int w = height;
        final int h = width;
        for (ImageLayer layer : layers) {
            if (layer.hasPixels()) {
                BufferedImage turned = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = turned.createGraphics();
                try {
                    g.translate(w, 0);
                    g.rotate(Math.PI / 2);
                    g.drawImage(layer.getImage(), 0, 0, null);
                } finally {
                    g.dispose();
                }
                layer.setImage(turned);
            }
            for (Annotation a : layer.getAnnotations()) {
                double nx1 = height - a.y1;
                double ny1 = a.x1;
                double nx2 = height - a.y2;
                double ny2 = a.x2;
                a.x1 = nx1;
                a.y1 = ny1;
                a.x2 = nx2;
                a.y2 = ny2;
            }
        }
        width = w;
        height = h;
    }

    public void flip(boolean horizontal) {
        pushHistory();
        for (ImageLayer layer : layers) {
            if (layer.hasPixels()) {
                BufferedImage mirrored = new BufferedImage(width, height,
                                                           BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = mirrored.createGraphics();
                try {
                    if (horizontal) {
                        g.drawImage(layer.getImage(), width, 0, -width, height, null);
                    } else {
                        g.drawImage(layer.getImage(), 0, height, width, -height, null);
                    }
                } finally {
                    g.dispose();
                }
                layer.setImage(mirrored);
            }
            for (Annotation a : layer.getAnnotations()) {
                if (horizontal) {
                    a.x1 = width - a.x1;
                    a.x2 = width - a.x2;
                } else {
                    a.y1 = height - a.y1;
                    a.y2 = height - a.y2;
                }
            }
        }
        vector = null;
        dirty = true;
    }

    // ---- history -----------------------------------------------------------

    public void pushHistory() {
        undoStack.push(new Snapshot(this));
        while (undoStack.size() > HISTORY_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
        dirty = true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(new Snapshot(this));
        restore(undoStack.pop());
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(new Snapshot(this));
        restore(redoStack.pop());
    }

    private void restore(Snapshot snapshot) {
        layers.clear();
        layers.addAll(snapshot.layers);
        width = snapshot.width;
        height = snapshot.height;
        activeIndex = Math.min(snapshot.active, Math.max(0, layers.size() - 1));
        dirty = true;
    }
}
