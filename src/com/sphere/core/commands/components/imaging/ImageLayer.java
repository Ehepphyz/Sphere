package com.sphere.components.imaging;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * One plane of a document. Carries pixels, annotations, or both: a scanned plot
 * with arrows on it is one layer of each, and the order in the list decides what
 * covers what.
 */
public final class ImageLayer {

    private String name;
    private BufferedImage image;
    private final List<Annotation> annotations = new ArrayList<>();
    private double opacity = 1.0;
    private boolean visible = true;
    private boolean locked;

    public ImageLayer(String name, BufferedImage image) {
        this.name = name;
        this.image = image;
    }

    public static ImageLayer raster(String name, int width, int height) {
        return new ImageLayer(name, blank(width, height));
    }

    public static ImageLayer annotations(String name) {
        return new ImageLayer(name, null);
    }

    public static BufferedImage blank(int width, int height) {
        return new BufferedImage(Math.max(1, width), Math.max(1, height),
                                 BufferedImage.TYPE_INT_ARGB);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank() ? "Layer" : name;
    }

    public BufferedImage getImage() {
        return image;
    }

    public void setImage(BufferedImage image) {
        this.image = image;
    }

    public List<Annotation> getAnnotations() {
        return annotations;
    }

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double opacity) {
        this.opacity = Math.max(0.0, Math.min(1.0, opacity));
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean hasPixels() {
        return image != null;
    }

    public boolean isEmpty() {
        return image == null && annotations.isEmpty();
    }

    /** Draws this layer in image space, honoring its opacity. */
    public void paint(Graphics2D g, double zoom) {
        if (!visible || opacity <= 0) {
            return;
        }
        Composite previous = g.getComposite();
        if (opacity < 1.0) {
            g.setComposite(AlphaComposite.getInstance(
                AlphaComposite.SRC_OVER, (float) opacity));
        }
        try {
            if (image != null) {
                g.drawImage(image, 0, 0, null);
            }
            for (Annotation annotation : annotations) {
                annotation.paint(g, zoom);
            }
        } finally {
            g.setComposite(previous);
        }
    }

    /** Turns this layer's annotations into pixels, so they can be filtered. */
    public void flattenAnnotations(int width, int height) {
        if (annotations.isEmpty()) {
            return;
        }
        BufferedImage target = image != null ? image : blank(width, height);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (Annotation annotation : annotations) {
                annotation.paint(g, 1.0);
            }
        } finally {
            g.dispose();
        }
        image = target;
        annotations.clear();
    }

    public ImageLayer copy() {
        BufferedImage clone = null;
        if (image != null) {
            clone = new BufferedImage(image.getWidth(), image.getHeight(),
                                      BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = clone.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        ImageLayer layer = new ImageLayer(name, clone);
        layer.opacity = opacity;
        layer.visible = visible;
        layer.locked = locked;
        for (Annotation annotation : annotations) {
            layer.annotations.add(annotation.copy());
        }
        return layer;
    }

    @Override
    public String toString() {
        return name;
    }
}
