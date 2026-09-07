package com.sphere.components.imaging;

import com.sphere.components.imaging.svg.SvgDocument;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import java.util.Locale;

/** Reads and writes the formats the editor handles. */
public final class ImageFileIO {

    private ImageFileIO() {
    }

    /** Extensions the editor can open. */
    public static final String[] READABLE = {
        "png", "jpg", "jpeg", "gif", "bmp", "wbmp", "svg"
    };

    public static boolean isImage(File file) {
        return file != null && file.isFile() && isImageName(file.getName());
    }

    public static boolean isImageName(String name) {
        final String ext = extension(name);
        for (String known : READABLE) {
            if (known.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Opens a file into a document. An SVG is drawn at a size that stays sharp on
     * screen and keeps its vector form, so it can be redrawn at any other size.
     */
    public static ImageDocument open(File file) throws Exception {
        final String ext = extension(file.getName());

        if (ext.equals("svg")) {
            SvgDocument vector = SvgDocument.load(file);
            final double scale = fitScale(vector.getWidth(), vector.getHeight());
            BufferedImage raster = vector.rasterize(scale);
            ImageDocument doc = new ImageDocument(raster, file, "svg");
            doc.setVector(vector);
            doc.markClean();
            return doc;
        }

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IllegalArgumentException(
                "No decoder available for " + file.getName() + ".");
        }
        ImageDocument doc = new ImageDocument(toArgb(image), file, ext.isEmpty() ? "png" : ext);
        doc.markClean();
        return doc;
    }

    /** Reads just enough to show a preview, without building a document. */
    public static BufferedImage readPreview(File file, int maxSide) {
        try {
            final String ext = extension(file.getName());
            if (ext.equals("svg")) {
                SvgDocument vector = SvgDocument.load(file);
                final double scale = Math.min(
                    maxSide / Math.max(1.0, vector.getWidth()),
                    maxSide / Math.max(1.0, vector.getHeight()));
                return vector.rasterize(Math.max(0.05, Math.min(scale, 4.0)));
            }
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                return null;
            }
            return scaleToFit(image, maxSide);
        } catch (Exception e) {
            return null;
        }
    }

    public static BufferedImage scaleToFit(BufferedImage image, int maxSide) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        if (w <= maxSide && h <= maxSide) {
            return image;
        }
        final double scale = Math.min((double) maxSide / w, (double) maxSide / h);
        final int nw = Math.max(1, (int) Math.round(w * scale));
        final int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            ImageDocument.applyQuality(g);
            g.drawImage(image, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Writes the document. A format without an alpha channel gets the image
     * composited over white rather than over black, which is what an unhandled
     * alpha turns into.
     */
    public static void save(ImageDocument doc, File file, String format,
                            float jpegQuality) throws Exception {
        final String fmt = format == null || format.isBlank()
            ? extension(file.getName()) : format.toLowerCase(Locale.ROOT);

        if (fmt.equals("svg")) {
            throw new IllegalArgumentException(
                "Editing does not write SVG back; export to PNG instead.");
        }

        final boolean opaque = fmt.equals("jpg") || fmt.equals("jpeg") || fmt.equals("bmp");
        BufferedImage image = opaque ? doc.renderOpaque(Color.WHITE) : doc.render(false);

        if (fmt.equals("jpg") || fmt.equals("jpeg")) {
            writeJpeg(image, file, jpegQuality);
        } else if (!ImageIO.write(image, fmt, file)) {
            throw new IllegalArgumentException("No encoder available for " + fmt + ".");
        }

        doc.setFile(file);
        doc.setFormat(fmt);
        doc.markClean();
    }

    private static void writeJpeg(BufferedImage image, File file, float quality)
            throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG encoder available.");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
    }

    /** Exports at a chosen scale, for a figure that has to meet a print size. */
    public static void export(ImageDocument doc, File file, String format,
                              int width, int height, float jpegQuality) throws Exception {
        BufferedImage image;
        if (doc.isVector() && doc.getLayers().size() == 1
            && doc.getLayers().get(0).getAnnotations().isEmpty()) {
            // Redrawn from the vector, so an export larger than the screen size
            // is sharp rather than enlarged.
            image = doc.getVector().rasterize(width, height);
        } else {
            image = scaleTo(doc.render(false), width, height);
        }

        final String fmt = format.toLowerCase(Locale.ROOT);
        if (fmt.equals("jpg") || fmt.equals("jpeg")) {
            BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(),
                                                   BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = flat.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
            writeJpeg(flat, file, jpegQuality);
        } else if (!ImageIO.write(image, fmt, file)) {
            throw new IllegalArgumentException("No encoder available for " + fmt + ".");
        }
    }

    private static BufferedImage scaleTo(BufferedImage source, int w, int h) {
        if (source.getWidth() == w && source.getHeight() == h) {
            return source;
        }
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h),
                                              BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            ImageDocument.applyQuality(g);
            g.drawImage(source, 0, 0, out.getWidth(), out.getHeight(), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                                              BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A first rasterization big enough to stay sharp, without being wasteful. */
    private static double fitScale(double w, double h) {
        final double longest = Math.max(w, h);
        if (longest <= 0) {
            return 1.0;
        }
        if (longest < 400) {
            return Math.min(4.0, 1200.0 / longest);
        }
        if (longest > 4000) {
            return 4000.0 / longest;
        }
        return 2.0;
    }

    /** Formats the editor can write, for the save dialog. */
    public static String[] writableFormats() {
        return new String[] {"png", "jpg", "bmp", "gif"};
    }
}
