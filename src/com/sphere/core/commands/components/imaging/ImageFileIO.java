package com.sphere.components.imaging;

import com.sphere.components.imaging.svg.SvgDocument;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import javax.imageio.plugins.tiff.TIFFDirectory;
import javax.imageio.plugins.tiff.TIFFField;
import javax.imageio.plugins.tiff.TIFFTag;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/** Reads and writes the formats the editor handles. */
public final class ImageFileIO {

    private ImageFileIO() {
    }

    /** Extensions the editor can open. */
    public static final String[] READABLE = {
        "png", "jpg", "jpeg", "gif", "bmp", "wbmp", "svg", "tif", "tiff"
    };

    /** The resolution written into a TIFF, which journals read to size a figure. */
    public static final int TIFF_DPI = 300;

    /** Pages of a TIFF stack read into layers; past this the stack is left to a dedicated tool. */
    private static final int MAX_PAGES = 64;

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

    private static boolean isTiff(String ext) {
        return ext.equals("tif") || ext.equals("tiff");
    }

    /**
     * Opens a file into a document. An SVG is drawn at a size that stays sharp on
     * screen and keeps its vector form, so it can be redrawn at any other size.
     *
     * A TIFF is read the way the field writes them. A detector frame or a
     * microscope image is 16-bit or floating point, which the editor's 8 bits
     * cannot hold, so it is shown stretched between its own darkest and
     * brightest values; a stack of frames becomes one layer per frame, the
     * first one showing. Either way the document remembers it, so that saving
     * cannot write the 8-bit copy over the original.
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

        if (isTiff(ext)) {
            return openTiff(file);
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

    private static ImageDocument openTiff(File file) throws IOException {
        final int pages = pageCount(file);
        final BufferedImage first = readPage(file, 0);
        if (first == null) {
            throw new IllegalArgumentException("No decoder available for " + file.getName() + ".");
        }
        final String depth = depthOf(first);
        final ImageDocument doc = new ImageDocument(displayable(first), file, "tiff");
        if (pages > 1) {
            doc.getLayers().get(0).setName("page 1");
            // Straight into the list: addLayer keeps an undo copy of every layer
            // each time, which for a stack is a copy of the stack per page.
            final int read = Math.min(pages, MAX_PAGES);
            for (int p = 1; p < read; p++) {
                final BufferedImage page = readPage(file, p);
                if (page == null || page.getWidth() != doc.getWidth() || page.getHeight() != doc.getHeight()) {
                    continue;       // a thumbnail page, or one of another size
                }
                final ImageLayer layer = new ImageLayer("page " + (p + 1), displayable(page));
                layer.setVisible(false);
                doc.getLayers().add(layer);
            }
            doc.setActiveIndex(0);
        }
        final StringBuilder note = new StringBuilder();
        if (depth != null) note.append(depth);
        if (pages > 1) {
            if (note.length() > 0) note.append(", ");
            note.append(pages).append(" pages").append(pages > MAX_PAGES ? " (first " + MAX_PAGES + " read)" : "");
        }
        doc.setSourceNote(note.length() == 0 ? null : note.toString());
        doc.markClean();
        return doc;
    }

    /** How many pictures a file holds: several for a TIFF stack, one otherwise. */
    public static int pageCount(File file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return 0;
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return Math.max(1, reader.getNumImages(true));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return 1;
        }
    }

    /** One page as it is stored, which for a TIFF may be 16-bit or floating point. */
    static BufferedImage readPage(File file, int page) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.read(page);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * What a picture holds beyond 8 bits a channel, said plainly, or null. The
     * packed and indexed kinds are 8-bit whatever their buffer type says.
     */
    static String depthOf(BufferedImage image) {
        if (image.getColorModel() instanceof DirectColorModel
            || image.getColorModel() instanceof IndexColorModel) {
            return null;
        }
        final Raster raster = image.getRaster();
        final String kind = raster.getNumBands() - (image.getColorModel().hasAlpha() ? 1 : 0) >= 3 ? "colour" : "grey";
        return switch (raster.getDataBuffer().getDataType()) {
            case DataBuffer.TYPE_BYTE -> null;
            case DataBuffer.TYPE_USHORT -> "16-bit " + kind;
            case DataBuffer.TYPE_SHORT -> "16-bit signed " + kind;
            case DataBuffer.TYPE_INT -> "32-bit integer " + kind;
            case DataBuffer.TYPE_FLOAT -> "32-bit float " + kind;
            case DataBuffer.TYPE_DOUBLE -> "64-bit float " + kind;
            default -> "deep " + kind;
        };
    }

    /**
     * An 8-bit copy for showing and editing.
     *
     * A deeper picture is stretched between the values below which 0.05% and
     * above which 0.05% of its samples lie, as ImageJ's auto contrast does:
     * mapped over its full range instead, a 12-bit detector frame stored in 16
     * bits comes out black, and one hot pixel would do the same to any frame.
     */
    public static BufferedImage displayable(BufferedImage image) {
        if (depthOf(image) == null) {
            return toArgb(image);
        }
        final Raster raster = image.getRaster();
        final int w = raster.getWidth();
        final int h = raster.getHeight();
        final int bands = raster.getNumBands();
        final boolean alpha = image.getColorModel().hasAlpha();
        final int colour = Math.max(1, alpha ? bands - 1 : bands);

        // The range, from a sample of at most about a million values.
        final long total = (long) w * h * Math.min(colour, 3);
        final int stride = (int) Math.max(1, total / 1_000_000);
        float[] sample = new float[(int) Math.min(total, 1_000_000L) + 3];
        int n = 0;
        long seen = 0;
        final float[] row = new float[w];
        for (int b = 0; b < Math.min(colour, 3); b++) {
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, b, row);
                for (int x = 0; x < w; x++) {
                    if (seen++ % stride != 0 || Float.isNaN(row[x]) || Float.isInfinite(row[x])) continue;
                    if (n == sample.length) sample = Arrays.copyOf(sample, n * 2);
                    sample[n++] = row[x];
                }
            }
        }
        float lo = 0;
        float hi = 1;
        if (n > 0) {
            Arrays.sort(sample, 0, n);
            lo = sample[(int) (0.0005 * (n - 1))];
            hi = sample[(int) Math.ceil(0.9995 * (n - 1))];
            if (!(hi > lo)) {
                lo = sample[0];
                hi = sample[n - 1];
            }
            if (!(hi > lo)) hi = lo + 1;
        }
        final float scale = 255f / (hi - lo);

        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final float[][] rows = new float[Math.min(colour, 3)][w];
        for (int y = 0; y < h; y++) {
            for (int b = 0; b < rows.length; b++) raster.getSamples(0, y, w, 1, b, rows[b]);
            for (int x = 0; x < w; x++) {
                final int r = level(rows[0][x], lo, scale);
                final int g = rows.length >= 3 ? level(rows[1][x], lo, scale) : r;
                final int bl = rows.length >= 3 ? level(rows[2][x], lo, scale) : r;
                out.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | bl);
            }
        }
        return out;
    }

    private static int level(float v, float lo, float scale) {
        if (Float.isNaN(v)) return 0;
        final float s = (v - lo) * scale;
        return s <= 0 ? 0 : s >= 255 ? 255 : Math.round(s);
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
            BufferedImage image = isTiff(ext) ? readPage(file, 0) : ImageIO.read(file);
            if (image == null) {
                return null;
            }
            return scaleToFit(isTiff(ext) ? displayable(image) : image, maxSide);
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
     * alpha turns into. A TIFF is written the same way: many of the programs a
     * figure goes through next show a TIFF's alpha as black.
     */
    public static void save(ImageDocument doc, File file, String format,
                            float jpegQuality) throws Exception {
        final String fmt = format == null || format.isBlank()
            ? extension(file.getName()) : format.toLowerCase(Locale.ROOT);

        if (fmt.equals("svg")) {
            throw new IllegalArgumentException(
                "Editing does not write SVG back; export to PNG instead.");
        }
        if (doc.getSourceNote() != null && doc.getFile() != null
            && file.getAbsoluteFile().equals(doc.getFile().getAbsoluteFile())) {
            throw new IllegalArgumentException(file.getName() + " holds " + doc.getSourceNote()
                + ", and the editor works on an 8-bit copy of one picture: writing it over the original "
                + "would lose data. Save it under another name.");
        }

        final boolean opaque = fmt.equals("jpg") || fmt.equals("jpeg") || fmt.equals("bmp") || isTiff(fmt);
        BufferedImage image = opaque ? doc.renderOpaque(Color.WHITE) : doc.render(false);

        if (fmt.equals("jpg") || fmt.equals("jpeg")) {
            writeJpeg(image, file, jpegQuality);
        } else if (isTiff(fmt)) {
            writeTiff(image, file, TIFF_DPI);
        } else if (!ImageIO.write(image, fmt, file)) {
            throw new IllegalArgumentException("No encoder available for " + fmt + ".");
        }

        doc.setFile(file);
        doc.setFormat(fmt);
        doc.setSourceNote(null);
        doc.markClean();
    }

    private static void writeJpeg(BufferedImage image, File file, float quality)
            throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG encoder available.");
        }
        ImageWriter writer = writers.next();
        // A stream over an existing file writes into it without shortening it,
        // so a smaller picture would keep the old one's tail. ImageIO.write
        // deletes first for the same reason.
        file.delete();
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

    /**
     * Writes a TIFF as a figure is expected to arrive: compressed without loss
     * (LZW, which every reader knows) and with its resolution stated, since a
     * TIFF that says nothing is taken for 72 dpi and a journal's checker then
     * refuses a figure that is perfectly sharp.
     */
    public static void writeTiff(BufferedImage image, File file, int dpi) throws IOException {
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No TIFF encoder available.");
        }
        final ImageWriter writer = writers.next();
        file.delete();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("LZW");
            final IIOMetadata defaults = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(image), param);
            final TIFFDirectory dir = TIFFDirectory.createFromMetadata(defaults);
            final BaselineTIFFTagSet base = BaselineTIFFTagSet.getInstance();
            final long[][] resolution = {{Math.max(1, dpi), 1}};
            dir.addTIFFField(new TIFFField(base.getTag(BaselineTIFFTagSet.TAG_X_RESOLUTION),
                TIFFTag.TIFF_RATIONAL, 1, resolution));
            dir.addTIFFField(new TIFFField(base.getTag(BaselineTIFFTagSet.TAG_Y_RESOLUTION),
                TIFFTag.TIFF_RATIONAL, 1, resolution));
            dir.addTIFFField(new TIFFField(base.getTag(BaselineTIFFTagSet.TAG_RESOLUTION_UNIT),
                BaselineTIFFTagSet.RESOLUTION_UNIT_INCH));
            dir.addTIFFField(new TIFFField(base.getTag(BaselineTIFFTagSet.TAG_SOFTWARE),
                TIFFTag.TIFF_ASCII, 1, new String[] {"Sphere"}));
            writer.write(null, new IIOImage(image, null, dir.getAsMetadata()), param);
        } finally {
            writer.dispose();
        }
    }

    /** Exports at a chosen scale, for a figure that has to meet a print size. */
    public static void export(ImageDocument doc, File file, String format,
                              int width, int height, float jpegQuality) throws Exception {
        export(doc, file, format, width, height, jpegQuality, TIFF_DPI);
    }

    /** The same, with the resolution a TIFF states. */
    public static void export(ImageDocument doc, File file, String format,
                              int width, int height, float jpegQuality, int dpi) throws Exception {
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
        if (fmt.equals("jpg") || fmt.equals("jpeg") || isTiff(fmt)) {
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
            if (isTiff(fmt)) {
                writeTiff(flat, file, dpi);
            } else {
                writeJpeg(flat, file, jpegQuality);
            }
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
        return new String[] {"png", "tiff", "jpg", "bmp", "gif"};
    }
}
