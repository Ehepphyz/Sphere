package com.sphere.components.g4editor;

import com.sphere.core.geant4.Mesh;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A software rasterizer for the 3D view, in pure Java: z-buffer, Gouraud
 * shading with a key light, a fill light and a specular highlight, feature
 * edges and silhouettes, translucency, a section plane with capped cuts, and
 * an id buffer that makes picking a lookup of the pixel under the mouse.
 *
 * A frame is built in two passes, both spread over every core: vertices are
 * transformed and lit per drawn item, then the screen is cut into horizontal
 * bands, each rasterizing every triangle that touches it, so no two threads
 * ever write the same pixel and no lock is taken.
 */
final class Raster {

    static final int FLAG_SELECTED = 1, FLAG_HOVER = 2, FLAG_TRANSPARENT = 4, FLAG_NO_EDGES = 8,
        FLAG_WIRE = 16, FLAG_GHOST = 32, FLAG_NO_PICK = 64;

    private int w, h;
    private BufferedImage image;
    private int[] color;
    private float[] depth;
    private int[] ids;

    // Frame state
    private Vec3 eye, right, up, back;
    /** Lights in world space: key from upper left over the shoulder, fill from lower right; and the key's half vector. */
    private Vec3 light1, light2, halfway;
    private boolean persp;
    private double focal, halfH, aspect, near;
    private boolean clip;
    private double cnx, cny, cnz, cd;
    private int bgTop = 0xFFB9C8DC, bgBottom = 0xFFEEF2F8;
    private int selectColor = 0xFF2EBF6E, hoverColor = 0xFFF2D24B, edgeColor = 0xFF20242C, capColor = 0xFFD04848;
    private boolean silhouettes = true;

    private final List<TriItem> opaque = new ArrayList<>();
    private final List<TriItem> translucent = new ArrayList<>();
    private final List<LineItem> lines = new ArrayList<>();
    private final List<LineItem> overlayLines = new ArrayList<>();
    private final List<LineItem> backgroundLines = new ArrayList<>();
    // Splats, volumes a few pixels wide drawn as a shaded square at their centre's depth: (x, y, depth,
    // half size) and (colour, id), packed
    private float[] splatF = new float[4 * 256];
    private int[] splatI = new int[2 * 256];
    private int splats;

    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        final Thread t = new Thread(r, "g4editor-raster");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------

    private static final class TriItem {
        float[] sx, sy, sd, pd;   // screen x, y, depth key, section-plane distance
        int[] col, colBack;       // lit colour, front and back
        int[] tri;
        int nv, ntri;
        // What to prepare it from, until it is
        Mesh mesh;
        Xform world;
        int argb;
        // Arrays of its own, kept when it goes back to the pool: the next frame fills them again
        float[] ownX, ownY, ownD, ownP;
        int[] ownCol, ownBack;
        FloatList edgeX, edgeY, edgeD;

        void own(int n, boolean clip) {
            if (ownX == null || ownX.length < n) {
                final int c = Math.max(16, n);
                ownX = new float[c];
                ownY = new float[c];
                ownD = new float[c];
                ownCol = new int[c];
                ownP = null;
                ownBack = null;
            }
            if (clip && (ownP == null || ownP.length < ownX.length)) {
                ownP = new float[ownX.length];
                ownBack = new int[ownX.length];
            }
        }
        int id, flags;
        float alpha = 1;
        double sortKey;
        float minY, maxY;
        // Feature edges in screen space, with their own depth keys
        float[] ex, ey, ed;
        int ne;
        int ecolor;
        int ewidth = 1;
    }

    private static final class LineItem {
        float[] x, y, d;
        int argb;
        int width;
        boolean depthTest;
        float minY, maxY;
    }

    // ------------------------------------------------------------------

    BufferedImage image() {
        return image;
    }

    private BufferedImage scaled;
    private int[] scaledPixels;

    /**
     * This frame stretched to a larger size, each pixel repeated, on every core: what a view in motion
     * shows. Java2D's interpolated scaling of an image that changes every frame ran in software and took
     * longer than drawing the frame; a bilinear one of our own, a third of the frame on this kind of CPU.
     */
    BufferedImage scaledTo(int width, int height) {
        if (scaled == null || scaled.getWidth() != width || scaled.getHeight() != height) {
            scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            scaledPixels = ((DataBufferInt) scaled.getRaster().getDataBuffer()).getData();
        }
        final int[] src = color, dst = scaledPixels;
        final int sw = w, sh = h;
        final int[] column = new int[width];
        for (int x = 0; x < width; x++) {
            column[x] = Math.min(sw - 1, (int) ((long) x * sw / width));
        }
        final List<Future<?>> fs = new ArrayList<>();
        final int bands = THREADS * 2;
        for (int b = 0; b < bands; b++) {
            final int y0 = b * height / bands, y1 = (b + 1) * height / bands;
            fs.add(POOL.submit(() -> {
                int last = -1;
                for (int y = y0; y < y1; y++) {
                    final int sy = Math.min(sh - 1, (int) ((long) y * sh / height));
                    final int o = y * width;
                    if (sy == last) {
                        System.arraycopy(dst, o - width, dst, o, width); // the row above, again
                        continue;
                    }
                    final int row = sy * sw;
                    for (int x = 0; x < width; x++) {
                        dst[o + x] = src[row + column[x]];
                    }
                    last = sy;
                }
            }));
        }
        join(fs);
        return scaled;
    }

    int width() {
        return w;
    }

    int height() {
        return h;
    }

    /** The id at a pixel, -1 for background. */
    int idAt(int x, int y) {
        if (ids == null || x < 0 || y < 0 || x >= w || y >= h) {
            return -1;
        }
        return ids[y * w + x];
    }

    /** The world point under a pixel, from the depth buffer, or null on background. */
    Vec3 pointAt(int x, int y) {
        if (depth == null || x < 0 || y < 0 || x >= w || y >= h) {
            return null;
        }
        final float d = depth[y * w + x];
        if (d == -Float.MAX_VALUE || ids[y * w + x] < 0) {
            return null;
        }
        final double nx = ((x + 0.5) / w - 0.5) * 2 * aspect, ny = (0.5 - (y + 0.5) / h) * 2;
        if (persp) {
            final double vz = 1.0 / d;
            final double vx = nx * vz / focal, vy = ny * vz / focal;
            return eye.add(right.mul(vx)).add(up.mul(vy)).sub(back.mul(vz));
        }
        final double vz = -d;
        return eye.add(right.mul(nx * halfH)).add(up.mul(ny * halfH)).sub(back.mul(vz));
    }

    void setColors(int bgTop, int bgBottom, int select, int hover, int edge) {
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.selectColor = select;
        this.hoverColor = hover;
        this.edgeColor = edge;
    }

    void setSilhouettes(boolean on) {
        this.silhouettes = on;
    }

    /** Starts a frame; sceneRadius places the orthographic eye far enough behind everything. */
    void begin(Camera cam, int width, int height, double sceneRadius) {
        if (width != w || height != h || image == null) {
            w = Math.max(1, width);
            h = Math.max(1, height);
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            color = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            depth = new float[w * h];
            ids = new int[w * h];
        }
        right = cam.right;
        up = cam.up;
        back = cam.back;
        light1 = back.add(up.mul(0.55)).sub(right.mul(0.35)).unit();
        light2 = back.sub(up.mul(0.4)).add(right.mul(0.7)).unit();
        halfway = light1.add(back).unit();
        persp = cam.perspective;
        aspect = (double) w / h;
        if (persp) {
            eye = cam.eye();
            focal = 1 / Math.tan(cam.fovY / 2);
            near = cam.near();
        } else {
            halfH = cam.halfHeight();
            eye = cam.target.add(back.mul(Math.max(cam.distance, sceneRadius * 4 + 1)));
            near = -Double.MAX_VALUE;
        }
        clip = false;
        opaque.clear();
        translucent.clear();
        lines.clear();
        overlayLines.clear();
        backgroundLines.clear();
        splats = 0;
    }

    /** Cuts away everything on the positive side of n.p = d (world frame). */
    void setSection(Vec3 n, double d) {
        clip = true;
        cnx = n.x();
        cny = n.y();
        cnz = n.z();
        cd = d;
    }

    void clearSection() {
        clip = false;
    }

    /** Queues a mesh placed in the world, coloured, with an id for picking. */
    void mesh(Mesh m, Xform world, int argb, int id, int flags) {
        if (m == null || m.isEmpty()) {
            return;
        }
        TriItem it;
        synchronized (SPARE) {
            it = SPARE.poll();
        }
        if (it == null) {
            it = new TriItem();
        }
        it.mesh = m;
        it.world = world;
        it.argb = argb;
        it.ex = it.ey = it.ed = null;
        it.ne = 0;
        it.id = (flags & FLAG_NO_PICK) != 0 ? -1 : id;
        it.flags = flags;
        final double a = ((argb >>> 24) & 0xFF) / 255.0;
        it.alpha = (float) a;
        if ((flags & FLAG_TRANSPARENT) != 0 || a < 0.99 || (flags & FLAG_GHOST) != 0) {
            if ((flags & FLAG_GHOST) != 0) {
                it.alpha = Math.min(it.alpha, 0.18f);
            }
            translucent.add(it);
        } else {
            opaque.add(it);
        }
        it.ecolor = (flags & FLAG_SELECTED) != 0 ? selectColor : (flags & FLAG_HOVER) != 0 ? hoverColor : edgeColor;
        it.ewidth = (flags & (FLAG_SELECTED | FLAG_HOVER)) != 0 ? 2 : 1;
        pending.add(it);
    }

    /**
     * The items of past frames, with their arrays, for the next ones: a frame of a big detector makes tens of
     * thousands, and making them anew each time fed the garbage collector pauses felt in the whole window.
     * Shared by every raster (one frame is drawn at a time), bounded.
     */
    private static final java.util.ArrayDeque<TriItem> SPARE = new java.util.ArrayDeque<>();
    private static final int MAX_SPARE = 40_000;
    private static final float[] NO_FLOATS = new float[0];
    private static final int[] NO_INTS = new int[0];

    private static void recycle(List<TriItem> items) {
        synchronized (SPARE) {
            for (TriItem it : items) {
                if (SPARE.size() >= MAX_SPARE) {
                    break;
                }
                it.mesh = null;
                it.world = null;
                it.tri = null;
                it.sx = it.sy = it.sd = it.pd = null;
                it.col = it.colBack = null;
                it.ex = it.ey = it.ed = null;
                SPARE.push(it);
            }
        }
    }

    /**
     * Queues a volume too small to be worth its mesh, as a square `pixels` across at a world point: lit as
     * a face turned to the viewer, written to the depth and id buffers like any surface, so it hides what
     * is behind, is picked and gets its silhouette. A detector seen whole is mostly such volumes.
     */
    void splat(double px, double py, double pz, double pixels, int argb, int id, int flags) {
        final double dx = px - eye.x(), dy = py - eye.y(), dz = pz - eye.z();
        final double vx = dx * right.x() + dy * right.y() + dz * right.z();
        final double vy = dx * up.x() + dy * up.y() + dz * up.z();
        final double vz = -(dx * back.x() + dy * back.y() + dz * back.z());
        if (persp && vz < near) {
            return;
        }
        if (splats * 4 + 4 > splatF.length) {
            splatF = Arrays.copyOf(splatF, splatF.length * 2);
            splatI = Arrays.copyOf(splatI, splatI.length * 2);
        }
        final double sx, sy, key;
        if (persp) {
            sx = vx * focal / vz;
            sy = vy * focal / vz;
            key = 1.0 / vz;
        } else {
            sx = vx / halfH;
            sy = vy / halfH;
            key = -vz;
        }
        int base = argb;
        if ((flags & FLAG_SELECTED) != 0) {
            base = mix(argb, selectColor, 0.42);
        } else if ((flags & FLAG_HOVER) != 0) {
            base = mix(argb, hoverColor, 0.38);
        }
        final int k = splats++;
        splatF[k * 4] = (float) ((sx / aspect * 0.5 + 0.5) * w);
        splatF[k * 4 + 1] = (float) ((0.5 - sy * 0.5) * h);
        splatF[k * 4 + 2] = (float) key;
        splatF[k * 4 + 3] = (float) Math.max(0.5, pixels / 2);
        splatI[k * 2] = shade(back.x(), back.y(), back.z(), light1, light2, halfway,
            ((base >> 16) & 0xFF) / 255.0, ((base >> 8) & 0xFF) / 255.0, (base & 0xFF) / 255.0);
        splatI[k * 2 + 1] = (flags & FLAG_NO_PICK) != 0 ? -1 : id;
    }

    private void splats(int y0, int y1) {
        for (int k = 0; k < splats; k++) {
            final float cx = splatF[k * 4], cy = splatF[k * 4 + 1], d = splatF[k * 4 + 2], hs = splatF[k * 4 + 3];
            int ya = (int) Math.floor(cy - hs + 0.5f), yb = Math.max(ya, (int) Math.floor(cy + hs - 0.5f));
            if (yb < y0 || ya >= y1) {
                continue;
            }
            int xa = (int) Math.floor(cx - hs + 0.5f), xb = Math.max(xa, (int) Math.floor(cx + hs - 0.5f));
            xa = Math.max(xa, 0);
            xb = Math.min(xb, w - 1);
            ya = Math.max(ya, y0);
            yb = Math.min(yb, y1 - 1);
            final int c = splatI[k * 2], id = splatI[k * 2 + 1];
            for (int y = ya; y <= yb; y++) {
                for (int o = y * w + xa, e = y * w + xb; o <= e; o++) {
                    if (d > depth[o]) {
                        color[o] = c;
                        depth[o] = d;
                        ids[o] = id;
                    }
                }
            }
        }
    }

    /** Queues world-space segments (xyz xyz ...). */
    void lines(float[] seg, Xform world, int argb, int width, boolean depthTest) {
        if (seg == null || seg.length < 6) {
            return;
        }
        final LineItem li = new LineItem();
        li.argb = argb;
        li.width = width;
        li.depthTest = depthTest;
        final int n = seg.length / 3;
        li.x = new float[n];
        li.y = new float[n];
        li.d = new float[n];
        int k = 0;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 5 < seg.length; i += 6) {
            Vec3 p0 = new Vec3(seg[i], seg[i + 1], seg[i + 2]);
            Vec3 p1 = new Vec3(seg[i + 3], seg[i + 4], seg[i + 5]);
            if (world != null) {
                p0 = world.apply(p0);
                p1 = world.apply(p1);
            }
            final double[] a = view(p0), b = view(p1);
            if (persp) {
                if (a[2] < near && b[2] < near) {
                    continue;
                }
                if (a[2] < near || b[2] < near) {
                    final double t = (near - a[2]) / (b[2] - a[2]);
                    final double[] c = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, near};
                    if (a[2] < near) {
                        System.arraycopy(c, 0, a, 0, 3);
                    } else {
                        System.arraycopy(c, 0, b, 0, 3);
                    }
                }
            }
            final float[] sa = screen(a), sb = screen(b);
            li.x[k] = sa[0];
            li.y[k] = sa[1];
            li.d[k] = sa[2];
            li.x[k + 1] = sb[0];
            li.y[k + 1] = sb[1];
            li.d[k + 1] = sb[2];
            minY = Math.min(minY, Math.min(sa[1], sb[1]));
            maxY = Math.max(maxY, Math.max(sa[1], sb[1]));
            k += 2;
        }
        li.x = Arrays.copyOf(li.x, k);
        li.y = Arrays.copyOf(li.y, k);
        li.d = Arrays.copyOf(li.d, k);
        li.minY = minY - width;
        li.maxY = maxY + width;
        (depthTest ? lines : overlayLines).add(li);
    }

    /** Lines under everything: the grid and the world's frame, which volumes always cover. */
    void backgroundLines(float[] seg, Xform world, int argb, int width) {
        final int before = lines.size();
        lines(seg, world, argb, width, true);
        if (lines.size() > before) {
            backgroundLines.add(lines.remove(lines.size() - 1));
        }
    }

    private final List<TriItem> pending = new ArrayList<>();

    private double[] view(Vec3 p) {
        final double dx = p.x() - eye.x(), dy = p.y() - eye.y(), dz = p.z() - eye.z();
        return new double[] {
            dx * right.x() + dy * right.y() + dz * right.z(),
            dx * up.x() + dy * up.y() + dz * up.z(),
            -(dx * back.x() + dy * back.y() + dz * back.z())};
    }

    private float[] screen(double[] v) {
        double sx, sy, key;
        if (persp) {
            sx = v[0] * focal / v[2];
            sy = v[1] * focal / v[2];
            key = 1.0 / v[2];
        } else {
            sx = v[0] / halfH;
            sy = v[1] / halfH;
            key = -v[2];
        }
        return new float[] {(float) ((sx / aspect * 0.5 + 0.5) * w), (float) ((0.5 - sy * 0.5) * h), (float) key};
    }

    // ------------------------------------------------------------------
    // Vertex stage
    // ------------------------------------------------------------------

    /** A vertex stage thread's working arrays, kept from item to item: a frame prepares tens of thousands. */
    private static final class Scratch {
        double[] vx = new double[0], vy = vx, vz = vx;
        int[] col = new int[0], colB = col;
        float[] pd = new float[0];
        final double[] seg = new double[6];

        void ensure(int n) {
            if (vx.length < n) {
                final int c = Math.max(n, vx.length * 2);
                vx = new double[c];
                vy = new double[c];
                vz = new double[c];
                col = new int[c];
                colB = new int[c];
                pd = new float[c];
            }
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private void prepare(TriItem it, Mesh m, Xform world, int argb) {
        final int nv = m.vertexCount();
        final double[] r = world.rotation();
        final Vec3 t = world.translation();
        final float[] P = m.pos, N = m.nrm;
        final Vec3 l1 = light1, l2 = light2, hv = halfway;

        int base = argb;
        if ((it.flags & FLAG_SELECTED) != 0) {
            base = mix(argb, selectColor, 0.42);
        } else if ((it.flags & FLAG_HOVER) != 0) {
            base = mix(argb, hoverColor, 0.38);
        }
        final double br = ((base >> 16) & 0xFF) / 255.0, bg = ((base >> 8) & 0xFF) / 255.0, bb = (base & 0xFF) / 255.0;

        final Scratch sc = SCRATCH.get();
        if ((it.flags & FLAG_WIRE) != 0) {
            // A wireframe is its edges: its triangles are never filled, so never projected either
            it.sx = it.sy = it.sd = NO_FLOATS;
            it.col = NO_INTS;
            it.tri = NO_INTS;
            it.pd = null;
            it.colBack = null;
            it.ntri = 0;
            it.nv = 0;
            it.sortKey = sortKeyOf(m, r, t);
            featureEdges(it, m, r, t, sc);
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < it.ne; i++) {
                minY = Math.min(minY, it.ey[i]);
                maxY = Math.max(maxY, it.ey[i]);
            }
            it.minY = minY - it.ewidth;
            it.maxY = maxY + it.ewidth;
            return;
        }
        sc.ensure(nv);
        final double[] vx = sc.vx, vy = sc.vy, vz = sc.vz;
        final int[] col = sc.col, colB = clip ? sc.colB : null;
        final float[] pd = clip ? sc.pd : null;
        // Mesh to view space in one matrix (the placement, then the camera), and the lights and the section
        // plane taken into the mesh's own frame, so a normal is used as stored: the placement is rigid
        // (a rotation, or a reflection), its inverse its transpose
        final double ex = t.x() - eye.x(), ey = t.y() - eye.y(), ez = t.z() - eye.z();
        final double a00 = right.x() * r[0] + right.y() * r[3] + right.z() * r[6];
        final double a01 = right.x() * r[1] + right.y() * r[4] + right.z() * r[7];
        final double a02 = right.x() * r[2] + right.y() * r[5] + right.z() * r[8];
        final double a03 = right.x() * ex + right.y() * ey + right.z() * ez;
        final double a10 = up.x() * r[0] + up.y() * r[3] + up.z() * r[6];
        final double a11 = up.x() * r[1] + up.y() * r[4] + up.z() * r[7];
        final double a12 = up.x() * r[2] + up.y() * r[5] + up.z() * r[8];
        final double a13 = up.x() * ex + up.y() * ey + up.z() * ez;
        final double a20 = -(back.x() * r[0] + back.y() * r[3] + back.z() * r[6]);
        final double a21 = -(back.x() * r[1] + back.y() * r[4] + back.z() * r[7]);
        final double a22 = -(back.x() * r[2] + back.y() * r[5] + back.z() * r[8]);
        final double a23 = -(back.x() * ex + back.y() * ey + back.z() * ez);
        final Vec3 k1 = local(l1, r), k2 = local(l2, r), kh = local(hv, r);
        final double p0 = cnx * r[0] + cny * r[3] + cnz * r[6], p1 = cnx * r[1] + cny * r[4] + cnz * r[7];
        final double p2 = cnx * r[2] + cny * r[5] + cnz * r[8], p3 = cnx * t.x() + cny * t.y() + cnz * t.z() - cd;
        final int cap = clip ? capOf(base) : 0;
        double nearest = Double.MAX_VALUE;
        // A flat face's vertices come one after the other with one normal: shaded once
        float lnx = Float.NaN, lny = Float.NaN, lnz = Float.NaN;
        int shaded = 0;
        for (int i = 0; i < nv; i++) {
            final double x = P[i * 3], y = P[i * 3 + 1], z = P[i * 3 + 2];
            vx[i] = a00 * x + a01 * y + a02 * z + a03;
            vy[i] = a10 * x + a11 * y + a12 * z + a13;
            final double zv = a20 * x + a21 * y + a22 * z + a23;
            vz[i] = zv;
            nearest = Math.min(nearest, zv);
            final float nx = N[i * 3], ny = N[i * 3 + 1], nz = N[i * 3 + 2];
            if (nx != lnx || ny != lny || nz != lnz) {
                shaded = shade(nx, ny, nz, k1, k2, kh, br, bg, bb);
                lnx = nx;
                lny = ny;
                lnz = nz;
            }
            col[i] = shaded;
            if (clip) {
                colB[i] = cap;
                pd[i] = (float) (p0 * x + p1 * y + p2 * z + p3);
            }
        }
        // a mirrored placement (G4ReflectionFactory) turns the triangles' winding: turn it back
        final boolean mirrored = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6])
            + r[2] * (r[3] * r[7] - r[4] * r[6]) < 0;
        if (nearest >= near) {
            // Nothing to clip, the common case: projected straight into arrays of the mesh's size, and the
            // mesh's own triangles drawn as they are
            it.own(nv, clip);
            final float[] sx = it.ownX, sy = it.ownY, sd = it.ownD;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < nv; i++) {
                if (persp) {
                    final double z = Math.max(vz[i], 1e-9);
                    sx[i] = (float) ((vx[i] * focal / z / aspect * 0.5 + 0.5) * w);
                    sy[i] = (float) ((0.5 - vy[i] * focal / z * 0.5) * h);
                    sd[i] = (float) (1.0 / z);
                } else {
                    sx[i] = (float) ((vx[i] / halfH / aspect * 0.5 + 0.5) * w);
                    sy[i] = (float) ((0.5 - vy[i] / halfH * 0.5) * h);
                    sd[i] = (float) -vz[i];
                }
                minY = Math.min(minY, sy[i]);
                maxY = Math.max(maxY, sy[i]);
            }
            int[] tri = m.tri;
            if (mirrored) {
                tri = new int[m.tri.length];
                for (int k = 0; k < tri.length; k += 3) {
                    tri[k] = m.tri[k];
                    tri[k + 1] = m.tri[k + 2];
                    tri[k + 2] = m.tri[k + 1];
                }
            }
            double cz = 0;
            for (int k = 0; k < m.tri.length; k += 3) {
                cz += vz[m.tri[k]];
            }
            it.sx = sx;
            it.sy = sy;
            it.sd = sd;
            System.arraycopy(col, 0, it.ownCol, 0, nv);
            it.col = it.ownCol;
            if (clip) {
                System.arraycopy(pd, 0, it.ownP, 0, nv);
                System.arraycopy(colB, 0, it.ownBack, 0, nv);
            }
            it.pd = clip ? it.ownP : null;
            it.colBack = clip ? it.ownBack : null;
            it.tri = tri;
            it.ntri = tri.length;
            it.nv = nv;
            it.sortKey = m.tri.length > 0 ? cz / (m.tri.length / 3.0) : 0;
            it.minY = minY;
            it.maxY = maxY;
            featureEdges(it, m, r, t, sc);
            return;
        }
        // Near clipping (perspective), then projection
        final FloatList sx = new FloatList(nv), sy = new FloatList(nv), sd = new FloatList(nv), sp = new FloatList(nv);
        final IntList cl = new IntList(nv), clB = new IntList(nv), tris = new IntList(m.tri.length);
        for (int i = 0; i < nv; i++) {
            addVertex(sx, sy, sd, vx[i], vy[i], vz[i]);
            sp.add(clip ? pd[i] : 0);
            cl.add(col[i]);
            clB.add(clip ? colB[i] : 0);
        }
        double cz = 0;
        for (int k = 0; k < m.tri.length; k += 3) {
            final int a = m.tri[k], b = m.tri[mirrored ? k + 2 : k + 1], c = m.tri[mirrored ? k + 1 : k + 2];
            final boolean ia = vz[a] >= near, ib = vz[b] >= near, ic = vz[c] >= near;
            if (ia && ib && ic) {
                tris.add(a);
                tris.add(b);
                tris.add(c);
            } else if (ia || ib || ic) {
                // Sutherland-Hodgman against the near plane
                final int[] idx = {a, b, c};
                final List<double[]> poly = new ArrayList<>(4);
                for (int e = 0; e < 3; e++) {
                    final int i0 = idx[e], i1 = idx[(e + 1) % 3];
                    final boolean in0 = vz[i0] >= near, in1 = vz[i1] >= near;
                    if (in0) {
                        poly.add(new double[] {i0, -1, 0});
                    }
                    if (in0 != in1) {
                        final double tt = (near - vz[i0]) / (vz[i1] - vz[i0]);
                        poly.add(new double[] {i0, i1, tt});
                    }
                }
                final int[] newIdx = new int[poly.size()];
                for (int q = 0; q < poly.size(); q++) {
                    final double[] pv = poly.get(q);
                    if (pv[1] < 0) {
                        newIdx[q] = (int) pv[0];
                    } else {
                        final int i0 = (int) pv[0], i1 = (int) pv[1];
                        final double tt = pv[2];
                        newIdx[q] = sx.size();
                        addVertex(sx, sy, sd, vx[i0] + (vx[i1] - vx[i0]) * tt, vy[i0] + (vy[i1] - vy[i0]) * tt, near);
                        sp.add(clip ? (float) (pd[i0] + (pd[i1] - pd[i0]) * tt) : 0);
                        cl.add(mix(col[i0], col[i1], tt));
                        clB.add(clip ? colB[i0] : 0);
                    }
                }
                for (int q = 1; q + 1 < newIdx.length; q++) {
                    tris.add(newIdx[0]);
                    tris.add(newIdx[q]);
                    tris.add(newIdx[q + 1]);
                }
            }
            cz += vz[a];
        }
        // The lists' own arrays are kept, with their counts, rather than copied to their exact sizes
        it.sx = sx.a;
        it.sy = sy.a;
        it.sd = sd.a;
        it.pd = clip ? sp.a : null;
        it.col = cl.a;
        it.colBack = clip ? clB.a : null;
        it.tri = tris.a;
        it.ntri = tris.n;
        it.nv = sx.n;
        it.sortKey = m.tri.length > 0 ? cz / (m.tri.length / 3.0) : 0;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < it.nv; i++) {
            minY = Math.min(minY, it.sy[i]);
            maxY = Math.max(maxY, it.sy[i]);
        }
        it.minY = minY;
        it.maxY = maxY;
        featureEdges(it, m, r, t, sc);
    }

    /**
     * The depth a wireframe is sorted by among the translucent items: its placement's origin, the centre of
     * a Geant4 solid. Only its lines are drawn, so nearer or farther by a hair changes nothing that shows.
     */
    private double sortKeyOf(Mesh m, double[] r, Vec3 t) {
        return -((t.x() - eye.x()) * back.x() + (t.y() - eye.y()) * back.y() + (t.z() - eye.z()) * back.z());
    }

    /** A direction of the world in the frame of a mesh placed by the rigid rotation (or reflection) r. */
    private static Vec3 local(Vec3 d, double[] r) {
        return new Vec3(r[0] * d.x() + r[3] * d.y() + r[6] * d.z(),
                        r[1] * d.x() + r[4] * d.y() + r[7] * d.z(),
                        r[2] * d.x() + r[5] * d.y() + r[8] * d.z());
    }

    /** The item's feature edges in screen space, unless it is drawn without. */
    private void featureEdges(TriItem it, Mesh m, double[] r, Vec3 t, Scratch sc) {
        if ((it.flags & FLAG_NO_EDGES) == 0 || (it.flags & (FLAG_SELECTED | FLAG_HOVER)) != 0) {
            final float[] fe = m.featureEdges();
            if (it.edgeX == null) {
                it.edgeX = new FloatList(fe.length / 3);
                it.edgeY = new FloatList(fe.length / 3);
                it.edgeD = new FloatList(fe.length / 3);
            }
            final FloatList ex = it.edgeX, ey = it.edgeY, ed = it.edgeD;
            ex.n = 0;
            ey.n = 0;
            ed.n = 0;
            final double[] s = sc.seg; // the two ends in view space: s[0..2], s[3..5]
            for (int i = 0; i + 5 < fe.length; i += 6) {
                viewOf(fe, i, r, t, s, 0);
                viewOf(fe, i + 3, r, t, s, 3);
                if (persp) {
                    if (s[2] < near && s[5] < near) {
                        continue;
                    }
                    if (s[2] < near || s[5] < near) {
                        final double tt = (near - s[2]) / (s[5] - s[2]);
                        final int o = s[2] < near ? 0 : 3;
                        s[o] = s[0] + (s[3] - s[0]) * tt;
                        s[o + 1] = s[1] + (s[4] - s[1]) * tt;
                        s[o + 2] = near;
                    }
                }
                if (clip) {
                    // Drop edges on the cut-away side
                    if (planeOfView(s[0], s[1], s[2]) > 0 && planeOfView(s[3], s[4], s[5]) > 0) {
                        continue;
                    }
                }
                for (int o = 0; o <= 3; o += 3) {
                    double px, py, key;
                    if (persp) {
                        px = s[o] * focal / s[o + 2];
                        py = s[o + 1] * focal / s[o + 2];
                        key = 1.0 / s[o + 2];
                    } else {
                        px = s[o] / halfH;
                        py = s[o + 1] / halfH;
                        key = -s[o + 2];
                    }
                    ex.add((float) ((px / aspect * 0.5 + 0.5) * w));
                    ey.add((float) ((0.5 - py * 0.5) * h));
                    ed.add((float) key);
                }
            }
            it.ex = ex.a;
            it.ey = ey.a;
            it.ed = ed.a;
            it.ne = ex.n;
        }
    }

    /** Signed distance to the section plane of a view-space point (eye + right x + up y - back z). */
    private double planeOfView(double vx, double vy, double vz) {
        final double px = eye.x() + right.x() * vx + up.x() * vy - back.x() * vz;
        final double py = eye.y() + right.y() * vx + up.y() * vy - back.y() * vz;
        final double pz = eye.z() + right.z() * vx + up.z() * vy - back.z() * vz;
        return cnx * px + cny * py + cnz * pz - cd;
    }

    /** A mesh point placed by (r, t), in view space, written at out[o..o+2]. */
    private void viewOf(float[] a, int i, double[] r, Vec3 t, double[] out, int o) {
        final double x = a[i], y = a[i + 1], z = a[i + 2];
        final double wx = r[0] * x + r[1] * y + r[2] * z + t.x();
        final double wy = r[3] * x + r[4] * y + r[5] * z + t.y();
        final double wz = r[6] * x + r[7] * y + r[8] * z + t.z();
        final double dx = wx - eye.x(), dy = wy - eye.y(), dz = wz - eye.z();
        out[o] = dx * right.x() + dy * right.y() + dz * right.z();
        out[o + 1] = dx * up.x() + dy * up.y() + dz * up.z();
        out[o + 2] = -(dx * back.x() + dy * back.y() + dz * back.z());
    }

    private void addVertex(FloatList sx, FloatList sy, FloatList sd, double vx, double vy, double vz) {
        if (persp) {
            final double z = Math.max(vz, 1e-9);
            sx.add((float) ((vx * focal / z / aspect * 0.5 + 0.5) * w));
            sy.add((float) ((0.5 - vy * focal / z * 0.5) * h));
            sd.add((float) (1.0 / z));
        } else {
            sx.add((float) ((vx / halfH / aspect * 0.5 + 0.5) * w));
            sy.add((float) ((0.5 - vy / halfH * 0.5) * h));
            sd.add((float) -vz);
        }
    }

    private static int shade(double nx, double ny, double nz, Vec3 l1, Vec3 l2, Vec3 hv, double r, double g, double b) {
        final double d1 = Math.max(0, nx * l1.x() + ny * l1.y() + nz * l1.z());
        final double d2 = Math.max(0, nx * l2.x() + ny * l2.y() + nz * l2.z());
        final double sh = Math.max(0, nx * hv.x() + ny * hv.y() + nz * hv.z());
        // sh^48 by squaring: Math.pow, once per vertex of every item of every frame, was a tenth of the frame
        final double s2 = sh * sh, s4 = s2 * s2, s8 = s4 * s4, s16 = s8 * s8;
        final double sp = s16 * s16 * s16 * 0.32;
        final double k = 0.30 + 0.62 * d1 + 0.18 * d2;
        final int ri = clamp((int) ((r * k + sp) * 255));
        final int gi = clamp((int) ((g * k + sp) * 255));
        final int bi = clamp((int) ((b * k + sp) * 255));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    /** The colour of a cut face: the material's own, hatched toward the cap colour and darkened. */
    private int capOf(int base) {
        final int m = mix(base | 0xFF000000, capColor, 0.35);
        return mix(m, 0xFF000000, 0.18) | 0xFF000000;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    static int mix(int a, int b, double t) {
        final int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        final int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        final int aa = (a >>> 24) & 0xFF;
        return (aa << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
            | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------

    /** Renders everything queued since begin(). */
    /** -Dg4editor.raster.stats=true prints each frame's stage times and counts. */
    private static final boolean STATS = Boolean.getBoolean("g4editor.raster.stats");
    private final java.util.concurrent.atomic.AtomicLong busy = new java.util.concurrent.atomic.AtomicLong();

    void end() {
        final long t0 = System.nanoTime();
        // Vertex stage, in parallel per item
        final List<TriItem> work = new ArrayList<>(pending);
        pending.clear();
        final List<Future<?>> fs = new ArrayList<>();
        final int chunk = Math.max(1, work.size() / (THREADS * 4));
        for (int s = 0; s < work.size(); s += chunk) {
            final int from = s, to = Math.min(work.size(), s + chunk);
            fs.add(POOL.submit(() -> {
                final long c0 = STATS ? System.nanoTime() : 0;
                for (int i = from; i < to; i++) {
                    final TriItem it = work.get(i);
                    prepare(it, it.mesh, it.world, it.argb);
                }
                if (STATS) {
                    busy.addAndGet(System.nanoTime() - c0);
                }
            }));
        }
        join(fs);
        final long t1 = System.nanoTime();
        // Translucent items back to front
        translucent.sort((a, b) -> Double.compare(b.sortKey, a.sortKey));

        // Raster stage, one band per task
        final int bands = Math.max(1, Math.min(h, THREADS * 3));
        fs.clear();
        // Two passes: the silhouettes read the rows next to a band, which belong to its
        // neighbour, so every band's surfaces must be finished before any outline is drawn.
        for (int bnd = 0; bnd < bands; bnd++) {
            final int y0 = bnd * h / bands, y1 = (bnd + 1) * h / bands;
            fs.add(POOL.submit(() -> surfaces(y0, y1)));
        }
        join(fs);
        final long t2 = System.nanoTime();
        fs.clear();
        for (int bnd = 0; bnd < bands; bnd++) {
            final int y0 = bnd * h / bands, y1 = (bnd + 1) * h / bands;
            fs.add(POOL.submit(() -> lines(y0, y1)));
        }
        join(fs);
        if (STATS) {
            long to = 0, tt = 0, eo = 0, et = 0, verts = 0;
            for (TriItem it : opaque) {
                verts += it.nv;
                to += it.ntri / 3;
                eo += it.ne / 2;
            }
            for (TriItem it : translucent) {
                verts += it.nv;
                tt += it.ntri / 3;
                et += it.ne / 2;
            }
            System.out.printf(java.util.Locale.ROOT, "raster %dx%d: vertex %.1f ms (busy %.1f ms over %d threads, %d verts), surfaces %.1f ms, lines+glass %.1f ms;"
                + " opaque %d items %d tris %d edges, glass %d items %d tris %d edges%n", w, h, (t1 - t0) / 1e6,
                busy.getAndSet(0) / 1e6, THREADS, verts,
                (t2 - t1) / 1e6, (System.nanoTime() - t2) / 1e6, opaque.size(), to, eo, translucent.size(), tt, et);
        }
        // The frame is in the buffers now; its items go back to the pool, holding no mesh, until the next frame
        recycle(opaque);
        recycle(translucent);
        opaque.clear();
        translucent.clear();
        lines.clear();
        overlayLines.clear();
        backgroundLines.clear();
        splats = 0;
    }

    private static void join(List<Future<?>> fs) {
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (Exception e) {
                // A failed band leaves its pixels as they were; the next frame redraws them.
            }
        }
    }

    private void surfaces(int y0, int y1) {
        // Background gradient
        for (int y = y0; y < y1; y++) {
            final int c = mix(bgTop, bgBottom, (double) y / Math.max(1, h - 1)) | 0xFF000000;
            final int o = y * w;
            Arrays.fill(color, o, o + w, c);
            Arrays.fill(depth, o, o + w, -Float.MAX_VALUE);
            Arrays.fill(ids, o, o + w, -1);
        }
        for (LineItem li : backgroundLines) {
            if (li.maxY >= y0 && li.minY < y1) {
                edges(li.x, li.y, li.d, li.x.length, li.argb, li.width, y0, y1, false, ((li.argb >>> 24) & 0xFF) / 255f);
            }
        }
        for (TriItem it : opaque) {
            if (it.maxY < y0 || it.minY >= y1) {
                continue;
            }
            if ((it.flags & FLAG_WIRE) == 0) {
                triangles(it, y0, y1, false);
            }
        }
        splats(y0, y1);
    }

    private void lines(int y0, int y1) {
        if (silhouettes) {
            silhouette(y0, y1);
        }
        for (TriItem it : opaque) {
            if (it.ex != null && it.maxY >= y0 && it.minY < y1) {
                edges(it.ex, it.ey, it.ed, it.ne, it.ecolor, it.ewidth, y0, y1, true, 1f);
            }
        }
        for (LineItem li : lines) {
            if (li.maxY >= y0 && li.minY < y1) {
                edges(li.x, li.y, li.d, li.x.length, li.argb, li.width, y0, y1, true, ((li.argb >>> 24) & 0xFF) / 255f);
            }
        }
        for (TriItem it : translucent) {
            if (it.maxY < y0 || it.minY >= y1) {
                continue;
            }
            if ((it.flags & FLAG_WIRE) == 0) {
                triangles(it, y0, y1, true);
            }
            if (it.ex != null) {
                edges(it.ex, it.ey, it.ed, it.ne, it.ecolor, it.ewidth, y0, y1, true,
                    (it.flags & (FLAG_SELECTED | FLAG_HOVER)) != 0 ? 1f : Math.max(0.25f, it.alpha));
            }
        }
        for (LineItem li : overlayLines) {
            if (li.maxY >= y0 && li.minY < y1) {
                edges(li.x, li.y, li.d, li.x.length, li.argb, li.width, y0, y1, false, ((li.argb >>> 24) & 0xFF) / 255f);
            }
        }
    }

    private void triangles(TriItem it, int y0, int y1, boolean blend) {
        final float[] X = it.sx, Y = it.sy, D = it.sd, PD = it.pd;
        final int[] C = it.col, T = it.tri;
        final boolean twoSided = clip || blend;
        final float alpha = it.alpha;
        final int id = it.id;
        for (int k = 0; k < it.ntri; k += 3) {
            final int a = T[k], b = T[k + 1], c = T[k + 2];
            final float ax = X[a], ay = Y[a], bx = X[b], by = Y[b], cx = X[c], cy = Y[c];
            final float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            boolean front = area < 0; // screen y points down
            if (!front && !twoSided) {
                continue;
            }
            if (area == 0) {
                continue;
            }
            int minX = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
            int maxX = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
            int minY = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
            int maxY = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
            if (maxX < 0 || minX >= w || maxY < y0 || minY >= y1) {
                continue;
            }
            minX = Math.max(minX, 0);
            maxX = Math.min(maxX, w - 1);
            minY = Math.max(minY, y0);
            maxY = Math.min(maxY, y1 - 1);
            final float inv = 1f / area;
            final float da = D[a], db = D[b], dc = D[c];
            final int ca = front || !clip ? C[a] : it.colBack[a];
            final int cb = front || !clip ? C[b] : it.colBack[b];
            final int cc = front || !clip ? C[c] : it.colBack[c];
            final float ra = (ca >> 16) & 0xFF, ga = (ca >> 8) & 0xFF, ba = ca & 0xFF;
            final float rb = (cb >> 16) & 0xFF, gb = (cb >> 8) & 0xFF, bbb = cb & 0xFF;
            final float rc = (cc >> 16) & 0xFF, gc = (cc >> 8) & 0xFF, bc = cc & 0xFF;
            final float pa = PD == null ? 0 : PD[a], pb = PD == null ? 0 : PD[b], pc = PD == null ? 0 : PD[c];
            // Along a row each barycentric is linear in x: w(x) = k + s x. The span where all three
            // pass the test is solved for, a pixel wider each side, so a large triangle only visits its
            // own pixels rather than its whole bounding box; the per-pixel test below still decides
            final double s0 = (by - cy) * (double) inv, s1 = (cy - ay) * (double) inv, s2 = -(s0 + s1);
            final boolean wide = maxX - minX > 24; // a small triangle costs less visited whole than solved for
            for (int y = minY; y <= maxY; y++) {
                final float py = y + 0.5f;
                final int row = y * w;
                double lo = minX, hi = maxX;
                final double k0 = wide ? (bx * (double) (cy - py) - (by - py) * (double) cx) * inv : 0;
                final double k1 = wide ? (cx * (double) (ay - py) - (cy - py) * (double) ax) * inv : 0;
                final double k2 = 1 - k0 - k1;
                for (int e = 0; wide && e < 3; e++) {
                    final double ke = e == 0 ? k0 : e == 1 ? k1 : k2, se = e == 0 ? s0 : e == 1 ? s1 : s2;
                    if (Math.abs(se) < 1e-12) {
                        if (ke < -1e-4) {
                            hi = lo - 1; // the whole row is outside this edge
                        }
                        continue;
                    }
                    // pixel x has its centre at x + 0.5
                    final double edge = (-1e-5 - ke) / se - 0.5;
                    if (se > 0) {
                        lo = Math.max(lo, Math.floor(edge) - 1);
                    } else {
                        hi = Math.min(hi, Math.ceil(edge) + 1);
                    }
                }
                final int x0 = (int) Math.max(minX, lo), x1 = (int) Math.min(maxX, hi);
                for (int x = x0; x <= x1; x++) {
                    final float px = x + 0.5f;
                    float w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv;
                    float w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv;
                    float w2 = 1 - w0 - w1;
                    if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) {
                        continue;
                    }
                    final float d = w0 * da + w1 * db + w2 * dc;
                    final int o = row + x;
                    if (d <= depth[o]) {
                        continue;
                    }
                    if (PD != null) {
                        float p;
                        if (persp) {
                            p = (w0 * pa * da + w1 * pb * db + w2 * pc * dc) / d;
                        } else {
                            p = w0 * pa + w1 * pb + w2 * pc;
                        }
                        if (p > 0) {
                            continue;
                        }
                    }
                    int r = (int) (w0 * ra + w1 * rb + w2 * rc);
                    int g = (int) (w0 * ga + w1 * gb + w2 * gc);
                    int bl = (int) (w0 * ba + w1 * bbb + w2 * bc);
                    if (blend) {
                        final int dst = color[o];
                        r = (int) (r * alpha + ((dst >> 16) & 0xFF) * (1 - alpha));
                        g = (int) (g * alpha + ((dst >> 8) & 0xFF) * (1 - alpha));
                        bl = (int) (bl * alpha + (dst & 0xFF) * (1 - alpha));
                        color[o] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
                        if (id >= 0 && ids[o] < 0) {
                            ids[o] = id; // translucent things stay pickable over the background
                        }
                    } else {
                        color[o] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
                        depth[o] = d;
                        ids[o] = id;
                    }
                }
            }
        }
    }

    /** Darkens pixels where the id changes: outlines curved silhouettes that have no feature edge. */
    private void silhouette(int y0, int y1) {
        for (int y = Math.max(y0, 1); y < Math.min(y1, h - 1); y++) {
            final int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                final int o = row + x;
                final int id = ids[o];
                if (id < 0) {
                    continue;
                }
                final int r = ids[o + 1], l = ids[o - 1], dn = ids[o + w], upId = ids[o - w];
                if (r != id || l != id || dn != id || upId != id) {
                    // Only the nearer side draws the line, so it stays one pixel wide
                    final float d = depth[o];
                    if ((r != id && depth[o + 1] < d) || (l != id && depth[o - 1] < d)
                        || (dn != id && depth[o + w] < d) || (upId != id && depth[o - w] < d)) {
                        color[o] = mix(color[o] | 0xFF000000, edgeColor, 0.75) | 0xFF000000;
                    }
                }
            }
        }
    }

    private void edges(float[] X, float[] Y, float[] D, int count, int argb, int width, int y0, int y1, boolean depthTest,
                       float alpha) {
        final float bias = persp ? 1.0006f : 1f;
        final double[] span = new double[2];
        for (int k = 0; k + 1 < count; k += 2) {
            final float x0 = X[k], yy0 = Y[k], x1 = X[k + 1], yy1 = Y[k + 1];
            if ((yy0 < y0 - width && yy1 < y0 - width) || (yy0 >= y1 + width && yy1 >= y1 + width)) {
                continue;
            }
            final float dx = x1 - x0, dy = yy1 - yy0;
            // Only the part of the segment over this band is stepped along: zoomed in, an envelope's edge
            // runs thousands of pixels off screen, and every band would walk all of it
            span[0] = 0;
            span[1] = 1;
            if (!clip(-dx, x0 + width, span) || !clip(dx, w + width - x0, span)
                || !clip(-dy, yy0 - (y0 - width), span) || !clip(dy, y1 + width - yy0, span)) {
                continue;
            }
            // The steps are those of the whole segment, so the pixels are the same as when all of it was walked
            final int steps = (int) Math.min(20000, Math.max(Math.abs(dx), Math.abs(dy))) + 1;
            final int sFrom = Math.max(0, (int) Math.floor(span[0] * steps) - 1);
            final int sTo = Math.min(steps, (int) Math.ceil(span[1] * steps) + 1);
            final float d0 = D[k], d1 = D[k + 1];
            // Depth offset toward the eye, so edges win against their own faces
            for (int s = sFrom; s <= sTo; s++) {
                final float t = (float) s / steps;
                final int x = (int) (x0 + dx * t), y = (int) (yy0 + dy * t);
                float d = d0 + (d1 - d0) * t;
                d = persp ? d * bias : d + edgeBiasOrtho;
                plot(x, y, d, argb, depthTest, alpha, y0, y1);
                if (width > 1) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        plot(x, y + 1, d, argb, depthTest, alpha, y0, y1);
                    } else {
                        plot(x + 1, y, d, argb, depthTest, alpha, y0, y1);
                    }
                }
            }
        }
    }

    /** One side of a Liang-Barsky clip: narrows the parameter span [t0, t1] to where p t <= q; false when empty. */
    private static boolean clip(double p, double q, double[] t) {
        if (p == 0) {
            return q >= 0;
        }
        final double r = q / p;
        if (p < 0) {
            if (r > t[1]) {
                return false;
            }
            t[0] = Math.max(t[0], r);
        } else {
            if (r < t[0]) {
                return false;
            }
            t[1] = Math.min(t[1], r);
        }
        return true;
    }

    private float edgeBiasOrtho = 0.5f;

    /** Ortho edge depth bias, in mm: proportional to the view size. */
    void setOrthoBias(float mm) {
        edgeBiasOrtho = mm;
    }

    private void plot(int x, int y, float d, int argb, boolean depthTest, float alpha, int y0, int y1) {
        if (x < 0 || x >= w || y < y0 || y >= y1) {
            return;
        }
        final int o = y * w + x;
        if (depthTest && d < depth[o]) {
            return;
        }
        if (alpha >= 0.999f) {
            color[o] = argb | 0xFF000000;
        } else {
            final int dst = color[o];
            final int r = (int) (((argb >> 16) & 0xFF) * alpha + ((dst >> 16) & 0xFF) * (1 - alpha));
            final int g = (int) (((argb >> 8) & 0xFF) * alpha + ((dst >> 8) & 0xFF) * (1 - alpha));
            final int b = (int) ((argb & 0xFF) * alpha + (dst & 0xFF) * (1 - alpha));
            color[o] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    // ------------------------------------------------------------------

    private static final class FloatList {
        float[] a;
        int n;

        FloatList(int cap) {
            a = new float[Math.max(4, cap)];
        }

        void add(float v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        int size() {
            return n;
        }

        float[] array() {
            return Arrays.copyOf(a, n);
        }
    }

    private static final class IntList {
        int[] a;
        int n;

        IntList(int cap) {
            a = new int[Math.max(4, cap)];
        }

        void add(int v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        int[] array() {
            return Arrays.copyOf(a, n);
        }
    }
}
