package com.sphere.components.spdf.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shrinks a picture by halves before Java2D draws it much smaller than it
 * is: bilinear filtering alone samples four pixels and a photo turns to noise.
 * The halvings are kept per picture, so scrolling and zooming reuse them.
 */
final class ImageScaler {

    private ImageScaler() {
    }

    private static final Map<BufferedImage, SoftReference<BufferedImage[]>> CHAINS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** The halving of an image closest above the size it is drawn at. */
    static BufferedImage scaleFor(BufferedImage img, AffineTransform imageToDevice) {
        double sx = Math.hypot(imageToDevice.getScaleX(), imageToDevice.getShearY()) * img.getWidth();
        double sy = Math.hypot(imageToDevice.getShearX(), imageToDevice.getScaleY()) * img.getHeight();
        double target = Math.max(sx / img.getWidth(), sy / img.getHeight());
        int steps = 0;
        double s = 1;
        while (s * 0.5 >= target && steps < 8 && img.getWidth() * s * 0.5 >= 2 && img.getHeight() * s * 0.5 >= 2) {
            s *= 0.5;
            steps++;
        }
        if (steps == 0) {
            return img;
        }
        SoftReference<BufferedImage[]> ref = CHAINS.get(img);
        BufferedImage[] chain = ref == null ? null : ref.get();
        if (chain == null) {
            chain = new BufferedImage[9];
            CHAINS.put(img, new SoftReference<>(chain));
        }
        BufferedImage cur = img;
        for (int i = 1; i <= steps; i++) {
            if (chain[i] == null) {
                int w = Math.max(1, cur.getWidth() / 2);
                int h = Math.max(1, cur.getHeight() / 2);
                BufferedImage half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = half.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(cur, 0, 0, w, h, null);
                g.dispose();
                chain[i] = half;
            }
            cur = chain[i];
        }
        return cur;
    }
}
