package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.render.PageRenderer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * Draws pages in the background and keeps the pictures while memory allows:
 * a few threads render what is asked, the newest request first (what is on
 * screen now beats what was scrolled past), an LRU cache bounded by pixels
 * keeps them, and a stale picture of a page (another zoom, the previous
 * version) stands in until the new one is ready.
 *
 * A page too big for one picture at its zoom (a poster, or any page zoomed
 * far in) is drawn in tiles, and only the tiles in sight are drawn; a whole
 * page at a lower resolution stands behind them while they come.
 */
final class RenderService {

    /** Tiles are this many device pixels square. */
    static final int TILE = 1024;
    /** A page bigger than this, in device pixels, is drawn in tiles. */
    static final long TILED_ABOVE = 8_000_000L;

    record Key(int page, int scale, int sig, int hide, boolean night, int tile) {
    }

    private final SpdfDoc doc;
    private final LinkedHashMap<Key, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<Key> pending = new HashSet<>();
    private final ThreadPoolExecutor pool;
    private long pixels;
    private final long budget;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong order = new AtomicLong();

    /** A render job; the newest comes first. */
    private final class Job implements Runnable, Comparable<Job> {
        final long seq = order.incrementAndGet();
        final Runnable work;

        Job(Runnable work) {
            this.work = work;
        }

        @Override
        public void run() {
            work.run();
        }

        @Override
        public int compareTo(Job o) {
            return Long.compare(o.seq, seq);
        }
    }

    RenderService(SpdfDoc doc) {
        this.doc = doc;
        long max = Runtime.getRuntime().maxMemory();
        this.budget = Math.max(20_000_000L, Math.min(120_000_000L, max / 12));
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        this.pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "spdf-render");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
    }

    void shutdown() {
        pool.shutdownNow();
    }

    /** Forgets pending work (the document changed under it). */
    void bump() {
        generation.incrementAndGet();
    }

    /** True when a page at a scale is drawn in tiles. */
    static boolean tiled(PdfPage p, double scale) {
        return (long) Math.ceil(p.displayWidth() * scale) * (long) Math.ceil(p.displayHeight() * scale) > TILED_ABOVE;
    }

    /** The scale of the whole-page picture standing behind the tiles of a page. */
    static double backdropScale(PdfPage p, double scale) {
        double px = Math.ceil(p.displayWidth() * scale) * Math.ceil(p.displayHeight() * scale);
        return scale * Math.sqrt(TILED_ABOVE * 0.45 / px);
    }

    /**
     * The picture of a page at a scale, or null while it is drawn (onReady
     * then runs on the EDT). scale is device pixels per point.
     */
    BufferedImage get(int page, double scale, PageRenderer.GlyphFilter hide, int hideKey, boolean night,
                      Runnable onReady) {
        return request(page, scale, hide, hideKey, night, -1, onReady);
    }

    /** One tile of a page (tx, ty count tiles from the top left), or null while it is drawn. */
    BufferedImage tile(int page, double scale, int tx, int ty, PageRenderer.GlyphFilter hide, int hideKey, boolean night,
                       Runnable onReady) {
        return request(page, scale, hide, hideKey, night, ty * 4096 + tx, onReady);
    }

    private synchronized BufferedImage request(int page, double scale, PageRenderer.GlyphFilter hide, int hideKey,
                                               boolean night, int tile, Runnable onReady) {
        int sig = doc.signature(page);
        Key k = new Key(page, (int) Math.round(scale * 100), sig, hideKey, night, tile);
        BufferedImage img = cache.get(k);
        if (img != null || pending.contains(k)) {
            return img;
        }
        pending.add(k);
        int gen = generation.get();
        pool.execute(new Job(() -> {
            BufferedImage out = null;
            try {
                if (gen == generation.get() || hideKey != 0) {
                    out = render(page, scale, hide, night, tile);
                }
            } catch (Throwable e) {
                out = null;
            }
            BufferedImage done = out;
            synchronized (RenderService.this) {
                pending.remove(k);
                if (done != null) {
                    cache.put(k, done);
                    pixels += (long) done.getWidth() * done.getHeight();
                    trim();
                }
            }
            if (done != null && onReady != null) {
                SwingUtilities.invokeLater(onReady);
            }
        }));
        return null;
    }

    /** Any whole picture of a page, best first: same version and closest scale, else an older one. */
    synchronized BufferedImage any(int page, double scale, boolean night) {
        int sig = doc.signature(page);
        BufferedImage best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<Key, BufferedImage> e : cache.entrySet()) {
            Key k = e.getKey();
            if (k.page != page || k.night != night || k.tile != -1) {
                continue;
            }
            double score = Math.abs(Math.log(k.scale / (scale * 100.0))) + (k.sig == sig ? 0 : 5) + (k.hide == 0 ? 0 : 3);
            if (score < bestScore) {
                bestScore = score;
                best = e.getValue();
            }
        }
        return best;
    }

    private void trim() {
        Iterator<Map.Entry<Key, BufferedImage>> it = cache.entrySet().iterator();
        while (pixels > budget && it.hasNext()) {
            Map.Entry<Key, BufferedImage> e = it.next();
            BufferedImage b = e.getValue();
            pixels -= (long) b.getWidth() * b.getHeight();
            it.remove();
        }
    }

    synchronized void clear() {
        cache.clear();
        pixels = 0;
    }

    BufferedImage render(int pageIndex, double scale, PageRenderer.GlyphFilter hide, boolean night) {
        return render(pageIndex, scale, hide, night, -1);
    }

    private BufferedImage render(int pageIndex, double scale, PageRenderer.GlyphFilter hide, boolean night, int tile) {
        PdfPage page = doc.page(pageIndex);
        int w = Math.max(1, (int) Math.ceil(page.displayWidth() * scale));
        int h = Math.max(1, (int) Math.ceil(page.displayHeight() * scale));
        int x0 = 0;
        int y0 = 0;
        if (tile >= 0) {
            x0 = (tile % 4096) * TILE;
            y0 = (tile / 4096) * TILE;
            w = Math.max(1, Math.min(TILE, w - x0));
            h = Math.max(1, Math.min(TILE, h - y0));
        }
        // the page's pictures decode side by side before the content runs
        doc.cache.predecode(page);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        PageRenderer.Options o = new PageRenderer.Options();
        o.hide = hide;
        int gen = generation.get();
        o.canceled = () -> hide == null && gen != generation.get();
        AffineTransform at = AffineTransform.getTranslateInstance(-x0, -y0);
        at.scale(scale, scale);
        doc.renderer.render(g, page, at, o);
        g.dispose();
        if (night) {
            nightMode(img);
        }
        return img;
    }

    /** Inverts lightness and keeps hue: white paper turns dark, colors stay recognizable. */
    static void nightMode(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            int y = (299 * r + 587 * g + 114 * b) / 1000;
            int d = 255 - 2 * y;
            r = clamp(r + d);
            g = clamp(g + d);
            b = clamp(b + d);
            r = 26 + r * 210 / 255;
            g = 27 + g * 210 / 255;
            b = 31 + b * 210 / 255;
            px[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
