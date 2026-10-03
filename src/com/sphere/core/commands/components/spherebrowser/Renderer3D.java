package com.sphere.components.spherebrowser;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.theme.ThemePaletteDark;
import com.sphere.theme.ThemePaletteLight;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Sphere's own 3D engine: a z-buffer drawn by the processor, no OpenGL and no
 * library, so that it runs wherever Java does, a remote desktop and WSL
 * included.
 *
 * The data box is mapped onto a cube, stretched along each axis as the user
 * asks, turned by a quaternion and seen through a perspective or an
 * orthographic camera. Triangles are filled in horizontal bands, one band per
 * task, so every core works on the picture; opaque faces are depth tested,
 * translucent ones blended from the back to the front. A surface is lit by a
 * key lamp, a fill from the eye and a specular highlight; points are drawn as
 * little lit spheres; an outline darkens the jumps of depth so that facets
 * and silhouettes read as in an engraving. Cutting planes open the box along
 * each axis, and two eyes a few degrees apart make a red-cyan anaglyph.
 */
public final class Renderer3D {

    /* ------------------------------------------------------------------ */
    /* The camera and the lamps: the view panel sets these                 */
    /* ------------------------------------------------------------------ */

    /** Orientation, w x y z. */
    final double[] q = {1, 0, 0, 0};
    static final double HOME = 7.2;
    double distance = HOME;
    double panX;
    double panY;
    double fovDeg = 30;
    boolean perspective = true;
    /** How far each axis is stretched: the X, Y, Z of the user's sliders. */
    final double[] stretch = {1, 1, 1};

    double lightAz = 35;
    double lightEl = 55;
    double ambient = 0.30;
    double diffuse = 0.70;
    double specular = 0.35;
    double shininess = 30;

    boolean wireframe;
    boolean axes = true;
    boolean walls = true;
    boolean fog;
    boolean outline = true;
    boolean anaglyph;
    /** A dark ground, as the theme's when it is dark; the user may turn it over. */
    boolean darkBackground = com.sphere.components.imaging.ImagingTheme.isDark();
    boolean colorBar = true;
    boolean hud = true;
    double pointScale = 1;
    /** Cutting planes, in the unit cube: what lies outside is not drawn. */
    final double[] clipLo = {-1, -1, -1};
    final double[] clipHi = {1, 1, 1};

    /* ------------------------------------------------------------------ */
    /* What the last frame left, for the overlay and the mouse             */
    /* ------------------------------------------------------------------ */

    private int W;
    private int H;
    private int ss = 1;
    private int[] color = new int[0];
    private float[] depth = new float[0];
    private double[] m = new double[9];
    private double f;
    private double[] center = new double[3];
    private double[] half = {1, 1, 1};
    /** How much of the cube the data box fills along each axis: 1, or less when the shape keeps its aspect. */
    private final double[] extent = {1, 1, 1};
    private final Map<Mesh3D, float[]> screen = new IdentityHashMap<>();
    private final Map<Mesh3D, float[]> smoothCache = new IdentityHashMap<>();
    private final Map<Mesh3D, double[]> smoothKey = new IdentityHashMap<>();
    long lastFrameNanos;

    /** Another renderer with the same camera and lamps, for a picture drawn off the event thread. */
    Renderer3D twin() {
        final Renderer3D r = new Renderer3D();
        System.arraycopy(q, 0, r.q, 0, 4);
        System.arraycopy(stretch, 0, r.stretch, 0, 3);
        System.arraycopy(clipLo, 0, r.clipLo, 0, 3);
        System.arraycopy(clipHi, 0, r.clipHi, 0, 3);
        r.distance = distance;
        r.panX = panX;
        r.panY = panY;
        r.fovDeg = fovDeg;
        r.perspective = perspective;
        r.lightAz = lightAz;
        r.lightEl = lightEl;
        r.ambient = ambient;
        r.diffuse = diffuse;
        r.specular = specular;
        r.shininess = shininess;
        r.wireframe = wireframe;
        r.axes = axes;
        r.walls = walls;
        r.fog = fog;
        r.outline = outline;
        r.anaglyph = anaglyph;
        r.darkBackground = darkBackground;
        r.colorBar = colorBar;
        r.hud = hud;
        r.pointScale = pointScale;
        return r;
    }

    /* ------------------------------------------------------------------ */
    /* Quaternions                                                         */
    /* ------------------------------------------------------------------ */

    static double[] axisAngle(double x, double y, double z, double angle) {
        final double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1e-12) return new double[]{1, 0, 0, 0};
        final double s = Math.sin(angle / 2) / l;
        return new double[]{Math.cos(angle / 2), x * s, y * s, z * s};
    }

    static double[] mul(double[] a, double[] b) {
        return new double[]{
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]};
    }

    static double[] normalize(double[] a) {
        final double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3]);
        return l < 1e-12 ? new double[]{1, 0, 0, 0} : new double[]{a[0] / l, a[1] / l, a[2] / l, a[3] / l};
    }

    static double[] matrix(double[] q) {
        final double w = q[0];
        final double x = q[1];
        final double y = q[2];
        final double z = q[3];
        return new double[]{
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)};
    }

    /** Sets the orientation as ROOT names it: theta the elevation, phi the azimuth, in degrees. */
    void setAngles(double thetaDeg, double phiDeg) {
        final double[] tilt = axisAngle(1, 0, 0, Math.toRadians(thetaDeg - 90));
        final double[] turn = axisAngle(0, 0, 1, Math.toRadians(-phiDeg));
        set(normalize(mul(tilt, turn)));
    }

    void set(double[] quaternion) {
        System.arraycopy(quaternion, 0, q, 0, 4);
    }

    /** A turn about an axis of the screen (x right, y up), the arcball's. */
    void turnScreen(double ax, double ay, double az, double angle) {
        set(normalize(mul(axisAngle(ax, ay, az, angle), q)));
    }

    /** A turn about the data's own z axis, the turntable's: z stays up. */
    void turnData(double angle) {
        set(normalize(mul(q, axisAngle(0, 0, 1, angle))));
    }

    /** The elevation and the azimuth of the current orientation, in degrees, as setAngles takes them. */
    double[] angles() {
        final double[] r = matrix(q);
        // The direction of the eye, in the data's frame: the third row.
        final double theta = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, r[8]))));
        final double phi = Math.toDegrees(Math.atan2(r[6], -r[7]));
        return new double[]{theta, phi};
    }

    /* ------------------------------------------------------------------ */
    /* A frame                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Draws the scene at w by h pixels; supersampled ss times along each side
     * and filtered down, which is what makes edges smooth when the view rests.
     */
    BufferedImage render(Scene3D scene, int w, int h, int supersampling) {
        final long t0 = System.nanoTime();
        w = Math.max(16, w);
        h = Math.max(16, h);
        final BufferedImage out;
        if (anaglyph) {
            final int[] left = frame(scene, w, h, supersampling, -1);
            final int[] right = frame(scene, w, h, supersampling, 1);
            final int[] mixed = new int[left.length];
            for (int i = 0; i < mixed.length; i++) {
                mixed[i] = 0xFF000000 | luminance(left[i]) << 16 | (right[i] & 0x0000FFFF);
            }
            out = image(mixed, w, h);
            frame(scene, w, h, 1, 0);
        } else {
            out = image(frame(scene, w, h, supersampling, 0), w, h);
            if (supersampling != 1) frame(scene, w, h, 1, 0);
        }
        overlay(out, scene);
        lastFrameNanos = System.nanoTime() - t0;
        return out;
    }

    private static int luminance(int c) {
        return Math.min(255, (int) (0.30 * (c >> 16 & 255) + 0.59 * (c >> 8 & 255) + 0.11 * (c & 255)));
    }

    private static BufferedImage image(int[] pixels, int w, int h) {
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final int[] data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels, 0, data, 0, Math.min(data.length, pixels.length));
        return img;
    }

    /** One frame for one eye (-1, +1) or both (0), at the final size. */
    private int[] frame(Scene3D scene, int w, int h, int supersampling, int eye) {
        ss = Math.max(1, supersampling);
        W = w * ss;
        H = h * ss;
        if (color.length != W * H) {
            color = new int[W * H];
            depth = new float[W * H];
        }
        background();
        Arrays.fill(depth, 0f);

        m = matrix(q);
        if (eye != 0) {
            final double[] e = matrix(axisAngle(0, 1, 0, Math.toRadians(2.2 * eye)));
            m = times(e, m);
        }
        f = H / 2.0 / Math.tan(Math.toRadians(fovDeg) / 2);
        final double[] box = scene.box();
        double maxHalf = 0;
        for (int a = 0; a < 3; a++) {
            center[a] = (box[2 * a] + box[2 * a + 1]) / 2;
            half[a] = Math.max(1e-300, (box[2 * a + 1] - box[2 * a]) / 2);
            maxHalf = Math.max(maxHalf, half[a]);
        }
        for (int a = 0; a < 3; a++) extent[a] = scene.keepAspect ? half[a] / maxHalf : 1;
        if (scene.keepAspect) Arrays.fill(half, maxHalf);

        final Batch opaque = new Batch();
        final Batch translucent = new Batch();
        screen.clear();
        final boolean clipping = clipping();
        for (Mesh3D mesh : scene.meshes) {
            if (!mesh.visible || mesh.vertexCount() == 0) continue;
            final float[] s = project(mesh);
            screen.put(mesh, s);
            if (mesh.triangleCount() > 0) setup(mesh, s, mesh.opacity >= 0.999f ? opaque : translucent, clipping);
        }
        if (walls || axes) frameLines(box);
        raster(opaque, false);
        translucent.sortBackToFront();
        raster(translucent, true);
        for (Mesh3D mesh : scene.meshes) {
            if (!mesh.visible) continue;
            final float[] s = screen.get(mesh);
            if (s == null) continue;
            if (mesh.lineCount() > 0) lines(mesh, s, clipping);
            if (wireframe && mesh.triangleCount() > 0) wires(mesh, s, clipping);
            if (mesh.pointCount() > 0) points(mesh, s, clipping);
        }
        if (outline) outline();
        if (fog) fog();
        return ss == 1 ? color.clone() : downsample(w, h);
    }

    private boolean clipping() {
        for (int a = 0; a < 3; a++) if (clipLo[a] > -1 || clipHi[a] < 1) return true;
        return false;
    }

    private static double[] times(double[] a, double[] b) {
        final double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
            }
        }
        return r;
    }

    /**
     * The palette whose Space3D keys colour the ground, the frame and the
     * labels: the theme's, or, when the user turns the ground over, the
     * palette of the other darkness.
     */
    private ThemePalette space() {
        final ThemePalette current = ThemeManager.getCurrentPalette();
        if (darkBackground == com.sphere.components.imaging.ImagingTheme.isDark()) return current;
        return darkBackground ? ThemePaletteDark.INSTANCE : ThemePaletteLight.INSTANCE;
    }

    private int bgTop() {
        return space().getSpace3DGroundTop().getRGB() & 0xFFFFFF;
    }

    private int bgBottom() {
        return space().getSpace3DGroundBottom().getRGB() & 0xFFFFFF;
    }

    /** The colour of the labels over the ground: the title's, or the axes'. */
    private Color labelColor(boolean strong) {
        return strong ? space().getSpace3DTitle() : space().getSpace3DLabel();
    }

    private void background() {
        final int t = bgTop();
        final int b = bgBottom();
        for (int y = 0; y < H; y++) {
            final double k = y / (double) Math.max(1, H - 1);
            final int c = 0xFF000000 | mix(t, b, k);
            Arrays.fill(color, y * W, (y + 1) * W, c);
        }
    }

    private static int mix(int a, int b, double k) {
        final int r = (int) ((a >> 16 & 255) * (1 - k) + (b >> 16 & 255) * k);
        final int g = (int) ((a >> 8 & 255) * (1 - k) + (b >> 8 & 255) * k);
        final int bl = (int) ((a & 255) * (1 - k) + (b & 255) * k);
        return r << 16 | g << 8 | bl;
    }

    /* ------------------------------------------------------------------ */
    /* Vertices                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Every vertex of a mesh, seen: per vertex the screen x and y, the inverse
     * of its distance (the depth the buffer keeps), its place in the view
     * (vx vy vz, for flat normals) and in the unit cube (for the cuts): 9
     * floats.
     */
    private float[] project(Mesh3D mesh) {
        final int n = mesh.vertexCount();
        final float[] s = new float[9 * n];
        final double[] k = new double[3];
        for (int a = 0; a < 3; a++) k[a] = mesh.normalized ? 1 : 1 / half[a];
        for (int i = 0; i < n; i++) {
            final double nx = mesh.normalized ? mesh.pos[3 * i] : (mesh.pos[3 * i] - center[0]) * k[0];
            final double ny = mesh.normalized ? mesh.pos[3 * i + 1] : (mesh.pos[3 * i + 1] - center[1]) * k[1];
            final double nz = mesh.normalized ? mesh.pos[3 * i + 2] : (mesh.pos[3 * i + 2] - center[2]) * k[2];
            final double x = nx * stretch[0];
            final double y = ny * stretch[1];
            final double z = nz * stretch[2];
            final double vx = m[0] * x + m[1] * y + m[2] * z - panX;
            final double vy = m[3] * x + m[4] * y + m[5] * z - panY;
            final double vz = m[6] * x + m[7] * y + m[8] * z;
            final double dist = distance - vz;
            final double kk = perspective ? f / Math.max(1e-3, dist) : f / distance;
            final int o = 9 * i;
            s[o] = (float) (W / 2.0 + vx * kk);
            s[o + 1] = (float) (H / 2.0 - vy * kk);
            s[o + 2] = dist > 1e-3 ? (float) (1 / dist) : -1f;
            s[o + 3] = (float) vx;
            s[o + 4] = (float) vy;
            s[o + 5] = (float) vz;
            s[o + 6] = (float) (nx / extent[0]);
            s[o + 7] = (float) (ny / extent[1]);
            s[o + 8] = (float) (nz / extent[2]);
        }
        return s;
    }

    /** Where a point of the unit cube lands on the final picture: x, y, and its depth toward the eye. */
    double[] toScreen(double nx, double ny, double nz) {
        final double x = nx * stretch[0];
        final double y = ny * stretch[1];
        final double z = nz * stretch[2];
        final double vx = m[0] * x + m[1] * y + m[2] * z - panX;
        final double vy = m[3] * x + m[4] * y + m[5] * z - panY;
        final double vz = m[6] * x + m[7] * y + m[8] * z;
        final double dist = distance - vz;
        final double kk = perspective ? f / Math.max(1e-3, dist) : f / distance;
        return new double[]{(W / 2.0 + vx * kk) / ss, (H / 2.0 - vy * kk) / ss, vz};
    }

    /** A data point in the unit cube of the last frame. */
    double[] normalizedOf(double x, double y, double z, boolean normalized) {
        if (normalized) return new double[]{x, y, z};
        return new double[]{(x - center[0]) / half[0], (y - center[1]) / half[1], (z - center[2]) / half[2]};
    }

    double dataOf(int axis, double normalized) {
        return center[axis] + normalized * half[axis];
    }

    /* ------------------------------------------------------------------ */
    /* Light                                                               */
    /* ------------------------------------------------------------------ */

    private double[] lightDir() {
        final double az = Math.toRadians(lightAz);
        final double el = Math.toRadians(lightEl);
        return new double[]{Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)};
    }

    /** A colour lit along a normal of the view; both sides of a face are lit alike. */
    private int shade(int argb, double nx, double ny, double nz, double[] l, double[] hv) {
        final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-20) return argb;
        nx /= len;
        ny /= len;
        nz /= len;
        if (nz < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        final double d = Math.max(0, nx * l[0] + ny * l[1] + nz * l[2]);
        final double fill = 0.22 * nz;
        final double sp = specular > 0 ? specular * Math.pow(Math.max(0, nx * hv[0] + ny * hv[1] + nz * hv[2]), shininess) : 0;
        final double k = ambient + diffuse * d + fill;
        final int r = clamp((argb >> 16 & 255) * k + 255 * sp);
        final int g = clamp((argb >> 8 & 255) * k + 255 * sp);
        final int b = clamp((argb & 255) * k + 255 * sp);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    /* ------------------------------------------------------------------ */
    /* Triangles                                                           */
    /* ------------------------------------------------------------------ */

    /** Triangles ready for the raster: screen corners, depths, lit colours, cube places. */
    private static final class Batch {
        float[] xy = new float[6 * 1024];
        float[] z = new float[3 * 1024];
        int[] c = new int[3 * 1024];
        float[] cube = new float[9 * 1024];
        float[] alpha = new float[1024];
        int n;

        void add(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                 int c0, int c1, int c2, float[] s, int a, int b, int d, float opacity) {
            if (n == alpha.length) {
                final int k = alpha.length * 2;
                xy = Arrays.copyOf(xy, 6 * k);
                z = Arrays.copyOf(z, 3 * k);
                c = Arrays.copyOf(c, 3 * k);
                cube = Arrays.copyOf(cube, 9 * k);
                alpha = Arrays.copyOf(alpha, k);
            }
            xy[6 * n] = x0;
            xy[6 * n + 1] = y0;
            xy[6 * n + 2] = x1;
            xy[6 * n + 3] = y1;
            xy[6 * n + 4] = x2;
            xy[6 * n + 5] = y2;
            z[3 * n] = z0;
            z[3 * n + 1] = z1;
            z[3 * n + 2] = z2;
            c[3 * n] = c0;
            c[3 * n + 1] = c1;
            c[3 * n + 2] = c2;
            for (int k = 0; k < 3; k++) {
                cube[9 * n + k] = s[9 * a + 6 + k];
                cube[9 * n + 3 + k] = s[9 * b + 6 + k];
                cube[9 * n + 6 + k] = s[9 * d + 6 + k];
            }
            alpha[n] = opacity;
            n++;
        }

        /** Far first, by the mean depth; nearest last, so blending covers what is behind. */
        void sortBackToFront() {
            if (n < 2) return;
            final Integer[] order = new Integer[n];
            final float[] key = new float[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
                key[i] = z[3 * i] + z[3 * i + 1] + z[3 * i + 2];
            }
            Arrays.sort(order, (a, b) -> Float.compare(key[a], key[b]));
            final Batch sorted = new Batch();
            sorted.xy = new float[6 * n];
            sorted.z = new float[3 * n];
            sorted.c = new int[3 * n];
            sorted.cube = new float[9 * n];
            sorted.alpha = new float[n];
            for (int k = 0; k < n; k++) {
                final int i = order[k];
                System.arraycopy(xy, 6 * i, sorted.xy, 6 * k, 6);
                System.arraycopy(z, 3 * i, sorted.z, 3 * k, 3);
                System.arraycopy(c, 3 * i, sorted.c, 3 * k, 3);
                System.arraycopy(cube, 9 * i, sorted.cube, 9 * k, 9);
                sorted.alpha[k] = alpha[i];
            }
            xy = sorted.xy;
            z = sorted.z;
            c = sorted.c;
            cube = sorted.cube;
            alpha = sorted.alpha;
        }
    }

    private void setup(Mesh3D mesh, float[] s, Batch batch, boolean clipping) {
        final double[] l = lightDir();
        final double[] hv = {l[0], l[1], l[2] + 1};
        final double hl = Math.sqrt(hv[0] * hv[0] + hv[1] * hv[1] + hv[2] * hv[2]);
        hv[0] /= hl;
        hv[1] /= hl;
        hv[2] /= hl;
        // Per-vertex lit colours for a smooth mesh.
        int[] lit = null;
        if (mesh.lit && (mesh.smooth || mesh.normal != null)) {
            final float[] nrm = normals(mesh);
            lit = new int[mesh.vertexCount()];
            final double[] k = new double[3];
            for (int a = 0; a < 3; a++) k[a] = mesh.normalized ? 1 / stretch[a] : half[a] / stretch[a];
            final boolean explicit = mesh.normal != null && !mesh.smooth;
            for (int i = 0; i < lit.length; i++) {
                double nx = nrm[3 * i];
                double ny = nrm[3 * i + 1];
                double nz = nrm[3 * i + 2];
                if (explicit) {
                    // A normal of the data's space, carried through the cube's scales.
                    nx *= k[0];
                    ny *= k[1];
                    nz *= k[2];
                }
                final double vx = m[0] * nx + m[1] * ny + m[2] * nz;
                final double vy = m[3] * nx + m[4] * ny + m[5] * nz;
                final double vz = m[6] * nx + m[7] * ny + m[8] * nz;
                lit[i] = shade(mesh.argb[i], vx, vy, vz, l, hv);
            }
        }
        final float opacity = mesh.opacity;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            final int a = mesh.tri[3 * t];
            final int b = mesh.tri[3 * t + 1];
            final int c = mesh.tri[3 * t + 2];
            final float za = s[9 * a + 2];
            final float zb = s[9 * b + 2];
            final float zc = s[9 * c + 2];
            if (za <= 0 || zb <= 0 || zc <= 0) continue;
            if (clipping && outside(s, a) && outside(s, b) && outside(s, c)) continue;
            int ca;
            int cb;
            int cc;
            if (lit != null) {
                ca = lit[a];
                cb = lit[b];
                cc = lit[c];
            } else if (mesh.lit) {
                final double ux = s[9 * b + 3] - s[9 * a + 3];
                final double uy = s[9 * b + 4] - s[9 * a + 4];
                final double uz = s[9 * b + 5] - s[9 * a + 5];
                final double vx = s[9 * c + 3] - s[9 * a + 3];
                final double vy = s[9 * c + 4] - s[9 * a + 4];
                final double vz = s[9 * c + 5] - s[9 * a + 5];
                final double nx = uy * vz - uz * vy;
                final double ny = uz * vx - ux * vz;
                final double nz = ux * vy - uy * vx;
                ca = shade(mesh.argb[a], nx, ny, nz, l, hv);
                cb = mesh.argb[b] == mesh.argb[a] ? ca : shade(mesh.argb[b], nx, ny, nz, l, hv);
                cc = mesh.argb[c] == mesh.argb[a] ? ca : shade(mesh.argb[c], nx, ny, nz, l, hv);
            } else {
                ca = mesh.argb[a];
                cb = mesh.argb[b];
                cc = mesh.argb[c];
            }
            batch.add(s[9 * a], s[9 * a + 1], za, s[9 * b], s[9 * b + 1], zb, s[9 * c], s[9 * c + 1], zc,
                ca, cb, cc, s, a, b, c, opacity);
        }
    }

    private float[] normals(Mesh3D mesh) {
        if (mesh.normal != null && !mesh.smooth) return mesh.normal;
        // Smooth normals are taken in the stretched cube, so they follow the sliders.
        final double[] key = new double[3];
        for (int a = 0; a < 3; a++) key[a] = mesh.normalized ? stretch[a] : stretch[a] / half[a];
        final double[] was = smoothKey.get(mesh);
        if (was == null || !Arrays.equals(was, key) || smoothCache.get(mesh) == null) {
            mesh.computeSmoothNormals(key);
            smoothCache.put(mesh, mesh.normal);
            smoothKey.put(mesh, key);
        }
        return smoothCache.get(mesh);
    }

    private boolean outside(float[] s, int i) {
        for (int a = 0; a < 3; a++) {
            final float v = s[9 * i + 6 + a];
            if (v < clipLo[a] - 1e-6 || v > clipHi[a] + 1e-6) return true;
        }
        return false;
    }

    private boolean outsideAt(double x, double y, double z) {
        return x < clipLo[0] || x > clipHi[0] || y < clipLo[1] || y > clipHi[1] || z < clipLo[2] || z > clipHi[2];
    }

    /** Fills the batch's triangles, every band of rows at once. */
    private void raster(Batch batch, boolean blend) {
        if (batch.n == 0) return;
        final int bands = Math.max(1, Math.min(H / 8, Runtime.getRuntime().availableProcessors() * 4));
        final int bandH = (H + bands - 1) / bands;
        final boolean clipping = clipping();
        IntStream.range(0, bands).parallel().forEach(band -> {
            final int y0 = band * bandH;
            final int y1 = Math.min(H, y0 + bandH);
            for (int t = 0; t < batch.n; t++) triangle(batch, t, y0, y1, blend, clipping);
        });
    }

    private void triangle(Batch b, int t, int yLo, int yHi, boolean blend, boolean clipping) {
        final float x0 = b.xy[6 * t];
        final float y0 = b.xy[6 * t + 1];
        final float x1 = b.xy[6 * t + 2];
        final float y1 = b.xy[6 * t + 3];
        final float x2 = b.xy[6 * t + 4];
        final float y2 = b.xy[6 * t + 5];
        final int minY = Math.max(yLo, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        final int maxY = Math.min(yHi - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minY > maxY) return;
        final int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        final int maxX = Math.min(W - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        if (minX > maxX) return;
        final double area = (x1 - x0) * (double) (y2 - y0) - (x2 - x0) * (double) (y1 - y0);
        if (Math.abs(area) < 1e-9) return;
        final double inv = 1 / area;
        final float z0 = b.z[3 * t];
        final float z1 = b.z[3 * t + 1];
        final float z2 = b.z[3 * t + 2];
        final int c0 = b.c[3 * t];
        final int c1 = b.c[3 * t + 1];
        final int c2 = b.c[3 * t + 2];
        final boolean flat = c0 == c1 && c1 == c2;
        final float alpha = b.alpha[t];
        final int r0 = c0 >> 16 & 255;
        final int g0 = c0 >> 8 & 255;
        final int b0 = c0 & 255;
        final int r1 = c1 >> 16 & 255;
        final int g1 = c1 >> 8 & 255;
        final int b1 = c1 & 255;
        final int r2 = c2 >> 16 & 255;
        final int g2 = c2 >> 8 & 255;
        final int b2 = c2 & 255;
        for (int y = minY; y <= maxY; y++) {
            final double py = y + 0.5;
            final int row = y * W;
            for (int x = minX; x <= maxX; x++) {
                final double px = x + 0.5;
                final double w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                if (w0 < -1e-9) continue;
                final double w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                if (w1 < -1e-9) continue;
                final double w2 = 1 - w0 - w1;
                if (w2 < -1e-9) continue;
                final float z = (float) (w0 * z0 + w1 * z1 + w2 * z2);
                final int idx = row + x;
                if (z <= depth[idx]) continue;
                if (clipping) {
                    final int o = 9 * t;
                    final double cx = w0 * b.cube[o] + w1 * b.cube[o + 3] + w2 * b.cube[o + 6];
                    final double cy = w0 * b.cube[o + 1] + w1 * b.cube[o + 4] + w2 * b.cube[o + 7];
                    final double cz = w0 * b.cube[o + 2] + w1 * b.cube[o + 5] + w2 * b.cube[o + 8];
                    if (outsideAt(cx, cy, cz)) continue;
                }
                final int r;
                final int g;
                final int bl;
                if (flat) {
                    r = r0;
                    g = g0;
                    bl = b0;
                } else {
                    r = (int) (w0 * r0 + w1 * r1 + w2 * r2);
                    g = (int) (w0 * g0 + w1 * g1 + w2 * g2);
                    bl = (int) (w0 * b0 + w1 * b1 + w2 * b2);
                }
                if (blend) {
                    final int d = color[idx];
                    final float a = alpha;
                    color[idx] = 0xFF000000
                        | (int) (r * a + (d >> 16 & 255) * (1 - a)) << 16
                        | (int) (g * a + (d >> 8 & 255) * (1 - a)) << 8
                        | (int) (bl * a + (d & 255) * (1 - a));
                } else {
                    color[idx] = 0xFF000000 | r << 16 | g << 8 | bl;
                    depth[idx] = z;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lines and points                                                    */
    /* ------------------------------------------------------------------ */

    private void lines(Mesh3D mesh, float[] s, boolean clipping) {
        final int width = Math.max(1, Math.round(mesh.lineWidth * ss));
        for (int l = 0; l < mesh.lineCount(); l++) {
            final int a = mesh.lin[2 * l];
            final int b = mesh.lin[2 * l + 1];
            if (clipping && !mesh.normalized && (outside(s, a) || outside(s, b))) continue;
            segment(s, a, b, mesh.argb[a], mesh.argb[b], width, mesh.opacity, 2e-3f);
        }
    }

    private void wires(Mesh3D mesh, float[] s, boolean clipping) {
        final int c = darkBackground ? 0x80FFFFFF : 0x80202020;
        for (int t = 0; t < mesh.triangleCount(); t++) {
            final int a = mesh.tri[3 * t];
            final int b = mesh.tri[3 * t + 1];
            final int d = mesh.tri[3 * t + 2];
            if (clipping && (outside(s, a) || outside(s, b) || outside(s, d))) continue;
            segment(s, a, b, c, c, Math.max(1, ss / 2), 0.55f, 4e-3f);
            segment(s, b, d, c, c, Math.max(1, ss / 2), 0.55f, 4e-3f);
            segment(s, d, a, c, c, Math.max(1, ss / 2), 0.55f, 4e-3f);
        }
    }

    /** A segment, depth tested a little in front of what it lies on, so wires show on their surface. */
    private void segment(float[] s, int a, int b, int ca, int cb, int width, float opacity, float bias) {
        final float za = s[9 * a + 2];
        final float zb = s[9 * b + 2];
        if (za <= 0 || zb <= 0) return;
        final double xa = s[9 * a];
        final double ya = s[9 * a + 1];
        final double xb = s[9 * b];
        final double yb = s[9 * b + 1];
        final double len = Math.max(Math.abs(xb - xa), Math.abs(yb - ya));
        if (len > 8 * (W + H)) return;
        final int steps = Math.max(1, (int) Math.ceil(len));
        final float aa = ((ca >>> 24) / 255f) * opacity;
        final int half = width / 2;
        for (int k = 0; k <= steps; k++) {
            final double t = k / (double) steps;
            final int x = (int) (xa + (xb - xa) * t);
            final int y = (int) (ya + (yb - ya) * t);
            final float z = (float) (za + (zb - za) * t) * (1 + bias);
            final int c = t < 0.5 ? ca : cb;
            for (int dy = -half; dy <= half; dy++) {
                final int yy = y + dy;
                if (yy < 0 || yy >= H) continue;
                for (int dx = -half; dx <= half; dx++) {
                    final int xx = x + dx;
                    if (xx < 0 || xx >= W) continue;
                    final int idx = yy * W + xx;
                    if (z < depth[idx]) continue;
                    color[idx] = blend(color[idx], c, aa);
                }
            }
        }
    }

    private static int blend(int dst, int src, float a) {
        if (a >= 0.999f) return 0xFF000000 | src;
        return 0xFF000000
            | (int) ((src >> 16 & 255) * a + (dst >> 16 & 255) * (1 - a)) << 16
            | (int) ((src >> 8 & 255) * a + (dst >> 8 & 255) * (1 - a)) << 8
            | (int) ((src & 255) * a + (dst & 255) * (1 - a));
    }

    /** Points as little lit spheres: brighter toward the lamp, darker at the rim. */
    private void points(Mesh3D mesh, float[] s, boolean clipping) {
        final double radius = Math.max(0.5, mesh.pointSize * pointScale * ss / 2.0);
        final int r = (int) Math.ceil(radius);
        final double[] l = lightDir();
        final boolean sphere = radius >= 1.6;
        for (int k = 0; k < mesh.pointCount(); k++) {
            final int i = mesh.pts[k];
            final float z = s[9 * i + 2];
            if (z <= 0) continue;
            if (clipping && !mesh.normalized && outside(s, i)) continue;
            // Nearer points are a little bigger in perspective.
            final double rr = perspective ? radius * Math.min(2.5, Math.max(0.5, z * distance)) : radius;
            final int ri = Math.max(r, (int) Math.ceil(rr));
            final int cx = (int) s[9 * i];
            final int cy = (int) s[9 * i + 1];
            int base = mesh.argb[i];
            // A dark marker on the dark ground would vanish: lifted toward white, its hue kept.
            if (darkBackground && luminance(base) < 150) base = (base & 0xFF000000) | mix(base & 0xFFFFFF, 0xFFFFFF, 0.5);
            final float alpha = ((base >>> 24) / 255f) * mesh.opacity;
            for (int dy = -ri; dy <= ri; dy++) {
                final int y = cy + dy;
                if (y < 0 || y >= H) continue;
                for (int dx = -ri; dx <= ri; dx++) {
                    final int x = cx + dx;
                    if (x < 0 || x >= W) continue;
                    final double d2 = (dx * dx + dy * dy) / (rr * rr);
                    if (d2 > 1) continue;
                    final int idx = y * W + x;
                    if (z < depth[idx]) continue;
                    int c = base;
                    if (sphere && mesh.lit) {
                        final double nz = Math.sqrt(1 - d2);
                        final double nx = dx / rr;
                        final double ny = -dy / rr;
                        final double dd = Math.max(0, nx * l[0] + ny * l[1] + nz * l[2]);
                        final double k2 = 0.35 + 0.75 * dd;
                        final double sp = 0.45 * Math.pow(Math.max(0, nz * 0.8 + dd * 0.2), 24);
                        c = clamp((base >> 16 & 255) * k2 + 255 * sp) << 16 | clamp((base >> 8 & 255) * k2 + 255 * sp) << 8
                            | clamp((base & 255) * k2 + 255 * sp);
                    }
                    color[idx] = blend(color[idx], c & 0xFFFFFF, alpha);
                    if (alpha > 0.5f) depth[idx] = z;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* The box: back walls with their grid                                 */
    /* ------------------------------------------------------------------ */

    private void frameLines(double[] box) {
        final Mesh3D frame = new Mesh3D("frame");
        frame.normalized = true;
        final int edge = 0xFF000000 | space().getSpace3DEdge().getRGB();
        final int grid = 0xFF000000 | space().getSpace3DGrid().getRGB();
        final double[] sx = {stretch[0], stretch[1], stretch[2]};
        // For each axis the wall away from the eye: the side whose outward normal points off.
        final int[] back = new int[3];
        for (int a = 0; a < 3; a++) {
            final double vz = m[6 + a] * sx[a];
            back[a] = vz > 0 ? -1 : 1;
        }
        if (walls) {
            for (int a = 0; a < 3; a++) {
                final int b = (a + 1) % 3;
                final int c = (a + 2) % 3;
                final double wall = back[a] * extent[a];
                // Grid lines along b and c at the ticks of the other axis.
                for (int axis : new int[]{b, c}) {
                    final int other = axis == b ? c : b;
                    for (double tv : tickPositions(box, other)) {
                        final double[] p = new double[3];
                        final double[] q2 = new double[3];
                        p[a] = wall;
                        q2[a] = wall;
                        p[other] = tv;
                        q2[other] = tv;
                        p[axis] = -extent[axis];
                        q2[axis] = extent[axis];
                        final int i = frame.vertex(p[0], p[1], p[2], grid, 0);
                        final int j = frame.vertex(q2[0], q2[1], q2[2], grid, 0);
                        frame.line(i, j);
                    }
                }
            }
        }
        frame.boxEdges(-extent[0], -extent[1], -extent[2], extent[0], extent[1], extent[2], edge);
        final float[] s = project(frame);
        screen.put(frame, s);
        // Drawn before the data, under it, so they never cover what they frame.
        for (int l = 0; l < frame.lineCount(); l++) {
            final int a = frame.lin[2 * l];
            final int b = frame.lin[2 * l + 1];
            final boolean isEdge = frame.argb[a] == edge;
            if (isEdge && !axes) continue;
            segment(s, a, b, frame.argb[a], frame.argb[b], Math.max(1, ss), isEdge ? 0.85f : 0.9f, -0.02f);
        }
        // The depth they wrote nothing: lines do not write depth, so the data covers them.
    }

    /** The ticks of an axis, as places in the unit cube. */
    private double[] tickPositions(double[] box, int axis) {
        final double lo = box[2 * axis];
        final double hi = box[2 * axis + 1];
        final double[] t = ticks(lo, hi);
        final double[] out = new double[t.length];
        int n = 0;
        for (double v : t) {
            final double p = (v - center[axis]) / half[axis];
            if (p > -extent[axis] * 1.0001 && p < extent[axis] * 1.0001) out[n++] = p;
        }
        return Arrays.copyOf(out, n);
    }

    static double[] ticks(double lo, double hi) {
        if (!(hi > lo)) return new double[]{lo};
        final double step = niceStep((hi - lo) / 5);
        final double first = Math.ceil(lo / step - 1e-9) * step;
        final int n = (int) Math.floor((hi - first) / step + 1e-9) + 1;
        final double[] t = new double[Math.max(0, Math.min(n, 40))];
        for (int i = 0; i < t.length; i++) t[i] = first + i * step;
        return t;
    }

    static double niceStep(double raw) {
        final double p = Math.pow(10, Math.floor(Math.log10(raw)));
        final double r = raw / p;
        return (r < 1.5 ? 1 : r < 3 ? 2 : r < 7 ? 5 : 10) * p;
    }

    static String label(double v, double step, boolean log) {
        if (log) {
            final double p = Math.pow(10, v);
            if (Math.abs(v - Math.rint(v)) < 1e-6) return "10^" + (int) Math.rint(v);
            return String.format(Locale.ROOT, "%.3g", p);
        }
        if (Math.abs(v) < step * 1e-6) return "0";
        final double a = Math.abs(v);
        if (a >= 1e5 || a < 1e-3) return String.format(Locale.ROOT, "%.2g", v);
        final int decimals = Math.max(0, (int) Math.ceil(-Math.log10(step) + 1e-9));
        return String.format(Locale.ROOT, "%." + Math.min(6, decimals) + "f", v);
    }

    /* ------------------------------------------------------------------ */
    /* After the raster                                                    */
    /* ------------------------------------------------------------------ */

    /** Darkens where the depth jumps: silhouettes and creases, as an engraver would. */
    private void outline() {
        final float[] d = depth;
        final int[] c = color;
        final int[] out = c.clone();
        final double threshold = 0.05;
        IntStream.range(1, H - 1).parallel().forEach(y -> {
            for (int x = 1; x < W - 1; x++) {
                final int i = y * W + x;
                final float z = d[i];
                if (z <= 0) continue;
                final double dist = 1 / z;
                double jump = 0;
                for (int k : new int[]{i - 1, i + 1, i - W, i + W}) {
                    final float zk = d[k];
                    final double dk = zk <= 0 ? dist + 10 : 1 / zk;
                    jump = Math.max(jump, dk - dist);
                }
                if (jump > threshold) {
                    final double dim = 1 - 0.6 * Math.min(1, 0.35 + jump / 0.6);
                    final int v = c[i];
                    out[i] = 0xFF000000 | (int) ((v >> 16 & 255) * dim) << 16 | (int) ((v >> 8 & 255) * dim) << 8
                        | (int) ((v & 255) * dim);
                }
            }
        });
        System.arraycopy(out, 0, c, 0, c.length);
    }

    private void fog() {
        final int bg = bgBottom();
        final double near = distance - 1.8;
        final double far = distance + 1.8;
        for (int i = 0; i < color.length; i++) {
            final float z = depth[i];
            if (z <= 0) continue;
            final double k = Math.max(0, Math.min(0.75, (1 / z - near) / (far - near) * 0.9));
            color[i] = 0xFF000000 | mix(color[i] & 0xFFFFFF, bg, k);
        }
    }

    private int[] downsample(int w, int h) {
        final int[] out = new int[w * h];
        final int n = ss * ss;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < ss; dy++) {
                    final int row = (y * ss + dy) * W + x * ss;
                    for (int dx = 0; dx < ss; dx++) {
                        final int c = color[row + dx];
                        r += c >> 16 & 255;
                        g += c >> 8 & 255;
                        b += c & 255;
                    }
                }
                out[y * w + x] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        });
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The overlay: axes, palette, what is shown                           */
    /* ------------------------------------------------------------------ */

    private void overlay(BufferedImage img, Scene3D scene) {
        final Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (axes) axesOverlay(g, scene);
            if (colorBar && scene.palette != null && scene.palette.length > 1) colorBarOverlay(g, scene, img.getWidth(),
                img.getHeight());
            if (hud) {
                final Color text = labelColor(true);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                g.setColor(text);
                if (scene.title != null && !scene.title.isBlank()) g.drawString(scene.title, 12, 22);
            }
        } finally {
            g.dispose();
        }
    }

    private void axesOverlay(Graphics2D g, Scene3D scene) {
        final double[] box = scene.box();
        final Color text = labelColor(false);
        final Color tick = space().getSpace3DEdge();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        final FontMetrics fm = g.getFontMetrics();
        final double[] mid = toScreen(0, 0, 0);
        for (int a = 0; a < 3; a++) {
            // The edge along this axis on which to write: of the four, the one
            // lowest and in front for x and y, the leftmost for z.
            double[] best0 = null;
            double[] best1 = null;
            double bestScore = -Double.MAX_VALUE;
            for (int s1 = -1; s1 <= 1; s1 += 2) {
                for (int s2 = -1; s2 <= 1; s2 += 2) {
                    final double[] p = new double[3];
                    final double[] q2 = new double[3];
                    final int b = (a + 1) % 3;
                    final int c = (a + 2) % 3;
                    p[a] = -extent[a];
                    q2[a] = extent[a];
                    p[b] = s1 * extent[b];
                    q2[b] = s1 * extent[b];
                    p[c] = s2 * extent[c];
                    q2[c] = s2 * extent[c];
                    final double[] sp = toScreen(p[0], p[1], p[2]);
                    final double[] sq = toScreen(q2[0], q2[1], q2[2]);
                    final double score = a == 2
                        ? -(sp[0] + sq[0]) + 0.2 * (sp[2] + sq[2])
                        : (sp[1] + sq[1]) * 0.6 + (sp[2] + sq[2]) * 60;
                    if (score > bestScore) {
                        bestScore = score;
                        best0 = p;
                        best1 = q2;
                    }
                }
            }
            if (best0 == null) continue;
            final double lo = box[2 * a];
            final double hi = box[2 * a + 1];
            final double[] t = ticks(lo, hi);
            final double step = t.length > 1 ? t[1] - t[0] : Math.abs(hi - lo);
            final double[] e0 = toScreen(best0[0], best0[1], best0[2]);
            final double[] e1 = toScreen(best1[0], best1[1], best1[2]);
            // Outward: from the box's centre toward the edge, on the screen.
            double ox = (e0[0] + e1[0]) / 2 - mid[0];
            double oy = (e0[1] + e1[1]) / 2 - mid[1];
            final double ol = Math.max(1e-6, Math.hypot(ox, oy));
            ox /= ol;
            oy /= ol;
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(tick);
            g.drawLine((int) e0[0], (int) e0[1], (int) e1[0], (int) e1[1]);
            // On an axis seen end-on the labels would pile up: only every k-th is written.
            final double room = Math.hypot(e1[0] - e0[0], e1[1] - e0[1]) / Math.max(1, t.length);
            final int every = Math.max(1, (int) Math.ceil(16 / Math.max(1e-6, room)));
            int index = -1;
            for (double v : t) {
                final double u = (v - center[a]) / half[a];
                if (u < -extent[a] * 1.0001 || u > extent[a] * 1.0001) continue;
                if (++index % every != 0) continue;
                final double[] p = best0.clone();
                p[a] = u;
                final double[] sp = toScreen(p[0], p[1], p[2]);
                g.setColor(tick);
                g.drawLine((int) sp[0], (int) sp[1], (int) (sp[0] + ox * 5), (int) (sp[1] + oy * 5));
                final String s = label(v, step, scene.log[a]);
                g.setColor(text);
                final int w = fm.stringWidth(s);
                g.drawString(s, (int) (sp[0] + ox * 14 - w / 2.0), (int) (sp[1] + oy * 14 + fm.getAscent() / 2.0 - 1));
            }
            final String title = scene.axisTitle[a] == null || scene.axisTitle[a].isBlank()
                ? new String[]{"x", "y", "z"}[a] : scene.axisTitle[a];
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            final FontMetrics tf = g.getFontMetrics();
            final double tx = (e0[0] + e1[0]) / 2 + ox * 34;
            final double ty = (e0[1] + e1[1]) / 2 + oy * 34;
            g.setColor(a == 0 ? new Color(0xE0605A) : a == 1 ? new Color(0x52B060) : new Color(0x4C8CE0));
            g.drawString(title, (int) (tx - tf.stringWidth(title) / 2.0), (int) (ty + tf.getAscent() / 2.0));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        }
    }

    private void colorBarOverlay(Graphics2D g, Scene3D scene, int w, int h) {
        final int bw = 14;
        final int bh = (int) (h * 0.55);
        final int x = w - bw - 54;
        final int y = (h - bh) / 2;
        final Color[] p = scene.palette;
        for (int k = 0; k < bh; k++) {
            final int i = (int) ((bh - 1 - k) / (double) (bh - 1) * (p.length - 1));
            g.setColor(p[Math.max(0, Math.min(p.length - 1, i))]);
            g.fillRect(x, y + k, bw, 1);
        }
        final Color text = labelColor(false);
        g.setColor(text);
        g.drawRect(x, y, bw, bh);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        final double lo = scene.valueLo;
        final double hi = scene.valueHi;
        if (hi > lo) {
            final double[] t = ticks(lo, hi);
            final double step = t.length > 1 ? t[1] - t[0] : hi - lo;
            for (double v : t) {
                final int yy = y + bh - (int) Math.round((v - lo) / (hi - lo) * bh);
                g.drawLine(x + bw, yy, x + bw + 3, yy);
                g.drawString(label(v, step, scene.log[2] && scene.valueTitle.isEmpty()), x + bw + 5, yy + 4);
            }
        }
        if (scene.valueTitle != null && !scene.valueTitle.isBlank()) {
            g.drawString(scene.valueTitle, x - 4, y - 8);
        }
    }

    /* ------------------------------------------------------------------ */
    /* The mouse                                                           */
    /* ------------------------------------------------------------------ */

    /** What lies under a pixel of the final picture: the nearest visible vertex. */
    record Pick(Mesh3D mesh, int vertex, double x, double y, double z, double value, int sx, int sy) {
    }

    Pick pick(Scene3D scene, int mx, int my) {
        if (W == 0 || ss != 1) return null;
        Pick best = null;
        double bestD = 14 * 14;
        for (Mesh3D mesh : scene.meshes) {
            if (!mesh.visible || mesh.normalized) continue;
            final float[] s = screen.get(mesh);
            if (s == null) continue;
            final int n = mesh.vertexCount();
            for (int i = 0; i < n; i++) {
                final double dx = s[9 * i] - mx;
                final double dy = s[9 * i + 1] - my;
                final double d = dx * dx + dy * dy;
                if (d >= bestD) continue;
                final int px = (int) s[9 * i];
                final int py = (int) s[9 * i + 1];
                if (px < 0 || py < 0 || px >= W || py >= H) continue;
                // Hidden behind something nearer: not what the eye sees there.
                float front = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        final int xx = px + ox;
                        final int yy = py + oy;
                        if (xx >= 0 && yy >= 0 && xx < W && yy < H) front = Math.max(front, depth[yy * W + xx]);
                    }
                }
                if (front > 0 && s[9 * i + 2] < front * 0.985f) continue;
                bestD = d;
                best = new Pick(mesh, i, mesh.pos[3 * i], mesh.pos[3 * i + 1], mesh.pos[3 * i + 2], mesh.value[i],
                    px, py);
            }
        }
        return best;
    }
}
